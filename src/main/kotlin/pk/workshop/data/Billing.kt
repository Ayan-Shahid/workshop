package pk.workshop.data

import pk.workshop.core.Dates
import pk.workshop.core.Money
import pk.workshop.core.Qty
import pk.workshop.db.Db
import pk.workshop.db.Row
import pk.workshop.db.fail

/** kind: PART (goods, FBR tax), LABOUR or SUBLET (services, provincial tax). */
data class InvLine(
    val kind: String, val partId: Long? = null, val description: String, val qty: Long, val rate: Long, val discount: Long = 0,
    val cost: Long = 0, val technicianId: Long? = null, val taxBp: Int = 0, val tax: Long = 0, val net: Long = 0,
) {
    val isGoods get() = kind == "PART"
    val gross get() = Money.times(rate, qty)
}

data class Invoice(
    val id: Long, val no: String, val kind: String, val date: String, val customerId: Long?, val customer: String, val customerPhone: String,
    val vehicleId: Long?, val vehicle: String, val jobId: Long?, val jobNo: String, val goodsNet: Long, val servicesNet: Long, val discount: Long,
    val goodsTax: Long, val servicesTax: Long, val rounding: Long, val total: Long, val cost: Long, val insurerId: Long?, val insurerShare: Long,
    val status: String, val againstId: Long?, val fbrNo: String, val notes: String, val paid: Long, val odometer: Long,
) {
    val due get() = total - paid
}

data class Payment(val id: Long, val no: String, val date: String, val direction: String, val party: String, val method: String, val amount: Long,
                   val wht: Long, val reference: String, val chequeDate: String, val chequeStatus: String, val invoiceNo: String, val note: String)

object Billing {
    private const val SELECT = """SELECT i.*, coalesce(c.name,'') cname, coalesce(c.phone,'') cphone,
        coalesce(v.reg_no||' '||coalesce(v.make,'')||' '||coalesce(v.model,''),'') vlabel, coalesce(j.job_no,'') jno, coalesce(j.odometer,0) odo,
        coalesce((SELECT sum(amount) FROM payment_allocations a WHERE a.invoice_id=i.id),0) paid
        FROM invoices i LEFT JOIN customers c ON c.id=i.customer_id LEFT JOIN vehicles v ON v.id=i.vehicle_id LEFT JOIN job_cards j ON j.id=i.job_id"""

    private fun map(r: Row) = Invoice(r.long("id"), r.str("inv_no"), r.str("kind"), r.str("date"), r.longOrNull("customer_id"), r.str("cname"),
        r.str("cphone"), r.longOrNull("vehicle_id"), r.str("vlabel").trim(), r.longOrNull("job_id"), r.str("jno"), r.long("goods_net"), r.long("services_net"),
        r.long("discount"), r.long("goods_tax"), r.long("services_tax"), r.long("rounding"), r.long("total"), r.long("cost"), r.longOrNull("insurer_id"),
        r.long("insurer_share"), r.str("status"), r.longOrNull("against_id"), r.str("fbr_no"), r.str("notes"), r.long("paid"), r.long("odo"))

    fun list(from: String, to: String, search: String = "", unpaidOnly: Boolean = false, customerId: Long? = null): List<Invoice> {
        val s = "%${search.trim()}%"
        return Db.query("""$SELECT WHERE i.date BETWEEN ? AND ? AND (i.inv_no LIKE ? OR c.name LIKE ? OR c.phone LIKE ? OR v.reg_no LIKE ?)
            AND (? IS NULL OR i.customer_id=?) ORDER BY i.date DESC, i.id DESC LIMIT 3000""", from, to, s, s, s, s, customerId, customerId).map(::map)
            .let { if (unpaidOnly) it.filter { inv -> inv.kind != "Credit note" && inv.due > 0 } else it }
    }

    fun get(id: Long) = Db.one("$SELECT WHERE i.id=?", id)?.let(::map) ?: fail("Invoice not found")
    fun byNo(no: String) = Db.one("$SELECT WHERE i.inv_no=?", no.trim())?.let(::map)
    fun due(id: Long) = get(id).due

    fun lines(id: Long): List<InvLine> = Db.query("SELECT * FROM invoice_lines WHERE invoice_id=? ORDER BY kind='PART' DESC, id", id).map {
        InvLine(it.str("kind"), it.longOrNull("part_id"), it.str("description"), it.long("qty"), it.long("rate"), it.long("discount"), it.long("cost"),
            it.longOrNull("technician_id"), it.int("tax_bp"), it.long("tax"), it.long("net"))
    }

    data class Totals(val lines: List<InvLine>, val goodsNet: Long, val servicesNet: Long, val discount: Long, val goodsTax: Long, val servicesTax: Long,
                      val rounding: Long, val total: Long, val goodsRate: Tax.Rate?, val servicesRate: Tax.Rate?)

    /**
     * Prices lines: an invoice-level discount is spread over lines by value, then each line is taxed
     * at the rate in force on the invoice date for its kind. The grand total is rounded to whole rupees.
     */
    fun compute(input: List<InvLine>, invoiceDiscount: Long, date: String): Totals {
        val g = Tax.rateFor("GOODS", date); val s = Tax.rateFor("SERVICES", date)
        val baseNets = input.map { it.gross - it.discount }
        val sumNets = baseNets.sum()
        if (invoiceDiscount > sumNets) fail("The discount is larger than the bill.")
        var left = invoiceDiscount
        val priced = input.mapIndexed { i, l ->
            val share = if (sumNets == 0L) 0 else if (i == input.lastIndex) left else
                java.math.BigDecimal.valueOf(invoiceDiscount).multiply(java.math.BigDecimal.valueOf(baseNets[i]))
                    .divide(java.math.BigDecimal.valueOf(sumNets), 0, java.math.RoundingMode.HALF_UP).toLong()
            left -= share
            val disc = l.discount + share
            val net = l.gross - disc
            val bp = if (l.isGoods) g?.rateBp ?: 0 else s?.rateBp ?: 0
            l.copy(discount = disc, net = net, taxBp = bp, tax = Money.pct(net, bp))
        }
        val gNet = priced.filter { it.isGoods }.sumOf { it.net }
        val sNet = priced.filter { !it.isGoods }.sumOf { it.net }
        val gTax = priced.filter { it.isGoods }.sumOf { it.tax }
        val sTax = priced.filter { !it.isGoods }.sumOf { it.tax }
        val raw = gNet + sNet + gTax + sTax
        val total = Money.roundToRupee(raw)
        return Totals(priced, gNet, sNet, priced.sumOf { it.discount }, gTax, sTax, total - raw, total, g, s)
    }

    /**
     * Posts an invoice: numbers it, moves stock for counter sales, and posts
     * Dr Receivable / Cr Sales, Cr Sales tax (FBR), Cr Services tax (province), plus Dr Cost of parts / Cr Stock.
     */
    fun post(kind: String, date: String, customerId: Long, vehicleId: Long?, jobId: Long?, input: List<InvLine>, invoiceDiscount: Long, notes: String,
             insurerId: Long? = null, insurerShare: Long = 0): Long = Db.tx {
        if (input.isEmpty()) fail("Add at least one line.")
        if (input.any { it.qty <= 0 }) fail("Quantities must be above zero.")
        Ledger.checkOpen(date)
        val t0 = compute(input, invoiceDiscount, date)
        // Counter sales take stock straight off the shelf, so their cost is the part's average cost right now.
        // (Job card parts already carry the cost from when they were issued to the job.)
        val t = if (jobId != null) t0 else t0.copy(lines = t0.lines.map { l ->
            if (l.kind == "PART" && l.partId != null) l.copy(cost = Money.times(Inventory.get(l.partId).avgCost, l.qty)) else l })
        if (insurerShare < 0 || insurerShare > t.total) fail("The insurer's share must be between zero and the bill total.")
        if (insurerShare > 0 && insurerId == null) fail("Choose the insurance company.")
        val no = Numbers.next("INV", date)
        val cost = t.lines.filter { it.isGoods }.sumOf { it.cost }
        val id = Db.insert("""INSERT INTO invoices(inv_no,kind,date,customer_id,vehicle_id,job_id,goods_net,services_net,discount,goods_tax,services_tax,rounding,
            total,cost,insurer_id,insurer_share,notes,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", no, kind, date, customerId, vehicleId, jobId,
            t.goodsNet, t.servicesNet, t.discount, t.goodsTax, t.servicesTax, t.rounding, t.total, cost, insurerId, insurerShare, notes, Dates.now())
        for (l in t.lines) {
            Db.insert("""INSERT INTO invoice_lines(invoice_id,kind,part_id,description,qty,rate,discount,tax_bp,tax,net,cost,technician_id)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""", id, l.kind, l.partId, l.description, l.qty, l.rate, l.discount, l.taxBp, l.tax, l.net, l.cost, l.technicianId)
            if (jobId == null && l.partId != null) {
                val p = Inventory.get(l.partId)
                if (p.qty < l.qty) fail("Only ${Qty.fmt(p.qty)} ${p.unit} of ${p.name} in stock.")
                Inventory.move(l.partId, date, "SALE", -l.qty, p.avgCost, "INV", id, no)
            }
        }
        val cust = Customers.get(customerId)
        val ar = Accounts.sys("AR")
        val lines = ArrayList<LedgerLine>()
        lines += LedgerLine(ar, debit = t.total - insurerShare, partyType = "C", partyId = customerId)
        if (insurerShare > 0) lines += LedgerLine(ar, debit = insurerShare, partyType = "C", partyId = insurerId, memo = "Insurer share of $no")
        val parts = t.lines.filter { it.kind == "PART" }.sumOf { it.net }
        val labour = t.lines.filter { it.kind == "LABOUR" }.sumOf { it.net }
        val sublet = t.lines.filter { it.kind == "SUBLET" }.sumOf { it.net }
        lines += LedgerLine(Accounts.sys("SALES_PARTS"), credit = parts)
        lines += LedgerLine(Accounts.sys("SALES_LABOUR"), credit = labour)
        lines += LedgerLine(Accounts.sys("SALES_SUBLET"), credit = sublet)
        lines += LedgerLine(Accounts.sys("GST"), credit = t.goodsTax)
        lines += LedgerLine(Accounts.sys("PST"), credit = t.servicesTax)
        if (t.rounding > 0) lines += LedgerLine(Accounts.sys("ROUNDING"), credit = t.rounding)
        if (t.rounding < 0) lines += LedgerLine(Accounts.sys("ROUNDING"), debit = -t.rounding)
        if (cost > 0) { lines += LedgerLine(Accounts.sys("COGS"), debit = cost); lines += LedgerLine(Accounts.sys("INVENTORY"), credit = cost) }
        val subletCost = t.lines.filter { it.kind == "SUBLET" }.sumOf { it.cost }
        if (subletCost > 0) { lines += LedgerLine(Accounts.sys("SUBLET_COST"), debit = subletCost); lines += LedgerLine(Accounts.sys("AP"), credit = subletCost, memo = "Sublet work on $no") }
        Ledger.post("SALE", date, "Invoice $no — ${cust.name}", lines, "invoice", id)
        // Loyalty points on what the customer pays
        val per100 = Settings.get(Settings.POINTS_PER_100, "0").toLongOrNull() ?: 0
        if (per100 > 0 && cust.type != "Walk-in") Customers.addPoints(customerId, (t.total - insurerShare) / 10000 * per100, id, "Earned on $no")
        if (jobId != null) Db.exec("UPDATE job_cards SET invoice_id=?, status=CASE WHEN status='Delivered' THEN status ELSE 'Ready' END WHERE id=?", id, jobId)
        Fbr.queue(id)
        Audit.log("create", "invoice", id, "$no ${Money.rs(t.total)}")
        id
    }

    /** Builds invoice lines from a job card's work (customer-supplied parts are left out). */
    fun linesFromJob(jobId: Long): List<InvLine> = Jobs.lines(jobId).filter { !it.customerSupplied || it.kind != "PART" }.map {
        InvLine(it.kind, it.partId, it.description, it.qty, it.rate, it.discount, it.cost, it.technicianId)
    }

    fun invoiceJob(jobId: Long, invoiceDiscount: Long, notes: String, insurerId: Long? = null, insurerShare: Long = 0): Long {
        val j = Jobs.get(jobId)
        if (j.invoiceId != null) fail("This job is already billed (${get(j.invoiceId).no}).")
        val ls = linesFromJob(jobId)
        if (ls.isEmpty()) fail("The job card has no billable lines.")
        return post("Service", Dates.today(), j.customerId, j.vehicleId, jobId, ls, invoiceDiscount, notes, insurerId, insurerShare)
    }

    /**
     * Credit note for returned parts or a corrected bill. [returns] maps invoice line index to the quantity credited.
     * Parts come back into stock and every posting of the original is reversed in proportion.
     */
    fun creditNote(invoiceId: Long, returns: Map<Int, Long>, reason: String, date: String = Dates.today()): Long = Db.tx {
        val inv = get(invoiceId)
        if (inv.kind == "Credit note") fail("You can't credit a credit note.")
        if (reason.isBlank()) fail("Enter the reason.")
        val orig = lines(invoiceId)
        val picked = returns.filter { it.value > 0 }.map { (i, q) ->
            val l = orig[i]
            if (q > l.qty) fail("You can return at most ${Qty.fmt(l.qty)} of ${l.description}.")
            fun part(v: Long) = java.math.BigDecimal.valueOf(v).multiply(java.math.BigDecimal.valueOf(q)).divide(java.math.BigDecimal.valueOf(l.qty), 0, java.math.RoundingMode.HALF_UP).toLong()
            l.copy(qty = q, discount = part(l.discount), net = part(l.net), tax = part(l.tax), cost = part(l.cost))
        }
        if (picked.isEmpty()) fail("Choose what to credit.")
        val no = Numbers.next("CN", date)
        val gNet = picked.filter { it.isGoods }.sumOf { it.net }; val sNet = picked.filter { !it.isGoods }.sumOf { it.net }
        val gTax = picked.filter { it.isGoods }.sumOf { it.tax }; val sTax = picked.filter { !it.isGoods }.sumOf { it.tax }
        val raw = gNet + sNet + gTax + sTax
        val total = Money.roundToRupee(raw)
        val cost = picked.filter { it.isGoods }.sumOf { it.cost }
        val id = Db.insert("""INSERT INTO invoices(inv_no,kind,date,customer_id,vehicle_id,job_id,goods_net,services_net,discount,goods_tax,services_tax,rounding,total,cost,
            status,against_id,notes,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", no, "Credit note", date, inv.customerId, inv.vehicleId, inv.jobId,
            -gNet, -sNet, 0, -gTax, -sTax, -(total - raw), -total, -cost, "Posted", invoiceId, reason, Dates.now())
        for (l in picked) {
            Db.insert("""INSERT INTO invoice_lines(invoice_id,kind,part_id,description,qty,rate,discount,tax_bp,tax,net,cost) VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
                id, l.kind, l.partId, l.description, -l.qty, l.rate, -l.discount, l.taxBp, -l.tax, -l.net, -l.cost)
            // Back on the shelf at what it cost when it was sold, matching the cost reversed in the ledger
            if (l.partId != null) Inventory.receive(l.partId, date, l.qty, Inventory.unitCost(l.cost, l.qty), "CN", id, no, kind = "RETURN_IN")
        }
        val ls = arrayListOf(
            LedgerLine(Accounts.sys("AR"), credit = total, partyType = "C", partyId = inv.customerId),
            LedgerLine(Accounts.sys("SALES_PARTS"), debit = picked.filter { it.kind == "PART" }.sumOf { it.net }),
            LedgerLine(Accounts.sys("SALES_LABOUR"), debit = picked.filter { it.kind == "LABOUR" }.sumOf { it.net }),
            LedgerLine(Accounts.sys("SALES_SUBLET"), debit = picked.filter { it.kind == "SUBLET" }.sumOf { it.net }),
            LedgerLine(Accounts.sys("GST"), debit = gTax), LedgerLine(Accounts.sys("PST"), debit = sTax),
        )
        val rounding = total - raw
        if (rounding > 0) ls += LedgerLine(Accounts.sys("ROUNDING"), debit = rounding)
        if (rounding < 0) ls += LedgerLine(Accounts.sys("ROUNDING"), credit = -rounding)
        if (cost > 0) { ls += LedgerLine(Accounts.sys("INVENTORY"), debit = cost); ls += LedgerLine(Accounts.sys("COGS"), credit = cost) }
        Ledger.post("CN", date, "Credit note $no against ${inv.no}: $reason", ls, "invoice", id)
        // The credit settles the original invoice first.
        val settle = minOf(total, inv.due)
        if (settle > 0) {
            val pid = Db.insert("""INSERT INTO payments(pay_no,date,direction,party_type,party_id,invoice_id,method,amount,note) VALUES(?,?,?,?,?,?,?,?,?)""",
                no, date, "IN", "C", inv.customerId, invoiceId, "Credit note", settle, "Applied credit note")
            Db.insert("INSERT INTO payment_allocations(payment_id,invoice_id,amount) VALUES(?,?,?)", pid, invoiceId, settle)
        }
        Audit.log("create", "credit_note", id, "$no against ${inv.no} ${Money.rs(total)}")
        id
    }

    // ---------- payments ----------

    /**
     * Receives money from a customer. [wht] is income tax a company withheld (it reduces what they owe too).
     * Allocates to the given invoice first, then the oldest unpaid ones.
     */
    fun receive(customerId: Long, amount: Long, method: String, reference: String, date: String, invoiceId: Long? = null, wht: Long = 0,
                chequeDate: String? = null, note: String = ""): Long = Db.tx {
        if (amount <= 0 && wht <= 0) fail("Enter an amount.")
        if (method == "Cheque" && reference.isBlank()) fail("Enter the cheque number and bank.")
        val no = Numbers.next("RC", date)
        val cust = Customers.get(customerId)
        val ls = arrayListOf<LedgerLine>()
        if (method == "Loyalty points") ls += LedgerLine(Accounts.sys("DISCOUNTS"), debit = amount, memo = "Points redeemed")
        else if (amount > 0) ls += LedgerLine(Accounts.forMethod(method), debit = amount)
        if (wht > 0) ls += LedgerLine(Accounts.sys("WHT_REC"), debit = wht, memo = "Tax withheld by customer")
        ls += LedgerLine(Accounts.sys("AR"), credit = amount + wht, partyType = "C", partyId = customerId)
        val vid = Ledger.post("RCPT", date, "Receipt $no from ${cust.name} ($method${if (reference.isNotBlank()) " $reference" else ""})", ls, "customer", customerId)
        val pid = Db.insert("""INSERT INTO payments(pay_no,date,direction,party_type,party_id,invoice_id,method,amount,wht,reference,cheque_date,cheque_status,note,voucher_id)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", no, date, "IN", "C", customerId, invoiceId, method, amount, wht, reference, chequeDate,
            if (method == "Cheque") "In hand" else null, note, vid)
        var left = amount + wht
        val open = list("0000-00-00", "9999-12-31", customerId = customerId).filter { it.kind != "Credit note" && it.due > 0 }
            .sortedWith(compareBy({ it.id != invoiceId }, { it.date }))
        for (inv in open) {
            if (left <= 0) break
            val a = minOf(left, inv.due)
            Db.insert("INSERT INTO payment_allocations(payment_id,invoice_id,amount) VALUES(?,?,?)", pid, inv.id, a)
            left -= a
        }
        // Insurer share is owed by the insurer, so allocate its payments to invoices where it is the insurer.
        if (left > 0) {
            val insured = list("0000-00-00", "9999-12-31").filter { it.insurerId == customerId && it.due > 0 }
            for (inv in insured) { if (left <= 0) break; val a = minOf(left, inv.due)
                Db.insert("INSERT INTO payment_allocations(payment_id,invoice_id,amount) VALUES(?,?,?)", pid, inv.id, a); left -= a }
        }
        if (method == "Loyalty points") {
            val pv = Settings.get(Settings.POINT_VALUE, "100").toLongOrNull() ?: 100
            val pts = if (pv == 0L) 0 else amount / pv
            if (pts > cust.points) fail("${cust.name} has only ${cust.points} points.")
            Customers.addPoints(customerId, -pts, invoiceId, "Redeemed on $no")
        }
        if (method == "Cash") CashDrawer.openIfEnabled()
        Audit.log("create", "receipt", pid, "$no ${Money.rs(amount)} $method")
        pid
    }

    fun payments(from: String, to: String, direction: String? = null): List<Payment> = Db.query(
        """SELECT p.*, CASE p.party_type WHEN 'C' THEN (SELECT name FROM customers WHERE id=p.party_id) ELSE (SELECT name FROM suppliers WHERE id=p.party_id) END party,
           coalesce((SELECT inv_no FROM invoices WHERE id=p.invoice_id), (SELECT doc_no FROM purchases WHERE id=p.purchase_id), '') ref_no
           FROM payments p WHERE p.date BETWEEN ? AND ? AND (? IS NULL OR direction=?) ORDER BY p.date DESC, p.id DESC""", from, to, direction, direction).map {
        Payment(it.long("id"), it.str("pay_no"), it.str("date"), it.str("direction"), it.str("party"), it.str("method"), it.long("amount"), it.long("wht"),
            it.str("reference"), it.str("cheque_date"), it.str("cheque_status"), it.str("ref_no"), it.str("note"))
    }

    fun invoicePayments(invoiceId: Long) = Db.query("""SELECT p.pay_no, p.date, p.method, a.amount, p.reference FROM payment_allocations a
        JOIN payments p ON p.id=a.payment_id WHERE a.invoice_id=? ORDER BY p.id""", invoiceId)

    // ---------- cheques ----------

    fun cheques(status: String? = "In hand") = Db.query("""SELECT p.*, (SELECT name FROM customers WHERE id=p.party_id) party FROM payments p
        WHERE method='Cheque' AND direction='IN' AND (? IS NULL OR cheque_status=?) ORDER BY cheque_date""", status, status)

    fun chequeCleared(paymentId: Long, date: String = Dates.today()) = Db.tx {
        val p = Db.one("SELECT * FROM payments WHERE id=?", paymentId) ?: fail("Cheque not found")
        if (p.str("cheque_status") != "In hand") fail("This cheque is already ${p.str("cheque_status").lowercase()}.")
        Ledger.post("JV", date, "Cheque ${p.str("reference")} deposited and cleared",
            listOf(LedgerLine(Accounts.sys("BANK"), debit = p.long("amount")), LedgerLine(Accounts.sys("CHEQUES"), credit = p.long("amount"))), "payment", paymentId)
        Db.exec("UPDATE payments SET cheque_status='Cleared' WHERE id=?", paymentId)
    }

    /** A bounced cheque puts the amount back on the customer's account. */
    fun chequeBounced(paymentId: Long, date: String = Dates.today()) = Db.tx {
        val p = Db.one("SELECT * FROM payments WHERE id=?", paymentId) ?: fail("Cheque not found")
        if (p.str("cheque_status") != "In hand") fail("This cheque is already ${p.str("cheque_status").lowercase()}.")
        Ledger.post("JV", date, "Cheque ${p.str("reference")} bounced",
            listOf(LedgerLine(Accounts.sys("AR"), debit = p.long("amount"), partyType = "C", partyId = p.long("party_id")),
                LedgerLine(Accounts.sys("CHEQUES"), credit = p.long("amount"))), "payment", paymentId)
        Db.exec("DELETE FROM payment_allocations WHERE payment_id=?", paymentId)
        Db.exec("UPDATE payments SET cheque_status='Bounced' WHERE id=?", paymentId)
    }

    // ---------- receivables ----------

    data class Ageing(val customerId: Long, val name: String, val phone: String, val current: Long, val d30: Long, val d60: Long, val d90: Long, val over90: Long) {
        val total get() = current + d30 + d60 + d90 + over90
    }

    fun ageing(asOf: String = Dates.today()): List<Ageing> {
        val open = list("0000-00-00", asOf, unpaidOnly = true)
        return open.groupBy { it.customerId ?: 0 }.map { (cid, invs) ->
            var cur = 0L; var a = 0L; var b = 0L; var c = 0L; var d = 0L
            val terms = invs.first().customerId?.let { Customers.get(it).termsDays } ?: 0
            for (i in invs) {
                val days = Dates.daysBetween(i.date, asOf) - terms
                when { days <= 0 -> cur += i.due; days <= 30 -> a += i.due; days <= 60 -> b += i.due; days <= 90 -> c += i.due; else -> d += i.due }
            }
            Ageing(cid, invs.first().customer, invs.first().customerPhone, cur, a, b, c, d)
        }.sortedByDescending { it.total }
    }
}

/** FBR digital invoicing hook. Invoices queue here; a licensed integrator's API is configured in Settings. */
object Fbr {
    fun enabled() = Settings.bool("fbr.enabled")
    fun queue(invoiceId: Long) {
        if (!enabled()) return
        Db.exec("UPDATE invoices SET fbr_no='PENDING' WHERE id=?", invoiceId)
    }
    fun pending() = Db.query("SELECT id, inv_no, date, total FROM invoices WHERE fbr_no='PENDING' ORDER BY id")
    /** Records the FBR invoice number returned by the integrator (entered or received via the integrator's API). */
    fun setNumber(invoiceId: Long, fbrNo: String) {
        Db.exec("UPDATE invoices SET fbr_no=? WHERE id=?", fbrNo.trim(), invoiceId)
        Audit.log("fbr", "invoice", invoiceId, fbrNo)
    }
}

/** Opens the cash drawer through the receipt printer when configured. */
object CashDrawer {
    var opener: (() -> Unit)? = null
    fun openIfEnabled() { if (Settings.bool(Settings.DRAWER)) try { opener?.invoke() } catch (_: Exception) {} }
}
