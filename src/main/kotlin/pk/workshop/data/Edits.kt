package pk.workshop.data

import pk.workshop.core.Dates
import pk.workshop.core.Money
import pk.workshop.core.Qty
import pk.workshop.db.Db
import pk.workshop.db.fail

/**
 * Changing and deleting saved records.
 *
 * Plain records (customers, parts, staff…) are deleted outright when nothing refers to them; once they have history
 * (bills, stock movements, ledger lines) they are archived instead, so old bills and reports stay correct.
 *
 * Money and stock documents (invoices, payments, purchase bills, vouchers, salaries…) are really deleted: their ledger
 * entries and stock movements are removed as if they had never been posted, and editing one replaces it under the same
 * number. Every change is written to the change log first, and nothing in a locked month can change.
 */
object Edits {

    // ===================================================================== ledger

    /** Removes a voucher and its lines (and its reversal, if it had one). */
    fun dropVoucher(vid: Long) {
        val v = Db.one("SELECT * FROM vouchers WHERE id=?", vid) ?: return
        Ledger.checkOpen(v.str("date"))
        // A reversal pointing at this voucher goes with it; deleting a reversal re-opens the original
        Db.query("SELECT id FROM vouchers WHERE reversal_of=?", vid).forEach { dropVoucher(it.long("id")) }
        v.longOrNull("reversal_of")?.let { Db.exec("UPDATE vouchers SET reversed=0 WHERE id=?", it) }
        Db.exec("DELETE FROM year_closes WHERE voucher_id=?", vid)
        Db.exec("DELETE FROM bank_recon WHERE voucher_line_id IN (SELECT id FROM voucher_lines WHERE voucher_id=?)", vid)
        val total = Db.scalarLong("SELECT coalesce(sum(debit),0) FROM voucher_lines WHERE voucher_id=?", vid)
        Db.exec("DELETE FROM voucher_lines WHERE voucher_id=?", vid)
        Db.exec("DELETE FROM vouchers WHERE id=?", vid)
        Audit.log("delete", "voucher", vid, "${v.str("v_no")} ${v.str("narration")} ${Money.rs(total)}")
    }

    fun dropVouchers(refType: String, refId: Long) =
        Db.query("SELECT id FROM vouchers WHERE ref_type=? AND ref_id=? ORDER BY id DESC", refType, refId).forEach { dropVoucher(it.long("id")) }

    /** Replaces a voucher's date, narration and lines, keeping its number. */
    fun replaceVoucher(vid: Long, date: String, narration: String, lines: List<LedgerLine>) = Db.tx {
        val v = Ledger.voucher(vid)
        Ledger.checkOpen(v.date); Ledger.checkOpen(date)
        if (narration.isBlank()) fail("Enter a narration.")
        val clean = lines.filter { it.debit != 0L || it.credit != 0L }
        if (clean.isEmpty()) fail("The voucher has no amounts.")
        if (clean.any { it.debit < 0 || it.credit < 0 }) fail("Amounts cannot be negative.")
        val dr = clean.sumOf { it.debit }; val cr = clean.sumOf { it.credit }
        if (dr != cr) fail("Debits (${Money.rs(dr)}) and credits (${Money.rs(cr)}) must be equal.")
        for (l in clean) { val a = Accounts.get(l.accountId); if (a.isGroup) fail("${a.name} is a group; post to an account under it.") }
        Db.exec("DELETE FROM bank_recon WHERE voucher_line_id IN (SELECT id FROM voucher_lines WHERE voucher_id=?)", vid)
        Db.exec("DELETE FROM voucher_lines WHERE voucher_id=?", vid)
        Db.exec("UPDATE vouchers SET date=?, narration=? WHERE id=?", date, narration.trim(), vid)
        for (l in clean) Db.insert("INSERT INTO voucher_lines(voucher_id,account_id,debit,credit,party_type,party_id,memo) VALUES(?,?,?,?,?,?,?)",
            vid, l.accountId, l.debit, l.credit, l.partyType, l.partyId, l.memo)
        Audit.log("edit", "voucher", vid, "${v.no}: ${Money.rs(v.total)} → ${Money.rs(dr)} $narration")
    }

    /** The document that made this voucher, when it must be changed there instead (an invoice, a payment…). */
    fun voucherOwner(vid: Long): String? {
        val v = Ledger.voucher(vid)
        Db.one("SELECT pay_no, direction FROM payments WHERE voucher_id=?", vid)?.let {
            return (if (it.str("direction") == "IN") "receipt " else "supplier payment ") + it.str("pay_no") + (if (it.str("direction") == "IN") " (Billing › Payments received)" else " (Purchasing › Suppliers › Payments)")
        }
        Db.one("SELECT p.month, s.name FROM payroll p JOIN staff s ON s.id=p.staff_id WHERE p.voucher_id=?", vid)?.let { return "the ${it.str("month")} salary of ${it.str("name")} (Staff › Salaries)" }
        return when (v.refType) {
            "invoice" -> Db.scalarStr("SELECT inv_no FROM invoices WHERE id=?", v.refId)?.let { "invoice $it (Billing › Invoices)" }
            "purchase" -> Db.scalarStr("SELECT doc_no FROM purchases WHERE id=?", v.refId)?.let { "purchase $it (Purchasing)" }
            "asset" -> Db.scalarStr("SELECT name FROM fixed_assets WHERE id=?", v.refId)?.let { "the equipment \"$it\" (Bookkeeping › Fixed assets)" }
            else -> null
        }
    }

    /** Vouchers whose lines can be edited directly: manual vouchers, expenses, opening balances and staff advances. */
    fun voucherEditable(vid: Long): Boolean {
        val v = Ledger.voucher(vid)
        if (v.reversed || voucherOwner(vid) != null) return false
        return v.type in setOf("CPV", "CRV", "BPV", "BRV", "JV", "EXP", "OPEN") &&
            v.refType in setOf(null, "manual", "expense", "customer", "supplier", "account", "staff")
    }

    /**
     * Deletes a voucher from Bookkeeping. Vouchers made by documents are deleted through their document;
     * the rest undo whatever they were part of (a stock adjustment, a cheque status, a depreciation run…).
     */
    fun deleteVoucher(vid: Long) = Db.tx {
        val v = Ledger.voucher(vid)
        voucherOwner(vid)?.let { fail("This entry belongs to $it. Change or delete it there.") }
        when {
            v.type == "STK" || (v.type == "OPEN" && v.refType == "part") -> {
                val m = Db.one("SELECT id FROM stock_moves WHERE ref_type IN ('ADJUST','OPENING') AND ref_id=? AND part_id=?", vid, v.refId)?.long("id")
                    ?: legacyMoveFor(v)
                if (m != null) deleteStockMove(m) else dropVoucher(vid)
            }
            v.refType == "payment" && v.refId != null -> undoCheque(v.refId)
            v.refType == "warranty_claim" && v.refId != null -> deleteClaim(v.refId)
            v.type == "DEP" -> deleteDepreciation(vid)
            v.type == "CASH" -> { Db.exec("DELETE FROM cash_closings WHERE voucher_id=?", vid); dropVoucher(vid) }
            else -> dropVoucher(vid)
        }
    }

    /** Stock adjustments made before moves were linked to their voucher: match by part, date and value. */
    private fun legacyMoveFor(v: Voucher): Long? {
        val partId = v.refId ?: return null
        val kind = if (v.type == "STK") "ADJUST" else "OPENING"
        return Db.query("SELECT * FROM stock_moves WHERE part_id=? AND kind=? AND date=? AND (ref_id IS NULL OR ref_id=?) ORDER BY id", partId, kind, v.date, partId)
            .firstOrNull { Money.times(it.long("unit_cost"), kotlin.math.abs(it.long("qty"))) == v.total }?.long("id")
    }

    // ===================================================================== stock

    /** Reverses one stock movement's effect on quantity and average cost, then deletes it. */
    private fun unapply(moveId: Long) {
        val m = Db.one("SELECT * FROM stock_moves WHERE id=?", moveId) ?: return
        val p = Inventory.get(m.long("part_id"))
        val q = m.long("qty"); val c = m.long("unit_cost")
        val newQty = p.qty - q
        if (newQty > 0) {
            val value = java.math.BigDecimal.valueOf(p.qty).multiply(java.math.BigDecimal.valueOf(p.avgCost)) -
                java.math.BigDecimal.valueOf(q).multiply(java.math.BigDecimal.valueOf(c))
            val avg = value.divide(java.math.BigDecimal.valueOf(newQty), 0, java.math.RoundingMode.HALF_UP).toLong().coerceAtLeast(0)
            Db.exec("UPDATE parts SET avg_cost=? WHERE id=?", avg, p.id)
        }
        Db.exec("UPDATE parts SET qty=? WHERE id=?", newQty, p.id)
        Db.exec("DELETE FROM stock_moves WHERE id=?", moveId)
    }

    private fun unapplyMoves(refType: String, refId: Long): Set<Long> {
        val ms = Db.query("SELECT id, part_id FROM stock_moves WHERE ref_type=? AND ref_id=? ORDER BY id DESC", refType, refId)
        ms.forEach { unapply(it.long("id")) }
        return ms.map { it.long("part_id") }.toSet()
    }

    private fun trackedStock(): Long = Inventory.stockValue() + Db.scalarLong("""SELECT coalesce(sum(l.cost),0) FROM job_lines l JOIN job_cards j ON j.id=l.job_id
        WHERE l.issued=1 AND j.invoice_id IS NULL AND j.status<>'Cancelled'""")

    /**
     * Runs a change that touches stock. Afterwards no part may be below zero, and if the stock value moved by a different
     * amount than the Stock account in the ledger (e.g. a corrected purchase price for parts already sold), the difference
     * is posted to cost of parts so the books keep matching the shelves.
     */
    private var stockDepth = 0

    private fun <T> stockChange(label: String, date: String, block: () -> T): T = Db.tx {
        // Only the outermost change checks: an edit removes then re-adds, and may pass through negative stock on the way
        if (stockDepth > 0) return@tx block()
        val inv = Accounts.sys("INVENTORY")
        val tBefore = trackedStock(); val lBefore = Ledger.balance(inv)
        stockDepth++
        val r = try { block() } finally { stockDepth-- }
        Db.query("SELECT name, qty, unit FROM parts WHERE qty<0").firstOrNull()?.let {
            fail("That would leave ${Qty.fmt(it.long("qty"))} ${it.str("unit")} of ${it.str("name")} in stock: some of it has already been sold or used. " +
                "Change those bills or job cards first, or adjust the stock.")
        }
        val d = (trackedStock() - tBefore) - (Ledger.balance(inv) - lBefore)
        if (d != 0L) {
            val day = if (Ledger.isLocked(date)) Dates.today() else date
            Ledger.post("STK", day, "Stock value correction after $label",
                if (d > 0) listOf(LedgerLine(inv, debit = d), LedgerLine(Accounts.sys("COGS"), credit = d))
                else listOf(LedgerLine(Accounts.sys("COGS"), debit = -d), LedgerLine(inv, credit = -d)), "stock_fix", null)
        }
        r
    }

    /** Deletes a stock adjustment or opening-stock entry (other movements belong to bills and job cards). */
    fun deleteStockMove(moveId: Long) = Db.tx {
        val m = Db.one("SELECT m.*, p.name FROM stock_moves m JOIN parts p ON p.id=m.part_id WHERE m.id=?", moveId) ?: fail("Movement not found")
        val kind = m.str("kind")
        if (kind != "ADJUST" && kind != "OPENING") fail("This movement comes from ${m.str("ref_type")} ${m.str("note")}. Change that document instead.")
        Ledger.checkOpen(m.str("date"))
        stockChange("deleting a stock ${if (kind == "ADJUST") "adjustment" else "opening entry"}", m.str("date")) {
            val vid = m.longOrNull("ref_id")?.takeIf { id -> Db.scalarLong("SELECT count(*) FROM vouchers WHERE id=? AND ref_type='part' AND ref_id=? AND vtype IN ('STK','OPEN')", id, m.long("part_id")) > 0 }
                ?: Db.query("SELECT id FROM vouchers WHERE ref_type='part' AND ref_id=? AND vtype=? AND date=?", m.long("part_id"), if (kind == "ADJUST") "STK" else "OPEN", m.str("date"))
                    .map { it.long("id") }.firstOrNull { Ledger.voucher(it).total == Money.times(m.long("unit_cost"), kotlin.math.abs(m.long("qty"))) }
            vid?.let { dropVoucher(it) }
            unapply(moveId)
            Audit.log("delete", "stock_move", moveId, "${m.str("name")} $kind ${Qty.fmt(m.long("qty"))} ${m.str("note")}")
        }
    }

    /** Changes a stock adjustment's quantity or reason. */
    fun editStockAdjustment(moveId: Long, delta: Long, reason: String) = Db.tx {
        val m = Db.one("SELECT * FROM stock_moves WHERE id=?", moveId) ?: fail("Movement not found")
        if (m.str("kind") != "ADJUST") fail("Only stock adjustments can be changed here.")
        deleteStockMove(moveId)
        Inventory.adjust(m.long("part_id"), delta, reason, m.str("date"))
    }

    // ===================================================================== payments

    /** Allocates whatever part of a customer receipt isn't allocated yet to that customer's oldest unpaid invoices. */
    fun reallocateReceipt(paymentId: Long) {
        val p = Db.one("SELECT * FROM payments WHERE id=?", paymentId) ?: return
        if (p.str("cheque_status") == "Bounced") return
        var left = p.long("amount") + p.long("wht") - Db.scalarLong("SELECT coalesce(sum(amount),0) FROM payment_allocations WHERE payment_id=?", paymentId)
        if (left <= 0) return
        val cid = p.long("party_id")
        val open = Billing.list("0000-00-00", "9999-12-31", customerId = cid).filter { it.kind != "Credit note" && it.due > 0 }.sortedBy { it.date } +
            Billing.list("0000-00-00", "9999-12-31").filter { it.insurerId == cid && it.due > 0 }
        for (inv in open) {
            if (left <= 0) break
            val a = minOf(left, Billing.due(inv.id)); if (a <= 0) continue
            Db.insert("INSERT INTO payment_allocations(payment_id,invoice_id,amount) VALUES(?,?,?)", paymentId, inv.id, a); left -= a
        }
    }

    /** Same for a supplier payment, against that supplier's unpaid bills. */
    fun reallocateSupplierPayment(paymentId: Long) {
        val p = Db.one("SELECT * FROM payments WHERE id=?", paymentId) ?: return
        var left = p.long("amount") - Db.scalarLong("SELECT coalesce(sum(amount),0) FROM payment_allocations WHERE payment_id=?", paymentId)
        for (b in Purchasing.list("0000-00-00", "9999-12-31", p.long("party_id")).filter { it.kind == "Bill" && it.total - it.paid > 0 }.sortedBy { it.date }) {
            if (left <= 0) break
            val a = minOf(left, b.total - b.paid)
            Db.insert("INSERT INTO payment_allocations(payment_id,purchase_id,amount) VALUES(?,?,?)", paymentId, b.id, a); left -= a
        }
    }

    /** Deletes a receipt or a supplier payment, with its ledger entry and cheque entries. */
    fun deletePayment(paymentId: Long) = Db.tx {
        val p = Db.one("SELECT * FROM payments WHERE id=?", paymentId) ?: fail("Payment not found")
        if (p.str("method") == "Credit note") fail("This came from a credit note. Delete the credit note instead.")
        Ledger.checkOpen(p.str("date"))
        Db.query("SELECT id FROM vouchers WHERE ref_type='payment' AND ref_id=?", paymentId).forEach { dropVoucher(it.long("id")) }
        p.longOrNull("voucher_id")?.let { dropVoucher(it) }
        if (p.str("method") == "Loyalty points") {
            Db.query("SELECT * FROM loyalty_moves WHERE customer_id=? AND note=?", p.long("party_id"), "Redeemed on ${p.str("pay_no")}").forEach {
                Db.exec("UPDATE customers SET points=points-? WHERE id=?", it.long("points"), p.long("party_id"))
                Db.exec("DELETE FROM loyalty_moves WHERE id=?", it.long("id"))
            }
        }
        Db.exec("DELETE FROM payment_allocations WHERE payment_id=?", paymentId)
        Db.exec("DELETE FROM payments WHERE id=?", paymentId)
        Audit.log("delete", "payment", paymentId, "${p.str("pay_no")} ${p.str("method")} ${Money.rs(p.long("amount"))} ${Ledger.partyName(p.str("party_type"), p.long("party_id"))}")
    }

    data class PaymentEdit(val amount: Long, val method: String, val reference: String, val date: String, val note: String, val wht: Long = 0, val chequeDate: String? = null)

    /** Changes a payment: it is replaced under the same number. */
    fun editPayment(paymentId: Long, e: PaymentEdit): Long = Db.tx {
        val p = Db.one("SELECT * FROM payments WHERE id=?", paymentId) ?: fail("Payment not found")
        if (p.str("cheque_status") in setOf("Cleared", "Bounced")) fail("This cheque is already ${p.str("cheque_status").lowercase()}. Undo that first (Billing › Cheques).")
        deletePayment(paymentId)
        if (p.str("direction") == "IN") Billing.receive(p.long("party_id"), e.amount, e.method, e.reference, e.date, p.longOrNull("invoice_id"), e.wht, e.chequeDate, e.note, no = p.str("pay_no"), keepId = paymentId)
        else Purchasing.paySupplier(p.long("party_id"), e.amount, e.method, e.reference, e.date, p.longOrNull("purchase_id"), e.note, no = p.str("pay_no"), keepId = paymentId)
    }

    /** Puts a cleared or bounced cheque back to "in hand". */
    fun undoCheque(paymentId: Long) = Db.tx {
        val p = Db.one("SELECT * FROM payments WHERE id=?", paymentId) ?: fail("Cheque not found")
        if (p.str("cheque_status") == "In hand") fail("This cheque is still in hand.")
        Db.query("SELECT id FROM vouchers WHERE ref_type='payment' AND ref_id=?", paymentId).forEach { dropVoucher(it.long("id")) }
        Db.exec("UPDATE payments SET cheque_status='In hand' WHERE id=?", paymentId)
        reallocateReceipt(paymentId)
        Audit.log("undo", "cheque", paymentId, "${p.str("reference")} back to in hand")
    }

    // ===================================================================== invoices

    private fun reuseKey(jobId: Long) = "reuse.inv.job.$jobId"

    /** The number to give a job's invoice when it's billed again after its invoice was deleted to make changes. */
    fun reusedNumber(jobId: Long): String? = Settings.get(reuseKey(jobId)).ifBlank { null }
    fun clearReusedNumber(jobId: Long) { if (reusedNumber(jobId) != null) Settings.set(reuseKey(jobId), "") }

    private fun checkInvoiceChangeable(inv: Invoice) {
        Ledger.checkOpen(inv.date)
        if (inv.fbrNo.isNotBlank() && inv.fbrNo != "PENDING") fail("${inv.no} is reported to FBR (${inv.fbrNo}). Issue a credit note instead of changing it.")
        Db.scalarStr("SELECT inv_no FROM invoices WHERE against_id=?", inv.id)?.let { fail("Credit note $it was issued against ${inv.no}. Delete the credit note first.") }
    }

    /**
     * Deletes an invoice or credit note: its ledger entries, the stock it took (counter sales) or returned (credit notes),
     * loyalty points earned on it. Payments stay with the customer and are re-applied to their other unpaid bills.
     * A job card's bill returns the job to unbilled, ready to change and bill again.
     */
    fun deleteInvoice(invoiceId: Long, keepNumberForJob: Boolean = false, reapply: Boolean = true): Unit = Db.tx {
        val inv = Billing.get(invoiceId)
        checkInvoiceChangeable(inv)
        stockChange("deleting ${inv.no}", inv.date) {
            if (inv.kind == "Credit note") {
                unapplyMoves("CN", invoiceId)
                Db.query("SELECT id FROM payments WHERE method='Credit note' AND pay_no=?", inv.no).forEach {
                    Db.exec("DELETE FROM payment_allocations WHERE payment_id=?", it.long("id")); Db.exec("DELETE FROM payments WHERE id=?", it.long("id"))
                }
            } else unapplyMoves("INV", invoiceId)
            dropVouchers("invoice", invoiceId)
            Db.query("SELECT * FROM loyalty_moves WHERE invoice_id=? AND note LIKE 'Earned%'", invoiceId).forEach {
                Db.exec("UPDATE customers SET points=points-? WHERE id=?", it.long("points"), it.long("customer_id"))
                Db.exec("DELETE FROM loyalty_moves WHERE id=?", it.long("id"))
            }
            val freed = Db.query("SELECT DISTINCT payment_id FROM payment_allocations WHERE invoice_id=?", invoiceId).map { it.long("payment_id") }
            Db.exec("DELETE FROM payment_allocations WHERE invoice_id=?", invoiceId)
            Db.exec("UPDATE payments SET invoice_id=NULL WHERE invoice_id=?", invoiceId)
            Db.exec("UPDATE loyalty_moves SET invoice_id=NULL WHERE invoice_id=?", invoiceId)
            Db.exec("UPDATE invoices SET against_id=NULL WHERE against_id=?", invoiceId)
            if (inv.jobId != null && inv.kind != "Credit note") {
                Db.exec("UPDATE job_cards SET invoice_id=NULL, status=CASE WHEN status='Delivered' THEN 'Ready' ELSE status END, delivered_at=NULL WHERE id=? AND invoice_id=?", inv.jobId, invoiceId)
                if (keepNumberForJob) Settings.set(reuseKey(inv.jobId), inv.no)
            }
            Db.exec("DELETE FROM invoice_lines WHERE invoice_id=?", invoiceId)
            Db.exec("DELETE FROM invoices WHERE id=?", invoiceId)
            if (reapply) freed.forEach { reallocateReceipt(it) }
            Audit.log("delete", "invoice", invoiceId, "${inv.no} ${inv.kind} ${inv.customer} ${Money.rs(inv.total)}")
        }
    }

    /** Changes a counter-sale invoice: replaced under the same number, with its payments re-applied. */
    fun editCounterInvoice(invoiceId: Long, date: String, customerId: Long, lines: List<InvLine>, discount: Long, notes: String): Long = Db.tx {
        val inv = Billing.get(invoiceId)
        if (inv.jobId != null) fail("This bill comes from job card ${inv.jobNo}. Use \"Change the job and bill again\".")
        if (inv.kind == "Credit note") fail("A credit note can't be edited. Delete it and issue it again.")
        val allocs = Db.query("SELECT payment_id, amount FROM payment_allocations WHERE invoice_id=? ORDER BY id", invoiceId).map { it.long("payment_id") to it.long("amount") }
        val id = stockChange("changing ${inv.no}", date) {
            deleteInvoice(invoiceId, reapply = false)
            Billing.post(inv.kind, date, customerId, inv.vehicleId, null, lines, discount, notes, inv.insurerId, inv.insurerShare, no = inv.no, keepId = inv.id)
        }
        // The payments that were on it go back on it first
        for ((pid, amt) in allocs) {
            val a = minOf(amt, Billing.due(id)); if (a > 0) Db.insert("INSERT INTO payment_allocations(payment_id,invoice_id,amount) VALUES(?,?,?)", pid, id, a)
        }
        allocs.forEach { reallocateReceipt(it.first) }
        Audit.log("edit", "invoice", id, "${inv.no}: ${Money.rs(inv.total)} → ${Money.rs(Billing.get(id).total)}")
        id
    }

    // ===================================================================== purchases

    /** Deletes a purchase bill or return: stock and ledger entries go, payments stay with the supplier. */
    fun deletePurchase(purchaseId: Long, reapply: Boolean = true): Unit = Db.tx {
        val p = Purchasing.get(purchaseId)
        Ledger.checkOpen(p.date)
        stockChange("deleting ${p.docNo}", p.date) {
            unapplyMoves(if (p.kind == "Return") "PR" else "PB", purchaseId)
            dropVouchers("purchase", purchaseId)
            val freed = Db.query("SELECT DISTINCT payment_id FROM payment_allocations WHERE purchase_id=?", purchaseId).map { it.long("payment_id") }
            Db.exec("DELETE FROM payment_allocations WHERE purchase_id=?", purchaseId)
            Db.exec("UPDATE payments SET purchase_id=NULL WHERE purchase_id=?", purchaseId)
            Db.exec("UPDATE purchase_orders SET status='Open', bill_id=NULL WHERE bill_id=?", purchaseId)
            Db.exec("DELETE FROM purchase_lines WHERE purchase_id=?", purchaseId)
            Db.exec("DELETE FROM purchases WHERE id=?", purchaseId)
            if (reapply) freed.forEach { reallocateSupplierPayment(it) }
            Audit.log("delete", "purchase", purchaseId, "${p.docNo} ${p.supplier} ${Money.rs(p.total)}")
        }
    }

    /** Changes a purchase bill or return: replaced under the same number; its payments and purchase order stay linked. */
    fun editPurchase(purchaseId: Long, supplierId: Long, billNo: String, date: String, lines: List<DocLine>, landed: Long, notes: String): Long = Db.tx {
        val p = Purchasing.get(purchaseId)
        val allocs = Db.query("SELECT payment_id, amount FROM payment_allocations WHERE purchase_id=?", purchaseId).map { it.long("payment_id") to it.long("amount") }
        val po = Db.scalarStr("SELECT id FROM purchase_orders WHERE bill_id=?", purchaseId)?.toLong()
        val id = stockChange("changing ${p.docNo}", date) {
            deletePurchase(purchaseId, reapply = false)
            if (p.kind == "Return") Purchasing.postReturn(supplierId, date, lines, notes, no = p.docNo, keepId = p.id)
            else Purchasing.postBill(supplierId, billNo, date, lines, landed, notes, no = p.docNo, keepId = p.id)
        }
        if (po != null) Db.exec("UPDATE purchase_orders SET status='Received', bill_id=? WHERE id=?", id, po)
        if (p.kind == "Bill" && supplierId == p.supplierId) for ((pid, amt) in allocs) {
            val due = Purchasing.get(id).let { it.total - it.paid }
            minOf(amt, due).takeIf { it > 0 }?.let { Db.insert("INSERT INTO payment_allocations(payment_id,purchase_id,amount) VALUES(?,?,?)", pid, id, it) }
        }
        allocs.forEach { reallocateSupplierPayment(it.first) }
        Audit.log("edit", "purchase", id, "${p.docNo}: ${Money.rs(p.total)} → ${Money.rs(Purchasing.get(id).total)}")
        id
    }

    fun editOrder(poId: Long, supplierId: Long, date: String, lines: List<DocLine>, notes: String) = Db.tx {
        val o = Db.one("SELECT * FROM purchase_orders WHERE id=?", poId) ?: fail("Order not found")
        if (o.str("status") == "Received") fail("This order has arrived; change its bill instead.")
        if (lines.isEmpty()) fail("Add at least one part.")
        Db.exec("UPDATE purchase_orders SET supplier_id=?, date=?, notes=? WHERE id=?", supplierId, date, notes, poId)
        Db.exec("DELETE FROM po_lines WHERE po_id=?", poId)
        lines.forEach { Db.insert("INSERT INTO po_lines(po_id,part_id,qty,cost) VALUES(?,?,?,?)", poId, it.partId, it.qty, it.cost) }
        Audit.log("edit", "purchase_order", poId, o.str("po_no"))
    }

    fun deleteOrder(poId: Long) = Db.tx {
        val o = Db.one("SELECT * FROM purchase_orders WHERE id=?", poId) ?: fail("Order not found")
        if (o.str("status") == "Received") fail("This order has arrived as a bill. Delete the bill first; the order then re-opens.")
        Db.exec("DELETE FROM po_lines WHERE po_id=?", poId); Db.exec("DELETE FROM purchase_orders WHERE id=?", poId)
        Audit.log("delete", "purchase_order", poId, o.str("po_no"))
    }

    /** Deletes a warranty claim, undoing its outcome (replacement stock, or the supplier's credit). */
    fun deleteClaim(claimId: Long) = Db.tx {
        val c = Db.one("SELECT * FROM warranty_claims WHERE id=?", claimId) ?: fail("Claim not found")
        val date = c.str("resolved_date").ifBlank { c.str("date") }
        stockChange("deleting warranty claim #$claimId", date) {
            unapplyMoves("WC", claimId)
            dropVouchers("warranty_claim", claimId)
            Db.exec("DELETE FROM warranty_claims WHERE id=?", claimId)
            Audit.log("delete", "warranty_claim", claimId, c.str("reason"))
        }
    }

    fun editClaim(claimId: Long, qty: Long, reason: String) {
        val c = Db.one("SELECT * FROM warranty_claims WHERE id=?", claimId) ?: fail("Claim not found")
        if (c.str("status") != "Sent") fail("This claim is closed. Delete it and enter it again.")
        if (qty <= 0) fail("Enter the quantity.")
        Db.exec("UPDATE warranty_claims SET qty=?, reason=? WHERE id=?", qty, reason, claimId)
    }

    // ===================================================================== job cards

    /** Deletes a job card that isn't billed. Parts issued to it go back on the shelf. */
    fun deleteJob(jobId: Long) = Db.tx {
        val j = Jobs.get(jobId)
        j.invoiceId?.let { fail("${j.jobNo} is billed (${Billing.get(it).no}). Delete the invoice first.") }
        stockChange("deleting ${j.jobNo}", Dates.today()) {
            unapplyMoves("JC", jobId)
            Jobs.attachments(jobId).forEach { runCatching { java.io.File(it.str("path")).delete() } }
            Db.exec("DELETE FROM attachments WHERE entity='job' AND entity_id=?", jobId)
            Db.exec("DELETE FROM job_lines WHERE job_id=?", jobId)
            Db.exec("DELETE FROM job_time WHERE job_id=?", jobId)
            Db.exec("DELETE FROM estimates WHERE job_id=?", jobId)
            Db.exec("UPDATE bookings SET job_id=NULL, status=CASE WHEN status='Arrived' THEN 'Booked' ELSE status END WHERE job_id=?", jobId)
            Db.exec("UPDATE job_cards SET comeback_of=NULL WHERE comeback_of=?", jobId)
            Db.exec("DELETE FROM job_cards WHERE id=?", jobId)
            clearReusedNumber(jobId)
            Audit.log("delete", "job", jobId, "${j.jobNo} ${j.vehicleLabel}")
        }
    }

    // ===================================================================== staff and books

    fun deletePayroll(payrollId: Long) = Db.tx {
        val p = Db.one("SELECT p.*, s.name FROM payroll p JOIN staff s ON s.id=p.staff_id WHERE p.id=?", payrollId) ?: fail("Salary not found")
        p.longOrNull("voucher_id")?.let { dropVoucher(it) }
        Db.exec("DELETE FROM payroll WHERE id=?", payrollId)
        Audit.log("delete", "payroll", payrollId, "${p.str("name")} ${p.str("month")} ${Money.rs(p.long("net"))}")
    }

    fun editPayroll(payrollId: Long, basic: Long, commission: Long, advanceDeducted: Long, other: Long, method: String, date: String): Long = Db.tx {
        val p = Db.one("SELECT * FROM payroll WHERE id=?", payrollId) ?: fail("Salary not found")
        deletePayroll(payrollId)
        StaffBook.paySalary(p.long("staff_id"), p.str("month"), basic, commission, advanceDeducted, other, method, date)
    }

    fun deleteAttendance(id: Long) { Db.exec("DELETE FROM attendance WHERE id=?", id) }
    fun editAttendance(id: Long, date: String, timeIn: String?, timeOut: String?) {
        Db.exec("UPDATE attendance SET date=?, time_in=?, time_out=? WHERE id=?", date, timeIn?.ifBlank { null }, timeOut?.ifBlank { null }, id)
    }

    fun deleteCashClosing(id: Long) = Db.tx {
        val c = Db.one("SELECT * FROM cash_closings WHERE id=?", id) ?: fail("Closing not found")
        Ledger.checkOpen(c.str("date"))
        c.longOrNull("voucher_id")?.let { dropVoucher(it) }
        Db.exec("DELETE FROM cash_closings WHERE id=?", id)
        Audit.log("delete", "cash_closing", id, c.str("date"))
    }

    /** Deletes a month's depreciation (only the latest one, so the running totals stay right). */
    fun deleteDepreciation(vid: Long) = Db.tx {
        val v = Ledger.voucher(vid)
        val month = v.date.take(7)
        val later = Db.scalarStr("SELECT max(last_month) FROM fixed_assets") ?: month
        if (later > month) fail("Delete the depreciation for $later first; months are undone latest first.")
        val prev = java.time.YearMonth.parse(month).minusMonths(1).toString()
        for (a in Assets.list().filter { it.lastMonth == month }) {
            val amount = minOf(a.monthly, a.depreciated)
            Db.exec("UPDATE fixed_assets SET depreciated=depreciated-?, last_month=? WHERE id=?", amount,
                if (a.depreciated - amount <= 0 || a.bought.take(7) > prev) null else prev, a.id)
        }
        dropVoucher(vid)
    }

    /** Changes equipment details. Cost and purchase date can change until depreciation has been charged on it. */
    fun editAsset(a: FixedAsset) = Db.tx {
        val old = Assets.list().firstOrNull { it.id == a.id } ?: fail("Equipment not found")
        if (a.name.isBlank()) fail("Enter the name.")
        if (a.lifeMonths <= 0) fail("Enter the useful life in months.")
        if ((a.cost != old.cost || a.bought != old.bought) && old.depreciated > 0) fail("Depreciation has been charged on it, so its cost and date can't change. Delete those depreciation months first.")
        if (a.cost != old.cost || a.bought != old.bought) {
            if (a.cost <= 0) fail("Enter the cost.")
            Db.query("SELECT id FROM vouchers WHERE ref_type='asset' AND ref_id=?", a.id).forEach { r ->
                val ls = Ledger.lines(r.long("id"))
                val credit = ls.first { it.credit > 0 }.accountId
                replaceVoucher(r.long("id"), a.bought, "Equipment: ${a.name}",
                    listOf(LedgerLine(Accounts.sys("FIXED_ASSETS"), debit = a.cost), LedgerLine(credit, credit = a.cost)))
            }
        }
        Db.exec("UPDATE fixed_assets SET name=?, category=?, bought=?, cost=?, salvage=?, life_months=?, notes=?, disposed=? WHERE id=?",
            a.name, a.category, a.bought, a.cost, a.salvage, a.lifeMonths, a.notes, a.disposed, a.id)
        Audit.log("edit", "asset", a.id, a.name)
    }

    fun deleteAsset(id: Long) = Db.tx {
        val a = Assets.list().firstOrNull { it.id == id } ?: fail("Equipment not found")
        if (a.depreciated > 0) fail("Depreciation has been charged on ${a.name}. Delete those months first (Bookkeeping › Vouchers, type DEP), or mark it disposed.")
        dropVouchers("asset", id)
        Db.exec("DELETE FROM fixed_assets WHERE id=?", id)
        Audit.log("delete", "asset", id, a.name)
    }

    // ===================================================================== plain records

    /** Result of a delete: erased, or archived (hidden) because other records use it. */
    enum class Removed { DELETED, ARCHIVED }

    private fun used(vararg checks: Pair<String, Long>): Boolean = checks.any { (sql, id) -> Db.scalarLong(sql, id) > 0 }

    fun deleteCustomer(id: Long): Removed = Db.tx {
        val c = Customers.get(id)
        if (c.name == "Walk-in customer") fail("The walk-in customer is used by counter sales and can't be deleted.")
        val busy = used("SELECT count(*) FROM invoices WHERE customer_id=?" to id,
            "SELECT count(*) FROM invoices WHERE insurer_id=?" to id, "SELECT count(*) FROM job_cards WHERE customer_id=?" to id,
            "SELECT count(*) FROM payments WHERE party_type='C' AND party_id=?" to id, "SELECT count(*) FROM voucher_lines WHERE party_type='C' AND party_id=?" to id)
        if (busy) {
            Db.exec("UPDATE customers SET active=0 WHERE id=?", id); Audit.log("archive", "customer", id, c.name); Removed.ARCHIVED
        } else {
            Db.exec("UPDATE bookings SET customer_id=NULL, vehicle_id=NULL WHERE customer_id=?", id)
            Db.exec("DELETE FROM loyalty_moves WHERE customer_id=?", id)
            Db.exec("DELETE FROM vehicles WHERE customer_id=?", id)
            Db.exec("DELETE FROM customers WHERE id=?", id)
            Audit.log("delete", "customer", id, c.name); Removed.DELETED
        }
    }

    fun deleteVehicle(id: Long) = Db.tx {
        val v = Vehicles.get(id)
        val jobs = Db.scalarLong("SELECT count(*) FROM job_cards WHERE vehicle_id=?", id) + Db.scalarLong("SELECT count(*) FROM invoices WHERE vehicle_id=?", id)
        if (jobs > 0) fail("${v.regNo} has job cards and bills. Delete those first, or move the car to its new owner (Edit › Transfer).")
        Db.exec("UPDATE bookings SET vehicle_id=NULL WHERE vehicle_id=?", id)
        Db.exec("DELETE FROM vehicles WHERE id=?", id)
        Audit.log("delete", "vehicle", id, v.regNo)
    }

    fun deletePart(id: Long): Removed = Db.tx {
        val p = Inventory.get(id)
        val busy = used("SELECT count(*) FROM stock_moves WHERE part_id=?" to id, "SELECT count(*) FROM job_lines WHERE part_id=?" to id,
            "SELECT count(*) FROM invoice_lines WHERE part_id=?" to id, "SELECT count(*) FROM purchase_lines WHERE part_id=?" to id,
            "SELECT count(*) FROM po_lines WHERE part_id=?" to id, "SELECT count(*) FROM warranty_claims WHERE part_id=?" to id)
        if (busy) {
            if (p.qty != 0L) fail("${p.name} still has ${Qty.fmt(p.qty)} ${p.unit} in stock. Adjust the stock to zero first, then delete it.")
            Db.exec("UPDATE parts SET active=0 WHERE id=?", id); Audit.log("archive", "part", id, p.name); Removed.ARCHIVED
        } else {
            Db.exec("DELETE FROM stock_count_lines WHERE part_id=?", id)
            Db.exec("DELETE FROM part_barcodes WHERE part_id=?", id)
            Db.exec("DELETE FROM parts WHERE id=?", id)
            Audit.log("delete", "part", id, p.name); Removed.DELETED
        }
    }

    fun deleteSupplier(id: Long): Removed = Db.tx {
        val s = Suppliers.get(id)
        val busy = used("SELECT count(*) FROM purchases WHERE supplier_id=?" to id, "SELECT count(*) FROM purchase_orders WHERE supplier_id=?" to id,
            "SELECT count(*) FROM warranty_claims WHERE supplier_id=?" to id, "SELECT count(*) FROM payments WHERE party_type='S' AND party_id=?" to id,
            "SELECT count(*) FROM voucher_lines WHERE party_type='S' AND party_id=?" to id)
        if (busy) { Db.exec("UPDATE suppliers SET active=0 WHERE id=?", id); Audit.log("archive", "supplier", id, s.name); Removed.ARCHIVED }
        else { Db.exec("DELETE FROM suppliers WHERE id=?", id); Audit.log("delete", "supplier", id, s.name); Removed.DELETED }
    }

    fun deleteTechnician(id: Long): Removed = Db.tx {
        val busy = used("SELECT count(*) FROM job_lines WHERE technician_id=?" to id, "SELECT count(*) FROM invoice_lines WHERE technician_id=?" to id,
            "SELECT count(*) FROM job_time WHERE technician_id=?" to id)
        if (busy) { Db.exec("UPDATE technicians SET active=0 WHERE id=?", id); Removed.ARCHIVED }
        else { Db.exec("UPDATE staff SET technician_id=NULL WHERE technician_id=?", id); Db.exec("DELETE FROM technicians WHERE id=?", id); Removed.DELETED }
            .also { Audit.log(if (it == Removed.DELETED) "delete" else "archive", "technician", id) }
    }

    fun deleteService(id: Long) { Db.exec("DELETE FROM services WHERE id=?", id); Audit.log("delete", "service", id) }

    fun deleteStaff(id: Long): Removed = Db.tx {
        val s = StaffBook.get(id)
        val busy = used("SELECT count(*) FROM payroll WHERE staff_id=?" to id, "SELECT count(*) FROM voucher_lines WHERE party_type='E' AND party_id=?" to id)
        if (busy) { Db.exec("UPDATE staff SET active=0 WHERE id=?", id); Audit.log("archive", "staff", id, s.name); Removed.ARCHIVED }
        else { Db.exec("DELETE FROM attendance WHERE staff_id=?", id); Db.exec("DELETE FROM staff WHERE id=?", id); Audit.log("delete", "staff", id, s.name); Removed.DELETED }
    }

    fun deleteAccount(id: Long): Removed = Db.tx {
        val a = Accounts.get(id)
        if (a.sys != null) fail("${a.name} is built in: automatic postings use it, so it can't be deleted.")
        if (Db.scalarLong("SELECT count(*) FROM accounts WHERE parent_id=?", id) > 0) fail("Move or delete the accounts under ${a.name} first.")
        if (Db.scalarLong("SELECT count(*) FROM voucher_lines WHERE account_id=?", id) > 0) {
            Db.exec("UPDATE accounts SET active=0 WHERE id=?", id); Audit.log("archive", "account", id, a.label); Removed.ARCHIVED
        } else { Db.exec("DELETE FROM accounts WHERE id=?", id); Audit.log("delete", "account", id, a.label); Removed.DELETED }
    }

    fun deleteBooking(id: Long) { Db.exec("DELETE FROM bookings WHERE id=?", id); Audit.log("delete", "booking", id) }
    fun deleteEstimate(id: Long) { Db.exec("DELETE FROM estimates WHERE id=?", id) }

    fun deleteCount(id: Long) = Db.tx {
        val st = Db.scalarStr("SELECT status FROM stock_counts WHERE id=?", id) ?: fail("Count not found")
        if (st == "Approved") {
            // Undo the adjustments the count made
            Db.query("SELECT id FROM stock_moves WHERE kind='ADJUST' AND note=?", "Stock count #$id").forEach { deleteStockMove(it.long("id")) }
        }
        Db.exec("DELETE FROM stock_count_lines WHERE count_id=?", id); Db.exec("DELETE FROM stock_counts WHERE id=?", id)
        Audit.log("delete", "stock_count", id)
    }

    /** What deleting means for this kind of record, in words, for the confirmation box. */
    fun message(r: Removed, what: String) = if (r == Removed.DELETED) "$what deleted" else "$what has history, so it was archived (hidden) instead of erased"
}
