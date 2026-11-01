package pk.workshop.data

import pk.workshop.core.Dates
import pk.workshop.core.Money
import pk.workshop.core.Qty
import pk.workshop.db.Db
import pk.workshop.db.Row
import pk.workshop.db.fail

data class Part(
    val id: Long = 0, val partNo: String = "", val name: String = "", val brand: String = "", val category: String = "", val grade: String = "Genuine",
    val unit: String = "pc", val location: String = "", val fits: String = "", val avgCost: Long = 0, val price: Long = 0, val qty: Long = 0,
    val reorderLevel: Long = 0, val lastCost: Long = 0, val active: Boolean = true, val barcodes: List<String> = emptyList(),
) {
    val label get() = listOf(partNo, name, brand).filter { it.isNotBlank() }.joinToString(" · ")
    val low get() = reorderLevel > 0 && qty <= reorderLevel
}

data class StockMove(val id: Long, val date: String, val kind: String, val qty: Long, val unitCost: Long, val ref: String, val note: String)

object Inventory {
    private fun map(r: Row) = Part(r.long("id"), r.str("part_no"), r.str("name"), r.str("brand"), r.str("category"), r.str("grade"), r.str("unit"),
        r.str("location"), r.str("fits"), r.long("avg_cost"), r.long("price"), r.long("qty"), r.long("reorder_level"), r.long("last_cost"), r.bool("active"),
        (r.strOrNull("codes") ?: "").split('\u0001').filter { it.isNotEmpty() })

    private const val SELECT = "SELECT p.*, (SELECT group_concat(code, char(1)) FROM part_barcodes b WHERE b.part_id=p.id) codes FROM parts p"

    fun list(search: String = "", onlyLow: Boolean = false, includeInactive: Boolean = false): List<Part> {
        val s = "%${search.trim()}%"
        return Db.query("""$SELECT WHERE (p.name LIKE ? OR p.part_no LIKE ? OR p.brand LIKE ? OR p.category LIKE ? OR p.fits LIKE ? OR p.location LIKE ?
            OR EXISTS(SELECT 1 FROM part_barcodes b WHERE b.part_id=p.id AND b.code LIKE ?)) AND (?=1 OR p.active=1)
            ORDER BY p.name LIMIT 3000""", s, s, s, s, s, s, s, includeInactive).map(::map)
            .let { if (onlyLow) it.filter { p -> p.low } else it }
    }

    fun get(id: Long): Part = Db.one("$SELECT WHERE p.id=?", id)?.let(::map) ?: fail("Part not found")

    fun byBarcode(code: String): Part? {
        val c = code.trim()
        if (c.isEmpty()) return null
        val id = Db.scalarStr("SELECT part_id FROM part_barcodes WHERE code=?", c)?.toLong()
            ?: Db.scalarStr("SELECT id FROM parts WHERE part_no=? AND active=1", c)?.toLong()
        return id?.let { get(it) }
    }

    fun categories(): List<String> = Db.query("SELECT DISTINCT category FROM parts WHERE category<>'' ORDER BY 1").map { it.str("category") }
    fun brands(): List<String> = Db.query("SELECT DISTINCT brand FROM parts WHERE brand<>'' ORDER BY 1").map { it.str("brand") }

    fun save(p: Part): Long = Db.tx {
        if (p.name.isBlank()) fail("Enter the part name.")
        if (p.price < 0 || p.avgCost < 0) fail("Prices cannot be negative.")
        for (c in p.barcodes) {
            val owner = Db.scalarStr("SELECT part_id FROM part_barcodes WHERE code=?", c)?.toLong()
            if (owner != null && owner != p.id) fail("Barcode $c already belongs to ${get(owner).name}.")
        }
        val id = if (p.id == 0L) {
            Db.insert("""INSERT INTO parts(part_no,name,brand,category,grade,unit,location,fits,price,reorder_level,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)""", p.partNo.trim(), p.name.trim(), p.brand.trim(), p.category.trim(), p.grade, p.unit,
                p.location.trim(), p.fits.trim(), p.price, p.reorderLevel, Dates.now()).also { Audit.log("create", "part", it, p.name) }
        } else {
            val old = get(p.id)
            Db.exec("""UPDATE parts SET part_no=?,name=?,brand=?,category=?,grade=?,unit=?,location=?,fits=?,price=?,reorder_level=?,active=? WHERE id=?""",
                p.partNo.trim(), p.name.trim(), p.brand.trim(), p.category.trim(), p.grade, p.unit, p.location.trim(), p.fits.trim(), p.price,
                p.reorderLevel, p.active, p.id)
            if (old.price != p.price) Audit.log("price", "part", p.id, "${p.name}: ${Money.rs(old.price)} → ${Money.rs(p.price)}")
            else Audit.log("update", "part", p.id, p.name)
            p.id
        }
        Db.exec("DELETE FROM part_barcodes WHERE part_id=?", id)
        val codes = p.barcodes.map { it.trim() }.filter { it.isNotEmpty() }.distinct().ifEmpty { listOf(internalCode(id)) }
        codes.forEach { Db.exec("INSERT INTO part_barcodes(code,part_id) VALUES(?,?)", it, id) }
        id
    }

    /** Internal Code 128 value for parts without a maker's barcode. */
    fun internalCode(id: Long) = "P" + id.toString().padStart(6, '0')

    /**
     * Records a stock movement and updates the on-hand quantity.
     * Positive qty adds stock. Does not post to the ledger (callers post the money side).
     */
    fun move(partId: Long, date: String, kind: String, qty: Long, unitCost: Long, refType: String?, refId: Long?, note: String = "") {
        Db.insert("INSERT INTO stock_moves(part_id,date,kind,qty,unit_cost,ref_type,ref_id,note) VALUES(?,?,?,?,?,?,?,?)",
            partId, date, kind, qty, unitCost, refType, refId, note)
        Db.exec("UPDATE parts SET qty=qty+? WHERE id=?", qty, partId)
    }

    /** Incoming stock at a cost: updates weighted-average cost. */
    fun receive(partId: Long, date: String, qty: Long, unitCost: Long, refType: String, refId: Long, note: String = "", kind: String = "IN") {
        val p = get(partId)
        val oldQty = maxOf(p.qty, 0)
        val newQty = oldQty + qty
        val avg = if (newQty <= 0) unitCost else
            ((java.math.BigDecimal.valueOf(oldQty).multiply(java.math.BigDecimal.valueOf(p.avgCost)) +
              java.math.BigDecimal.valueOf(qty).multiply(java.math.BigDecimal.valueOf(unitCost)))
                .divide(java.math.BigDecimal.valueOf(newQty), 0, java.math.RoundingMode.HALF_UP)).toLong()
        Db.exec("UPDATE parts SET avg_cost=?, last_cost=? WHERE id=?", avg, unitCost, partId)
        move(partId, date, kind, qty, unitCost, refType, refId, note)
    }

    /**
     * Outgoing stock valued at a set cost rather than the average (e.g. a return to the supplier at the bill price).
     * Re-averages what stays on the shelf so the stock value moves by exactly qty × unitCost, matching the ledger.
     */
    fun removeAtCost(partId: Long, date: String, kind: String, qty: Long, unitCost: Long, refType: String, refId: Long, note: String = "") {
        val p = get(partId)
        val left = p.qty - qty
        if (left > 0) {
            val avg = (java.math.BigDecimal.valueOf(p.qty).multiply(java.math.BigDecimal.valueOf(p.avgCost)) -
                java.math.BigDecimal.valueOf(qty).multiply(java.math.BigDecimal.valueOf(unitCost)))
                .divide(java.math.BigDecimal.valueOf(left), 0, java.math.RoundingMode.HALF_UP).toLong().coerceAtLeast(0)
            Db.exec("UPDATE parts SET avg_cost=? WHERE id=?", avg, partId)
        }
        move(partId, date, kind, -qty, unitCost, refType, refId, note)
    }

    /** Cost per unit (paisa) of [qty] thousandths that cost [total] paisa in all. */
    fun unitCost(total: Long, qty: Long): Long = if (qty == 0L) 0 else
        java.math.BigDecimal.valueOf(total).multiply(java.math.BigDecimal(1000)).divide(java.math.BigDecimal.valueOf(qty), 0, java.math.RoundingMode.HALF_UP).toLong()

    fun moves(partId: Long): List<StockMove> = Db.query("SELECT * FROM stock_moves WHERE part_id=? ORDER BY date DESC, id DESC LIMIT 500", partId).map {
        StockMove(it.long("id"), it.str("date"), it.str("kind"), it.long("qty"), it.long("unit_cost"),
            listOfNotNull(it.strOrNull("ref_type"), it.longOrNull("ref_id")?.toString()).joinToString(" #"), it.str("note"))
    }

    /** Opening stock at go-live: adds stock and posts it against opening balances. */
    fun openingStock(partId: Long, qty: Long, unitCost: Long, date: String = Dates.today()) = Db.tx {
        if (qty <= 0) fail("Enter the quantity.")
        val value = Money.times(unitCost, qty)
        val vid = if (value > 0) Ledger.post("OPEN", date, "Opening stock: ${get(partId).name}",
            listOf(LedgerLine(Accounts.sys("INVENTORY"), debit = value), LedgerLine(Accounts.sys("OPENING"), credit = value)), "part", partId) else null
        // The movement points at its voucher, so deleting one removes the other
        receive(partId, date, qty, unitCost, "OPENING", vid ?: 0, "Opening stock")
    }

    /** Stock adjustment for damage, loss or found stock. Posts the value difference. */
    fun adjust(partId: Long, delta: Long, reason: String, date: String = Dates.today()) = Db.tx {
        if (delta == 0L) fail("Enter a quantity to add or remove.")
        if (reason.isBlank()) fail("Enter a reason for the adjustment.")
        val p = get(partId)
        val value = Money.times(p.avgCost, kotlin.math.abs(delta))
        val vid = if (value > 0) {
            val inv = Accounts.sys("INVENTORY"); val adj = Accounts.sys("STOCK_ADJ")
            val lines = if (delta < 0) listOf(LedgerLine(adj, debit = value), LedgerLine(inv, credit = value))
                        else listOf(LedgerLine(inv, debit = value), LedgerLine(adj, credit = value))
            Ledger.post("STK", date, "Stock adjustment: ${p.name} (${Qty.fmt(delta)}) — $reason", lines, "part", partId)
        } else null
        move(partId, date, "ADJUST", delta, p.avgCost, "ADJUST", vid, reason)
        Audit.log("adjust", "part", partId, "${p.name} ${Qty.fmt(delta)} $reason")
    }

    /** Raises (or lowers) sale prices by a percentage for a brand and/or category. Returns rows changed. */
    fun bulkPrice(brand: String?, category: String?, percentBp: Int, roundTo: Long): Int = Db.tx {
        val parts = list(includeInactive = false).filter { (brand.isNullOrBlank() || it.brand == brand) && (category.isNullOrBlank() || it.category == category) }
        for (p in parts) {
            var np = p.price + Money.pct(p.price, percentBp)
            if (roundTo > 0) np = ((np + roundTo / 2) / roundTo) * roundTo
            Db.exec("UPDATE parts SET price=? WHERE id=?", np, p.id)
        }
        Audit.log("bulk_price", "part", null, "brand=$brand category=$category ${percentBp / 100.0}% on ${parts.size} parts")
        parts.size
    }

    fun stockValue(): Long = Db.query("SELECT qty, avg_cost FROM parts WHERE qty>0").sumOf { Money.times(it.long("avg_cost"), it.long("qty")) }

    // ---------- stock counts ----------

    data class CountLine(val id: Long, val partId: Long, val name: String, val location: String, val unit: String, val system: Long, val counted: Long)

    fun startCount(note: String, location: String?): Long = Db.tx {
        val id = Db.insert("INSERT INTO stock_counts(date,note) VALUES(?,?)", Dates.today(), note)
        val parts = list().filter { location.isNullOrBlank() || it.location.startsWith(location) }
        parts.forEach { Db.insert("INSERT INTO stock_count_lines(count_id,part_id,system_qty,counted_qty) VALUES(?,?,?,?)", id, it.id, it.qty, it.qty) }
        id
    }

    fun counts() = Db.query("SELECT c.*, (SELECT count(*) FROM stock_count_lines WHERE count_id=c.id) n FROM stock_counts c ORDER BY id DESC")

    fun countLines(countId: Long): List<CountLine> = Db.query(
        """SELECT l.*, p.name, p.location, p.unit FROM stock_count_lines l JOIN parts p ON p.id=l.part_id WHERE count_id=? ORDER BY p.location, p.name""", countId).map {
        CountLine(it.long("id"), it.long("part_id"), it.str("name"), it.str("location"), it.str("unit"), it.long("system_qty"), it.long("counted_qty"))
    }

    fun setCounted(lineId: Long, qty: Long) { Db.exec("UPDATE stock_count_lines SET counted_qty=? WHERE id=?", qty, lineId) }

    /** Scanning a code during a count adds one unit to that part's counted quantity. */
    fun scanCount(countId: Long, code: String): String {
        val p = byBarcode(code) ?: return "Unknown code $code"
        val n = Db.exec("UPDATE stock_count_lines SET counted_qty=counted_qty+1000 WHERE count_id=? AND part_id=?", countId, p.id)
        if (n == 0) {
            Db.insert("INSERT INTO stock_count_lines(count_id,part_id,system_qty,counted_qty) VALUES(?,?,?,?)", countId, p.id, p.qty, 1000)
        }
        return p.name
    }

    fun approveCount(countId: Long) = Db.tx {
        val st = Db.scalarStr("SELECT status FROM stock_counts WHERE id=?", countId)
        if (st != "Open") fail("This count is already approved.")
        for (l in countLines(countId)) {
            val now = get(l.partId).qty
            val delta = l.counted - now
            if (delta != 0L) adjust(l.partId, delta, "Stock count #$countId")
        }
        Db.exec("UPDATE stock_counts SET status='Approved' WHERE id=?", countId)
    }
}
