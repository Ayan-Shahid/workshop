package pk.workshop

import pk.workshop.core.Dates
import pk.workshop.core.Money
import pk.workshop.data.*
import pk.workshop.db.Db
import kotlin.test.*

/** Edits and deletes across every kind of record, on the full sample data, keeping the books and the shelves in step. */
class EditsTest {
    private fun n(sql: String, vararg a: Any?) = Db.scalarLong(sql, *a)

    private fun checkBooks(step: String) {
        val today = Dates.today()
        val tb = Ledger.trialBalance(today)
        assertEquals(tb.sumOf { it.debit }, tb.sumOf { it.credit }, "trial balance after $step")
        assertEquals(0, n("SELECT count(*) FROM (SELECT voucher_id FROM voucher_lines GROUP BY voucher_id HAVING sum(debit)<>sum(credit))"), "unbalanced voucher after $step")
        val bs = Ledger.balanceSheet(today)
        assertEquals(bs.totalAssets, bs.totalLiabilities + bs.totalEquity, "balance sheet after $step")
        val inv = Ledger.balance(Accounts.sys("INVENTORY"))
        val wip = n("""SELECT coalesce(sum(l.cost),0) FROM job_lines l JOIN job_cards j ON j.id=l.job_id WHERE l.issued=1 AND j.invoice_id IS NULL AND j.status<>'Cancelled'""")
        assertTrue(kotlin.math.abs(inv - Inventory.stockValue() - wip) < 100_00, "stock ledger ${Money.rs(inv)} vs shelf ${Money.rs(Inventory.stockValue())} + jobs ${Money.rs(wip)} after $step")
        assertEquals(0, n("SELECT count(*) FROM parts WHERE qty<0"), "negative stock after $step")
        assertEquals(0, n("SELECT count(*) FROM payment_allocations a WHERE invoice_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM invoices i WHERE i.id=a.invoice_id)"), "dangling allocation after $step")
        // No invoice is paid more than its total
        assertEquals(0, n("""SELECT count(*) FROM invoices i WHERE kind<>'Credit note' AND total < (SELECT coalesce(sum(amount),0) FROM payment_allocations WHERE invoice_id=i.id)"""), "overpaid invoice after $step")
    }

    private fun open(date: String) = !Ledger.isLocked(date)

    @Test fun everythingCanBeChangedOrDeleted() {
        val dir = kotlin.io.path.createTempDirectory("wsedit").toFile()
        System.setProperty("workshop.dataDir", dir.path)
        System.setProperty("workshop.demo", "1")
        try { assertTrue(Demo.maybeStart()) } finally { System.clearProperty("workshop.demo") }
        checkBooks("seeding")

        // ---- counter sale: edit (more quantity, new price), then delete
        val counter = Billing.list("2000-01-01", "2100-01-01").first { it.kind == "Counter" && it.jobId == null && open(it.date) && it.paid > 0 }
        val ls = Billing.lines(counter.id)
        val part = ls.first { it.partId != null }
        val shelfBefore = Inventory.get(part.partId!!).qty
        val edited = Edits.editCounterInvoice(counter.id, counter.date, counter.customerId!!,
            ls.map { if (it === part) InvLine(it.kind, it.partId, it.description, it.qty + 1000, it.rate + 10000, 0) else InvLine(it.kind, it.partId, it.description, it.qty, it.rate, it.discount) }, 0, "")
        val after = Billing.get(edited)
        assertEquals(counter.no, after.no, "edit keeps the number")
        assertEquals(counter.id, edited, "edit keeps the record")
        assertTrue(after.total > counter.total)
        assertEquals(minOf(counter.paid, after.total), after.paid, "payments stay on the edited bill")
        assertEquals(shelfBefore - 1000, Inventory.get(part.partId).qty, "one more unit off the shelf")
        checkBooks("editing a counter sale")
        Edits.deleteInvoice(edited)
        assertEquals(shelfBefore + part.qty, Inventory.get(part.partId).qty, "deleting the sale puts its parts back")
        assertNull(Billing.byNo(counter.no))
        checkBooks("deleting a counter sale")

        // ---- job invoice: take it off, change the job, bill again under the same number
        val jobInv = Billing.list("2000-01-01", "2100-01-01").first { it.jobId != null && it.kind == "Service" && open(it.date) &&
            n("SELECT count(*) FROM invoices WHERE against_id=?", it.id) == 0L }
        Edits.deleteInvoice(jobInv.id, keepNumberForJob = true)
        val job = Jobs.get(jobInv.jobId!!)
        assertNull(job.invoiceId)
        val labour = Jobs.lines(job.id).firstOrNull { it.kind == "LABOUR" }
        if (labour != null) Jobs.updateLine(labour.copy(rate = labour.rate + 50000, qty = labour.qty))
        val issued = Jobs.lines(job.id).firstOrNull { it.issued && it.partId != null && Inventory.get(it.partId).qty >= 1000 }
        if (issued != null) Jobs.updateLine(issued.copy(qty = issued.qty + 1000))
        checkBooks("changing a job's lines")
        val again = Billing.invoiceJob(job.id, 0, "")
        assertEquals(jobInv.no, Billing.get(again).no, "re-billed job keeps its invoice number")
        checkBooks("re-billing a job")

        // ---- purchase bill: correct a price (parts may already be sold), then try deleting bills
        val bill = Purchasing.list("2000-01-01", "2100-01-01").first { it.kind == "Bill" && open(it.date) }
        val bl = Purchasing.lines(bill.id).map { DocLine(it.long("part_id"), it.long("qty"), it.long("cost") + 5000) }
        val newBill = Edits.editPurchase(bill.id, bill.supplierId, bill.billNo, bill.date, bl, bill.landed, bill.notes)
        assertEquals(bill.docNo, Purchasing.get(newBill).docNo)
        assertEquals(bill.id, newBill)
        assertTrue(Purchasing.get(newBill).total > bill.total)
        checkBooks("correcting a purchase price")
        var deletedBill = false
        for (b in Purchasing.list("2000-01-01", "2100-01-01").filter { it.kind == "Bill" && open(it.date) }.sortedByDescending { it.date }) {
            try { Edits.deletePurchase(b.id); deletedBill = true; break } catch (e: pk.workshop.db.AppError) { assertTrue("already been sold" in (e.message ?: ""), e.message) }
            checkBooks("refusing to delete a used bill")
        }
        assertTrue(deletedBill, "at least one recent bill can be deleted")
        checkBooks("deleting a purchase bill")

        // ---- payments
        val receipt = Billing.payments("2000-01-01", "2100-01-01", "IN").first { it.method == "Cash" && open(it.date) }
        Edits.editPayment(receipt.id, Edits.PaymentEdit(receipt.amount / 2, "JazzCash", "TX123", receipt.date, "corrected"))
        val changed = Billing.payments("2000-01-01", "2100-01-01", "IN").first { it.no == receipt.no }
        assertEquals("JazzCash", changed.method); assertEquals(receipt.amount / 2, changed.amount)
        checkBooks("editing a receipt")
        Edits.deletePayment(changed.id)
        checkBooks("deleting a receipt")
        val sp = Billing.payments("2000-01-01", "2100-01-01", "OUT").first { open(it.date) }
        Edits.deletePayment(sp.id)
        checkBooks("deleting a supplier payment")
        Billing.cheques("Cleared").firstOrNull()?.takeIf { open(it.str("date")) }?.let { Edits.undoCheque(it.long("id")); checkBooks("undoing a cheque") }

        // ---- vouchers: delete an expense, edit a journal
        val exp = Ledger.vouchers("2000-01-01", "2100-01-01", "EXP").first { open(it.date) }
        assertTrue(Edits.voucherEditable(exp.id))
        val el = Ledger.lines(exp.id)
        Edits.replaceVoucher(exp.id, exp.date, exp.narration + " (fixed)", el.map { LedgerLine(it.accountId, if (it.debit > 0) it.debit + 10000 else 0, if (it.credit > 0) it.credit + 10000 else 0) })
        assertEquals(exp.total + 10000, Ledger.voucher(exp.id).total)
        Edits.deleteVoucher(exp.id)
        assertEquals(0, n("SELECT count(*) FROM vouchers WHERE id=?", exp.id))
        checkBooks("expense edit and delete")
        val sale = Ledger.vouchers("2000-01-01", "2100-01-01", "SALE").first()
        assertNotNull(Edits.voucherOwner(sale.id))
        assertFailsWith<pk.workshop.db.AppError> { Edits.deleteVoucher(sale.id) }

        // ---- stock adjustment and depreciation
        Db.query("SELECT id, date FROM stock_moves WHERE kind='ADJUST' ORDER BY id DESC").firstOrNull { open(it.str("date")) }?.let {
            Edits.deleteStockMove(it.long("id")); checkBooks("deleting a stock adjustment")
        }
        Ledger.vouchers("2000-01-01", "2100-01-01", "DEP").maxByOrNull { it.date }?.takeIf { open(it.date) }?.let {
            val before = Assets.list().sumOf { a -> a.depreciated }
            Edits.deleteVoucher(it.id)
            assertEquals(before - it.total, Assets.list().sumOf { a -> a.depreciated }, "depreciation rolled back")
            checkBooks("deleting depreciation")
        }

        // ---- payroll
        Db.query("SELECT p.*, v.date FROM payroll p JOIN vouchers v ON v.id=p.voucher_id ORDER BY p.id DESC").firstOrNull { open(it.str("date")) }?.let { p ->
            val id = Edits.editPayroll(p.long("id"), p.long("basic") + 100000, p.long("commission"), p.long("advance_deducted"), p.long("other_deduction"), p.str("method"), p.str("date"))
            assertEquals(p.long("net") + 100000, n("SELECT net FROM payroll WHERE id=?", id))
            Edits.deletePayroll(id)
            checkBooks("payroll edit and delete")
        }

        // ---- an open job with parts on it
        Jobs.list(openOnly = true).firstOrNull { j -> j.invoiceId == null && Jobs.lines(j.id).any { it.issued } }?.let {
            Edits.deleteJob(it.id); assertEquals(0, n("SELECT count(*) FROM job_cards WHERE id=?", it.id)); checkBooks("deleting an open job")
        }

        // ---- plain records
        val fresh = Customers.save(Customer(name = "Test Person", phone = "0333-1112223"))
        Vehicles.save(Vehicle(customerId = fresh, regNo = "TST-1"))
        assertEquals(Edits.Removed.DELETED, Edits.deleteCustomer(fresh))
        assertEquals(0, n("SELECT count(*) FROM vehicles WHERE reg_no='TST-1'"))
        val busy = Billing.list("2000-01-01", "2100-01-01").first { it.kind == "Service" }.customerId!!
        assertEquals(Edits.Removed.ARCHIVED, Edits.deleteCustomer(busy))
        val newPart = Inventory.save(Part(name = "Test part"))
        assertEquals(Edits.Removed.DELETED, Edits.deletePart(newPart))
        val s = Suppliers.save(Supplier(name = "Nobody Ltd"))
        assertEquals(Edits.Removed.DELETED, Edits.deleteSupplier(s))

        // ---- locked months refuse changes
        val old = Billing.list("2000-01-01", "2100-01-01").filter { it.kind != "Credit note" && n("SELECT count(*) FROM invoices WHERE against_id=?", it.id) == 0L }.minByOrNull { it.date }!!
        Ledger.lock(old.date.take(7))
        val e = assertFailsWith<pk.workshop.db.AppError> { Edits.deleteInvoice(old.id) }
        assertTrue("locked" in (e.message ?: ""))
        Ledger.unlock(old.date.take(7))
        println("Edits test: " + listOf("ADJUST", "DEP").joinToString { k -> k + "=" + n("SELECT count(*) FROM audit_log WHERE action='delete' AND detail LIKE ?", "%$k%") } + ", stock fixes=" + n("SELECT count(*) FROM vouchers WHERE ref_type='stock_fix'"))
        checkBooks("the end")
        assertTrue(n("SELECT count(*) FROM audit_log WHERE action IN ('delete','edit')") > 10, "changes are in the change log")
        Db.close()
    }
}
