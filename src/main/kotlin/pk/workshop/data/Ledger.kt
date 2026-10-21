package pk.workshop.data

import pk.workshop.core.Dates
import pk.workshop.core.Money
import pk.workshop.db.Db
import pk.workshop.db.Row
import pk.workshop.db.fail

data class Account(val id: Long, val code: String, val name: String, val type: String, val parentId: Long?, val sys: String?, val isGroup: Boolean, val active: Boolean) {
    val label get() = "$code  $name"
    /** Debit-normal accounts show balance as debit − credit. */
    val debitNormal get() = type == "ASSET" || type == "EXPENSE"
}

data class LedgerLine(val accountId: Long, val debit: Long = 0, val credit: Long = 0, val partyType: String? = null, val partyId: Long? = null, val memo: String? = null)

data class Voucher(val id: Long, val no: String, val type: String, val date: String, val narration: String, val refType: String?, val refId: Long?,
                   val reversed: Boolean, val reversalOf: Long?, val total: Long, val attachment: String?)

data class VoucherLineView(val id: Long, val account: String, val accountId: Long, val debit: Long, val credit: Long, val party: String, val memo: String)

object Accounts {
    private fun map(r: Row) = Account(r.long("id"), r.str("code"), r.str("name"), r.str("atype"), r.longOrNull("parent_id"), r.strOrNull("sys"), r.bool("is_group"), r.bool("active"))

    fun all(): List<Account> = Db.query("SELECT * FROM accounts ORDER BY code").map(::map)
    fun postable(): List<Account> = all().filter { !it.isGroup && it.active }
    fun get(id: Long): Account = Db.one("SELECT * FROM accounts WHERE id=?", id)?.let(::map) ?: fail("Account not found")
    fun sys(key: String): Long = Db.scalarStr("SELECT id FROM accounts WHERE sys=?", key)?.toLong() ?: fail("System account $key is missing")
    fun byType(type: String) = postable().filter { it.type == type }

    fun save(id: Long?, code: String, name: String, type: String, parentId: Long?, isGroup: Boolean): Long {
        if (code.isBlank() || name.isBlank()) fail("Enter a code and a name.")
        val dup = Db.scalarLong("SELECT count(*) FROM accounts WHERE code=? AND id<>?", code.trim(), id ?: -1)
        if (dup > 0) fail("Account code $code is already used.")
        return if (id == null) {
            Db.insert("INSERT INTO accounts(code,name,atype,parent_id,is_group) VALUES(?,?,?,?,?)", code.trim(), name.trim(), type, parentId, isGroup)
                .also { Audit.log("create", "account", it, "$code $name") }
        } else {
            val a = get(id)
            if (a.sys != null && a.type != type) fail("The type of a built-in account cannot change.")
            Db.exec("UPDATE accounts SET code=?, name=?, atype=?, parent_id=?, is_group=? WHERE id=?", code.trim(), name.trim(), type, parentId, isGroup, id)
            Audit.log("update", "account", id, "$code $name"); id
        }
    }

    fun setActive(id: Long, active: Boolean) {
        val a = get(id)
        if (a.sys != null && !active) fail("Built-in accounts cannot be deactivated.")
        Db.exec("UPDATE accounts SET active=? WHERE id=?", active, id)
    }

    /** Cash-like accounts a payment can be made from or into. */
    fun forMethod(method: String): Long = when (method) {
        "Cash" -> sys("CASH")
        "JazzCash", "Easypaisa" -> sys("WALLET")
        "Cheque" -> sys("CHEQUES")
        else -> sys("BANK")
    }

    fun cashAndBank(): List<Account> {
        val parent = Db.scalarStr("SELECT id FROM accounts WHERE code='1100'")?.toLong()
        return postable().filter { it.parentId == parent || it.sys in setOf("CASH", "BANK", "WALLET", "CHEQUES") }
    }
}

object Ledger {
    val manualTypes = listOf("CPV", "CRV", "BPV", "BRV", "JV")
    val typeNames = mapOf("CPV" to "Cash payment", "CRV" to "Cash receipt", "BPV" to "Bank payment", "BRV" to "Bank receipt", "JV" to "Journal",
        "SALE" to "Sale", "CN" to "Credit note", "RCPT" to "Receipt", "PUR" to "Purchase", "PRET" to "Purchase return", "PAY" to "Supplier payment",
        "STK" to "Stock adjustment", "EXP" to "Expense", "SAL" to "Payroll", "DEP" to "Depreciation", "OPEN" to "Opening balances",
        "CLOSE" to "Year-end close", "REV" to "Reversal", "CASH" to "Cash closing")

    fun isLocked(date: String): Boolean =
        Db.scalarLong("SELECT count(*) FROM period_locks WHERE month=?", date.take(7)) > 0

    fun checkOpen(date: String) { if (isLocked(date)) fail("${date.take(7)} is locked. Unlock the month in Bookkeeping to change it.") }

    /** Posts a balanced voucher. Returns its id. */
    fun post(vtype: String, date: String, narration: String, lines: List<LedgerLine>, refType: String? = null, refId: Long? = null, attachment: String? = null): Long = Db.tx {
        checkOpen(date)
        val clean = lines.filter { it.debit != 0L || it.credit != 0L }
        if (clean.isEmpty()) fail("The voucher has no amounts.")
        if (clean.any { it.debit < 0 || it.credit < 0 }) fail("Amounts cannot be negative.")
        val dr = clean.sumOf { it.debit }; val cr = clean.sumOf { it.credit }
        if (dr != cr) fail("Debits (${Money.rs(dr)}) and credits (${Money.rs(cr)}) must be equal.")
        for (l in clean) { val a = Accounts.get(l.accountId); if (a.isGroup) fail("${a.name} is a group; post to an account under it.") }
        val no = Numbers.next(vtype, date)
        val vid = Db.insert("INSERT INTO vouchers(v_no,vtype,date,narration,ref_type,ref_id,attachment,created_at) VALUES(?,?,?,?,?,?,?,?)",
            no, vtype, date, narration, refType, refId, attachment, Dates.now())
        for (l in clean) Db.insert("INSERT INTO voucher_lines(voucher_id,account_id,debit,credit,party_type,party_id,memo) VALUES(?,?,?,?,?,?,?)",
            vid, l.accountId, l.debit, l.credit, l.partyType, l.partyId, l.memo)
        Audit.log("post", "voucher", vid, "$no $narration ${Money.rs(dr)}")
        vid
    }

    /** Posted vouchers are never edited; a reversing voucher cancels them. */
    fun reverse(voucherId: Long, date: String, reason: String): Long = Db.tx {
        val v = voucher(voucherId)
        if (v.reversed) fail("This voucher is already reversed.")
        val lines = Db.query("SELECT * FROM voucher_lines WHERE voucher_id=?", voucherId).map {
            LedgerLine(it.long("account_id"), it.long("credit"), it.long("debit"), it.strOrNull("party_type"), it.longOrNull("party_id"), it.strOrNull("memo"))
        }
        val id = post("REV", date, "Reversal of ${v.no}: $reason", lines, v.refType, v.refId)
        Db.exec("UPDATE vouchers SET reversal_of=? WHERE id=?", voucherId, id)
        Db.exec("UPDATE vouchers SET reversed=1 WHERE id=?", voucherId)
        id
    }

    fun voucher(id: Long): Voucher = Db.one(
        "SELECT v.*, (SELECT sum(debit) FROM voucher_lines WHERE voucher_id=v.id) total FROM vouchers v WHERE v.id=?", id)
        ?.let { mapV(it) } ?: fail("Voucher not found")

    private fun mapV(r: Row) = Voucher(r.long("id"), r.str("v_no"), r.str("vtype"), r.str("date"), r.str("narration"), r.strOrNull("ref_type"),
        r.longOrNull("ref_id"), r.bool("reversed"), r.longOrNull("reversal_of"), r.long("total"), r.strOrNull("attachment"))

    fun vouchers(from: String, to: String, type: String? = null, search: String = ""): List<Voucher> = Db.query(
        """SELECT v.*, (SELECT sum(debit) FROM voucher_lines WHERE voucher_id=v.id) total FROM vouchers v
           WHERE v.date BETWEEN ? AND ? AND (? IS NULL OR v.vtype=?) AND (v.v_no LIKE ? OR v.narration LIKE ?)
           ORDER BY v.date DESC, v.id DESC LIMIT 2000""", from, to, type, type, "%$search%", "%$search%").map(::mapV)

    fun lines(voucherId: Long): List<VoucherLineView> = Db.query(
        """SELECT l.*, a.code||'  '||a.name acc FROM voucher_lines l JOIN accounts a ON a.id=l.account_id WHERE voucher_id=? ORDER BY l.id""", voucherId).map {
        VoucherLineView(it.long("id"), it.str("acc"), it.long("account_id"), it.long("debit"), it.long("credit"),
            partyName(it.strOrNull("party_type"), it.longOrNull("party_id")), it.str("memo"))
    }

    fun partyName(type: String?, id: Long?): String = when {
        type == null || id == null -> ""
        type == "C" -> Db.scalarStr("SELECT name FROM customers WHERE id=?", id) ?: ""
        type == "S" -> Db.scalarStr("SELECT name FROM suppliers WHERE id=?", id) ?: ""
        type == "E" -> Db.scalarStr("SELECT name FROM staff WHERE id=?", id) ?: ""
        else -> ""
    }

    // ---------- balances and statements ----------

    private fun descendants(accountId: Long): List<Long> {
        val out = arrayListOf(accountId)
        var frontier = listOf(accountId)
        while (frontier.isNotEmpty()) {
            val next = frontier.flatMap { p -> Db.query("SELECT id FROM accounts WHERE parent_id=?", p).map { it.long("id") } }
            out.addAll(next); frontier = next
        }
        return out
    }

    /** Natural-sign balance of an account (and its children) over a date range. */
    fun balance(accountId: Long, from: String = "0000-00-00", to: String = "9999-12-31", partyType: String? = null, partyId: Long? = null): Long {
        val a = Accounts.get(accountId)
        val ids = descendants(accountId).joinToString(",")
        val dc = Db.one("""SELECT coalesce(sum(l.debit),0) d, coalesce(sum(l.credit),0) c FROM voucher_lines l JOIN vouchers v ON v.id=l.voucher_id
            WHERE l.account_id IN ($ids) AND v.date BETWEEN ? AND ? AND (? IS NULL OR (l.party_type=? AND l.party_id=?))""",
            from, to, partyType, partyType, partyId)!!
        val net = dc.long("d") - dc.long("c")
        return if (a.debitNormal) net else -net
    }

    data class TbRow(val account: Account, val debit: Long, val credit: Long)

    fun trialBalance(asOf: String): List<TbRow> {
        val sums = Db.query("""SELECT l.account_id id, sum(l.debit) d, sum(l.credit) c FROM voucher_lines l JOIN vouchers v ON v.id=l.voucher_id
            WHERE v.date<=? GROUP BY l.account_id""", asOf).associateBy { it.long("id") }
        return Accounts.all().filter { !it.isGroup }.mapNotNull { a ->
            val r = sums[a.id] ?: return@mapNotNull null
            val net = r.long("d") - r.long("c")
            if (net == 0L) null else TbRow(a, if (net > 0) net else 0, if (net < 0) -net else 0)
        }
    }

    data class StatementLine(val account: Account, val amount: Long)
    data class ProfitAndLoss(val income: List<StatementLine>, val expenses: List<StatementLine>) {
        val totalIncome get() = income.sumOf { it.amount }
        val totalExpenses get() = expenses.sumOf { it.amount }
        val profit get() = totalIncome - totalExpenses
    }

    private fun periodSums(from: String, to: String): Map<Long, Long> = Db.query(
        """SELECT l.account_id id, sum(l.debit)-sum(l.credit) n FROM voucher_lines l JOIN vouchers v ON v.id=l.voucher_id
           WHERE v.date BETWEEN ? AND ? AND v.vtype<>'CLOSE' GROUP BY l.account_id""", from, to).associate { it.long("id") to it.long("n") }

    fun profitAndLoss(from: String, to: String): ProfitAndLoss {
        val sums = periodSums(from, to)
        val accts = Accounts.all().filter { !it.isGroup }
        val inc = accts.filter { it.type == "INCOME" }.map { StatementLine(it, -(sums[it.id] ?: 0)) }.filter { it.amount != 0L }
        val exp = accts.filter { it.type == "EXPENSE" }.map { StatementLine(it, sums[it.id] ?: 0) }.filter { it.amount != 0L }
        return ProfitAndLoss(inc, exp)
    }

    data class BalanceSheet(val assets: List<StatementLine>, val liabilities: List<StatementLine>, val equity: List<StatementLine>, val currentProfit: Long) {
        val totalAssets get() = assets.sumOf { it.amount }
        val totalLiabilities get() = liabilities.sumOf { it.amount }
        val totalEquity get() = equity.sumOf { it.amount } + currentProfit
    }

    fun balanceSheet(asOf: String): BalanceSheet {
        val sums = Db.query("""SELECT l.account_id id, sum(l.debit)-sum(l.credit) n FROM voucher_lines l JOIN vouchers v ON v.id=l.voucher_id
            WHERE v.date<=? GROUP BY l.account_id""", asOf).associate { it.long("id") to it.long("n") }
        val accts = Accounts.all().filter { !it.isGroup }
        fun lines(t: String, debitNormal: Boolean) = accts.filter { it.type == t }
            .map { StatementLine(it, (sums[it.id] ?: 0).let { n -> if (debitNormal) n else -n }) }.filter { it.amount != 0L }
        // Profit not yet closed into retained earnings
        val profit = accts.filter { it.type == "INCOME" || it.type == "EXPENSE" }.sumOf { -(sums[it.id] ?: 0) }
        return BalanceSheet(lines("ASSET", true), lines("LIABILITY", false), lines("EQUITY", false), profit)
    }

    data class LedgerRow(val date: String, val voucherId: Long, val no: String, val narration: String, val debit: Long, val credit: Long, val balance: Long, val party: String)

    fun ledger(accountId: Long, from: String, to: String, partyType: String? = null, partyId: Long? = null): Pair<Long, List<LedgerRow>> {
        val a = Accounts.get(accountId)
        val ids = descendants(accountId).joinToString(",")
        val opening = balance(accountId, "0000-00-00", Dates.plusDays(from, -1), partyType, partyId)
        var bal = opening
        val rows = Db.query("""SELECT v.date, v.id vid, v.v_no, v.narration, l.debit, l.credit, l.party_type, l.party_id, l.memo FROM voucher_lines l
            JOIN vouchers v ON v.id=l.voucher_id WHERE l.account_id IN ($ids) AND v.date BETWEEN ? AND ?
            AND (? IS NULL OR (l.party_type=? AND l.party_id=?)) ORDER BY v.date, v.id""", from, to, partyType, partyType, partyId).map {
            val d = it.long("debit"); val c = it.long("credit")
            bal += if (a.debitNormal) d - c else c - d
            val memo = it.str("memo")
            LedgerRow(it.str("date"), it.long("vid"), it.str("v_no"), it.str("narration") + if (memo.isNotEmpty()) " — $memo" else "", d, c, bal,
                partyName(it.strOrNull("party_type"), it.longOrNull("party_id")))
        }
        return opening to rows
    }

    /** Balance a customer owes (positive) or a supplier is owed (positive). */
    fun customerBalance(id: Long) = balance(Accounts.sys("AR"), partyType = "C", partyId = id)
    fun supplierBalance(id: Long) = balance(Accounts.sys("AP"), partyType = "S", partyId = id)

    data class CashFlow(val operatingIn: Long, val operatingOut: Long, val investing: Long, val financing: Long, val opening: Long, val closing: Long)

    fun cashFlow(from: String, to: String): CashFlow {
        val cashIds = Accounts.cashAndBank().map { it.id }.toSet()
        val opening = cashIds.sumOf { balance(it, "0000-00-00", Dates.plusDays(from, -1)) }
        val closing = cashIds.sumOf { balance(it, "0000-00-00", to) }
        var opIn = 0L; var opOut = 0L; var inv = 0L; var fin = 0L
        val vids = Db.query("""SELECT DISTINCT v.id FROM vouchers v JOIN voucher_lines l ON l.voucher_id=v.id WHERE v.date BETWEEN ? AND ?""", from, to).map { it.long("id") }
        val accts = Accounts.all().associateBy { it.id }
        for (vid in vids) {
            val ls = Db.query("SELECT account_id, debit, credit FROM voucher_lines WHERE voucher_id=?", vid)
            val cashNet = ls.filter { it.long("account_id") in cashIds }.sumOf { it.long("debit") - it.long("credit") }
            if (cashNet == 0L) continue
            val others = ls.filter { it.long("account_id") !in cashIds }.map { accts[it.long("account_id")]!! }
            when {
                others.any { it.sys == "FIXED_ASSETS" } -> inv += cashNet
                others.any { it.type == "EQUITY" } -> fin += cashNet
                cashNet > 0 -> opIn += cashNet
                else -> opOut += -cashNet
            }
        }
        return CashFlow(opIn, opOut, inv, fin, opening, closing)
    }

    // ---------- period control ----------

    fun locks(): List<String> = Db.query("SELECT month FROM period_locks ORDER BY month DESC").map { it.str("month") }
    fun lock(month: String) { Db.exec("INSERT OR IGNORE INTO period_locks(month,locked_at) VALUES(?,?)", month, Dates.now()); Audit.log("lock", "period", null, month) }
    fun unlock(month: String) { Db.exec("DELETE FROM period_locks WHERE month=?", month); Audit.log("unlock", "period", null, month) }

    /** Moves the fiscal year's profit into retained earnings and locks its months. */
    fun closeYear(anyDateInYear: String): Long = Db.tx {
        val fy = Dates.fiscalCode(anyDateInYear)
        if (Db.scalarLong("SELECT count(*) FROM year_closes WHERE fy=?", fy) > 0) fail("This year is already closed.")
        val from = Dates.fiscalStart(anyDateInYear); val to = Dates.fiscalEnd(anyDateInYear)
        val sums = periodSums(from, to)
        val lines = ArrayList<LedgerLine>()
        var profit = 0L
        for (a in Accounts.all().filter { !it.isGroup && (it.type == "INCOME" || it.type == "EXPENSE") }) {
            val n = sums[a.id] ?: 0
            if (n == 0L) continue
            profit -= n
            lines += if (n > 0) LedgerLine(a.id, credit = n) else LedgerLine(a.id, debit = -n)
        }
        val ret = Accounts.sys("RETAINED")
        if (profit > 0) lines += LedgerLine(ret, credit = profit) else if (profit < 0) lines += LedgerLine(ret, debit = -profit)
        val vid = if (lines.isEmpty()) null else post("CLOSE", to, "Year-end close FY $fy", lines)
        Db.exec("INSERT INTO year_closes(fy,closed_at,voucher_id) VALUES(?,?,?)", fy, Dates.now(), vid)
        var m = java.time.LocalDate.parse(from)
        while (!m.isAfter(java.time.LocalDate.parse(to))) { lock(m.toString().take(7)); m = m.plusMonths(1) }
        vid ?: 0L
    }
}
