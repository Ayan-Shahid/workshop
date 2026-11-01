package pk.workshop.data

import pk.workshop.core.Dates
import pk.workshop.core.Money
import pk.workshop.db.Db
import pk.workshop.db.Row
import pk.workshop.db.fail

data class Supplier(val id: Long = 0, val name: String = "", val phone: String = "", val address: String = "", val ntn: String = "", val notes: String = "", val active: Boolean = true)

data class DocLine(val partId: Long, val qty: Long, val cost: Long)

data class Purchase(val id: Long, val docNo: String, val kind: String, val supplierId: Long, val supplier: String, val billNo: String, val date: String,
                    val landed: Long, val total: Long, val notes: String, val paid: Long)

object Suppliers {
    private fun map(r: Row) = Supplier(r.long("id"), r.str("name"), r.str("phone"), r.str("address"), r.str("ntn"), r.str("notes"), r.bool("active"))
    fun list(search: String = ""): List<Supplier> = Db.query("SELECT * FROM suppliers WHERE (name LIKE ? OR phone LIKE ?) ORDER BY active DESC, name",
        "%$search%", "%$search%").map(::map)
    fun get(id: Long) = Db.one("SELECT * FROM suppliers WHERE id=?", id)?.let(::map) ?: fail("Supplier not found")
    fun save(s: Supplier): Long {
        if (s.name.isBlank()) fail("Enter the supplier name.")
        return if (s.id == 0L) Db.insert("INSERT INTO suppliers(name,phone,address,ntn,notes) VALUES(?,?,?,?,?)", s.name.trim(), s.phone, s.address, s.ntn, s.notes)
            .also { Audit.log("create", "supplier", it, s.name) }
        else { Db.exec("UPDATE suppliers SET name=?,phone=?,address=?,ntn=?,notes=?,active=? WHERE id=?", s.name.trim(), s.phone, s.address, s.ntn, s.notes, s.active, s.id); s.id }
    }
    fun balance(id: Long) = Ledger.supplierBalance(id)

    /** Opening balance owed to a supplier at go-live. */
    fun openingBalance(id: Long, amount: Long, date: String = Dates.today()) {
        if (amount <= 0) fail("Enter the amount owed.")
        Ledger.post("OPEN", date, "Opening balance: ${get(id).name}",
            listOf(LedgerLine(Accounts.sys("OPENING"), debit = amount), LedgerLine(Accounts.sys("AP"), credit = amount, partyType = "S", partyId = id)), "supplier", id)
    }
}

object Purchasing {
    private fun map(r: Row) = Purchase(r.long("id"), r.str("doc_no"), r.str("kind"), r.long("supplier_id"), r.str("sname"), r.str("bill_no"), r.str("date"),
        r.long("landed"), r.long("total"), r.str("notes"), r.long("paid"))

    fun list(from: String, to: String, supplierId: Long? = null): List<Purchase> = Db.query(
        """SELECT p.*, s.name sname, coalesce((SELECT sum(amount) FROM payment_allocations a WHERE a.purchase_id=p.id),0) paid
           FROM purchases p JOIN suppliers s ON s.id=p.supplier_id WHERE p.date BETWEEN ? AND ? AND (? IS NULL OR p.supplier_id=?) ORDER BY p.date DESC, p.id DESC""",
        from, to, supplierId, supplierId).map(::map)

    fun get(id: Long) = Db.one("""SELECT p.*, s.name sname, coalesce((SELECT sum(amount) FROM payment_allocations a WHERE a.purchase_id=p.id),0) paid
           FROM purchases p JOIN suppliers s ON s.id=p.supplier_id WHERE p.id=?""", id)?.let(::map) ?: fail("Purchase not found")

    fun lines(id: Long) = Db.query("SELECT l.*, p.name, p.unit FROM purchase_lines l JOIN parts p ON p.id=l.part_id WHERE purchase_id=?", id)

    /**
     * Posts a supplier bill: stock in at cost (landed costs such as freight spread by value),
     * Dr Stock / Cr Supplier. Optionally records an immediate payment.
     */
    fun postBill(supplierId: Long, billNo: String, date: String, lines: List<DocLine>, landed: Long, notes: String,
                 payNow: Long = 0, payMethod: String = "Cash", no: String? = null, keepId: Long? = null): Long = Db.tx {
        if (lines.isEmpty()) fail("Add at least one part.")
        if (lines.any { it.qty <= 0 || it.cost < 0 }) fail("Quantities must be above zero and costs not negative.")
        val goods = lines.sumOf { Money.times(it.cost, it.qty) }
        val total = goods + landed
        val docNo = no ?: Numbers.next("PB", date)
        val id = Db.insert("INSERT INTO purchases(id,doc_no,kind,supplier_id,bill_no,date,landed,total,notes) VALUES(?,?,?,?,?,?,?,?,?)",
            keepId, docNo, "Bill", supplierId, billNo, date, landed, total, notes)
        var landedLeft = landed
        lines.forEachIndexed { i, l ->
            val value = Money.times(l.cost, l.qty)
            val share = if (i == lines.lastIndex) landedLeft else if (goods == 0L) 0 else
                java.math.BigDecimal.valueOf(landed).multiply(java.math.BigDecimal.valueOf(value)).divide(java.math.BigDecimal.valueOf(goods), 0, java.math.RoundingMode.HALF_UP).toLong()
            landedLeft -= share
            val unitLanded = if (l.qty == 0L) l.cost else
                java.math.BigDecimal.valueOf(value + share).multiply(java.math.BigDecimal(1000)).divide(java.math.BigDecimal.valueOf(l.qty), 0, java.math.RoundingMode.HALF_UP).toLong()
            Db.insert("INSERT INTO purchase_lines(purchase_id,part_id,qty,cost) VALUES(?,?,?,?)", id, l.partId, l.qty, l.cost)
            Inventory.receive(l.partId, date, l.qty, unitLanded, "PB", id, docNo)
        }
        val sname = Suppliers.get(supplierId).name
        Ledger.post("PUR", date, "Purchase $docNo from $sname" + if (billNo.isNotBlank()) " (bill $billNo)" else "",
            listOf(LedgerLine(Accounts.sys("INVENTORY"), debit = total), LedgerLine(Accounts.sys("AP"), credit = total, partyType = "S", partyId = supplierId)),
            "purchase", id)
        if (payNow > 0) paySupplier(supplierId, payNow, payMethod, "", date, id)
        Audit.log("create", "purchase", id, "$docNo ${Money.rs(total)}")
        id
    }

    /** Returns parts to a supplier: stock out, Dr Supplier / Cr Stock. */
    fun postReturn(supplierId: Long, date: String, lines: List<DocLine>, notes: String, no: String? = null, keepId: Long? = null): Long = Db.tx {
        if (lines.isEmpty()) fail("Add at least one part.")
        for (l in lines) { val p = Inventory.get(l.partId); if (p.qty < l.qty) fail("Only ${pk.workshop.core.Qty.fmt(p.qty)} of ${p.name} in stock.") }
        val total = lines.sumOf { Money.times(it.cost, it.qty) }
        val docNo = no ?: Numbers.next("PR", date)
        val id = Db.insert("INSERT INTO purchases(id,doc_no,kind,supplier_id,bill_no,date,landed,total,notes) VALUES(?,?,?,?,?,?,?,?,?)",
            keepId, docNo, "Return", supplierId, "", date, 0, -total, notes)
        lines.forEach {
            Db.insert("INSERT INTO purchase_lines(purchase_id,part_id,qty,cost) VALUES(?,?,?,?)", id, it.partId, -it.qty, it.cost)
            Inventory.removeAtCost(it.partId, date, "RETURN_OUT", it.qty, it.cost, "PR", id, docNo)
        }
        Ledger.post("PRET", date, "Return $docNo to ${Suppliers.get(supplierId).name}",
            listOf(LedgerLine(Accounts.sys("AP"), debit = total, partyType = "S", partyId = supplierId), LedgerLine(Accounts.sys("INVENTORY"), credit = total)),
            "purchase", id)
        id
    }

    fun paySupplier(supplierId: Long, amount: Long, method: String, reference: String, date: String, purchaseId: Long? = null, note: String = "", no: String? = null, keepId: Long? = null): Long = Db.tx {
        if (amount <= 0) fail("Enter an amount.")
        @Suppress("NAME_SHADOWING") val no = no ?: Numbers.next("SP", date)
        val vid = Ledger.post("PAY", date, "Payment $no to ${Suppliers.get(supplierId).name} ($method${if (reference.isNotBlank()) " $reference" else ""})",
            listOf(LedgerLine(Accounts.sys("AP"), debit = amount, partyType = "S", partyId = supplierId), LedgerLine(Accounts.forMethod(method), credit = amount)),
            "supplier_payment", null)
        val pid = Db.insert("""INSERT INTO payments(id,pay_no,date,direction,party_type,party_id,purchase_id,method,amount,reference,note,voucher_id)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""", keepId, no, date, "OUT", "S", supplierId, purchaseId, method, amount, reference, note, vid)
        allocateToBills(pid, supplierId, amount, purchaseId)
        pid
    }

    private fun allocateToBills(paymentId: Long, supplierId: Long, amount: Long, purchaseId: Long?) {
        var left = amount
        val bills = list("0000-00-00", "9999-12-31", supplierId).filter { it.kind == "Bill" && it.total - it.paid > 0 }
            .sortedWith(compareBy({ it.id != purchaseId }, { it.date }))
        for (b in bills) {
            if (left <= 0) break
            val a = minOf(left, b.total - b.paid)
            Db.insert("INSERT INTO payment_allocations(payment_id,purchase_id,amount) VALUES(?,?,?)", paymentId, b.id, a)
            left -= a
        }
    }

    // ---------- purchase orders ----------

    fun orders() = Db.query("""SELECT o.*, s.name sname, (SELECT sum(qty*cost/1000) FROM po_lines WHERE po_id=o.id) total
        FROM purchase_orders o JOIN suppliers s ON s.id=o.supplier_id ORDER BY o.id DESC""")

    fun orderLines(poId: Long) = Db.query("SELECT l.*, p.name, p.unit FROM po_lines l JOIN parts p ON p.id=l.part_id WHERE po_id=?", poId)

    fun createOrder(supplierId: Long, date: String, lines: List<DocLine>, notes: String): Long = Db.tx {
        if (lines.isEmpty()) fail("Add at least one part.")
        val id = Db.insert("INSERT INTO purchase_orders(po_no,supplier_id,date,notes) VALUES(?,?,?,?)", Numbers.next("PO", date), supplierId, date, notes)
        lines.forEach { Db.insert("INSERT INTO po_lines(po_id,part_id,qty,cost) VALUES(?,?,?,?)", id, it.partId, it.qty, it.cost) }
        id
    }

    /** Turns an open order into a posted bill once the goods arrive. */
    fun receiveOrder(poId: Long, billNo: String, date: String, landed: Long): Long = Db.tx {
        val o = Db.one("SELECT * FROM purchase_orders WHERE id=?", poId) ?: fail("Order not found")
        if (o.str("status") != "Open") fail("This order is already ${o.str("status").lowercase()}.")
        val lines = orderLines(poId).map { DocLine(it.long("part_id"), it.long("qty"), it.long("cost")) }
        val bill = postBill(o.long("supplier_id"), billNo, date, lines, landed, "From ${o.str("po_no")}")
        Db.exec("UPDATE purchase_orders SET status='Received', bill_id=? WHERE id=?", bill, poId)
        bill
    }

    fun cancelOrder(poId: Long) { Db.exec("UPDATE purchase_orders SET status='Cancelled' WHERE id=? AND status='Open'", poId) }

    /** Reorder suggestions: parts at or below reorder level, quantity to bring them to twice the level, or last 90 days' sales. */
    fun reorderSuggestions(): List<Pair<Part, Long>> {
        val since = Dates.plusDays(Dates.today(), -90)
        return Inventory.list().filter { it.low }.map { p ->
            val sold = -Db.scalarLong("SELECT coalesce(sum(qty),0) FROM stock_moves WHERE part_id=? AND qty<0 AND kind IN ('SALE','ISSUE') AND date>=?", p.id, since)
            p to maxOf(p.reorderLevel * 2 - p.qty, sold - p.qty, 1000)
        }
    }

    // ---------- warranty claims ----------

    fun claims() = Db.query("""SELECT w.*, p.name pname, s.name sname FROM warranty_claims w JOIN parts p ON p.id=w.part_id
        JOIN suppliers s ON s.id=w.supplier_id ORDER BY w.id DESC""")

    fun createClaim(partId: Long, supplierId: Long, qty: Long, reason: String, date: String = Dates.today()): Long = Db.tx {
        val id = Db.insert("INSERT INTO warranty_claims(date,part_id,supplier_id,qty,reason) VALUES(?,?,?,?,?)", date, partId, supplierId, qty, reason)
        Audit.log("create", "warranty_claim", id, reason); id
    }

    /** Replaced: supplier sends a new part (stock back in at zero cost to us). Credited: supplier reduces what we owe. Rejected: nothing. */
    fun resolveClaim(id: Long, outcome: String, creditAmount: Long = 0) = Db.tx {
        val c = Db.one("SELECT * FROM warranty_claims WHERE id=?", id) ?: fail("Claim not found")
        if (c.str("status") != "Sent") fail("This claim is already closed.")
        when (outcome) {
            // Free replacement: more units for the same money, so the average cost comes down
            "Replaced" -> Inventory.receive(c.long("part_id"), Dates.today(), c.long("qty"), 0, "WC", id, "Warranty replacement", kind = "WARRANTY_IN")
            "Credited" -> {
                if (creditAmount <= 0) fail("Enter the credit amount.")
                Ledger.post("JV", Dates.today(), "Warranty credit from supplier, claim #$id",
                    listOf(LedgerLine(Accounts.sys("AP"), debit = creditAmount, partyType = "S", partyId = c.long("supplier_id")),
                        LedgerLine(Accounts.sys("STOCK_ADJ"), credit = creditAmount)), "warranty_claim", id)
            }
        }
        Db.exec("UPDATE warranty_claims SET status=?, resolved_date=? WHERE id=?", outcome, Dates.today(), id)
    }
}
