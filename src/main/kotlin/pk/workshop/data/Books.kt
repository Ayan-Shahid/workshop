package pk.workshop.data

import pk.workshop.core.Dates
import pk.workshop.core.Money
import pk.workshop.db.Db
import pk.workshop.db.Row
import pk.workshop.db.fail

/** Expenses, manual vouchers, daily cash closing. */
object Books {
    /** A simple expense: Dr expense account / Cr cash, bank or wallet. */
    fun expense(date: String, expenseAccountId: Long, amount: Long, method: String, narration: String, attachment: String?): Long {
        if (amount <= 0) fail("Enter an amount.")
        val a = Accounts.get(expenseAccountId)
        if (a.type != "EXPENSE") fail("Choose an expense account.")
        return Ledger.post("EXP", date, narration.ifBlank { a.name },
            listOf(LedgerLine(expenseAccountId, debit = amount), LedgerLine(Accounts.forMethod(method), credit = amount)), "expense", null, attachment)
    }

    /**
     * Manual voucher in the usual Pakistani forms. For CPV/BPV the cash/bank side is credited automatically,
     * for CRV/BRV it is debited; [lines] carry the other side. JV takes balanced lines as given.
     */
    fun manual(vtype: String, date: String, narration: String, cashBankAccountId: Long?, lines: List<LedgerLine>, attachment: String?): Long {
        if (narration.isBlank()) fail("Enter a narration.")
        val all = when (vtype) {
            "CPV", "BPV" -> {
                val cb = cashBankAccountId ?: Accounts.sys(if (vtype == "CPV") "CASH" else "BANK")
                lines + LedgerLine(cb, credit = lines.sumOf { it.debit - it.credit })
            }
            "CRV", "BRV" -> {
                val cb = cashBankAccountId ?: Accounts.sys(if (vtype == "CRV") "CASH" else "BANK")
                lines + LedgerLine(cb, debit = lines.sumOf { it.credit - it.debit })
            }
            "JV" -> lines
            else -> fail("Unknown voucher type")
        }
        return Ledger.post(vtype, date, narration, all, "manual", null, attachment)
    }

    // ---------- cash closing ----------

    data class CashDay(val date: String, val opening: Long, val cashIn: Long, val cashOut: Long) { val expected get() = opening + cashIn - cashOut }

    fun cashDay(date: String): CashDay {
        val cash = Accounts.sys("CASH")
        val opening = Ledger.balance(cash, "0000-00-00", Dates.plusDays(date, -1))
        val r = Db.one("""SELECT coalesce(sum(l.debit),0) i, coalesce(sum(l.credit),0) o FROM voucher_lines l JOIN vouchers v ON v.id=l.voucher_id
            WHERE l.account_id=? AND v.date=? AND v.vtype<>'CASH'""", cash, date)!!
        return CashDay(date, opening, r.long("i"), r.long("o"))
    }

    fun closeCash(date: String, counted: Long, note: String): Long = Db.tx {
        if (Db.scalarLong("SELECT count(*) FROM cash_closings WHERE date=?", date) > 0) fail("Cash for ${Dates.show(date)} is already closed.")
        val d = cashDay(date)
        val diff = counted - d.expected
        val vid = if (diff != 0L) Ledger.post("CASH", date, "Cash count difference ${Dates.show(date)}" + if (note.isNotBlank()) ": $note" else "",
            if (diff < 0) listOf(LedgerLine(Accounts.sys("ROUNDING"), debit = -diff), LedgerLine(Accounts.sys("CASH"), credit = -diff))
            else listOf(LedgerLine(Accounts.sys("CASH"), debit = diff), LedgerLine(Accounts.sys("OTHER_INCOME"), credit = diff)), "cash_closing", null) else null
        Db.insert("""INSERT INTO cash_closings(date,opening,cash_in,cash_out,expected,counted,difference,note,voucher_id) VALUES(?,?,?,?,?,?,?,?,?)""",
            date, d.opening, d.cashIn, d.cashOut, d.expected, counted, diff, note, vid)
    }

    fun closings() = Db.query("SELECT * FROM cash_closings ORDER BY date DESC LIMIT 400")

    // ---------- bank reconciliation ----------

    fun bankLines(accountId: Long, from: String, to: String) = Db.query("""SELECT l.id, v.date, v.v_no, v.narration, l.debit, l.credit, r.cleared_date
        FROM voucher_lines l JOIN vouchers v ON v.id=l.voucher_id LEFT JOIN bank_recon r ON r.voucher_line_id=l.id
        WHERE l.account_id=? AND v.date BETWEEN ? AND ? ORDER BY v.date, l.id""", accountId, from, to)

    fun setCleared(lineId: Long, cleared: Boolean, date: String = Dates.today()) {
        if (cleared) Db.exec("INSERT OR REPLACE INTO bank_recon(voucher_line_id,cleared_date) VALUES(?,?)", lineId, date)
        else Db.exec("DELETE FROM bank_recon WHERE voucher_line_id=?", lineId)
    }

    /** Balance per books counting only cleared lines up to [asOf]: compare with the bank statement. */
    fun clearedBalance(accountId: Long, asOf: String): Long = Db.scalarLong("""SELECT coalesce(sum(l.debit-l.credit),0) FROM voucher_lines l
        JOIN bank_recon r ON r.voucher_line_id=l.id WHERE l.account_id=? AND r.cleared_date<=?""", accountId, asOf)

    /** Opening balance of any account at go-live, against the Opening balances equity account. */
    fun openingBalance(accountId: Long, amount: Long, date: String) {
        val a = Accounts.get(accountId)
        if (amount == 0L) fail("Enter an amount.")
        val open = Accounts.sys("OPENING")
        val lines = if (a.debitNormal == (amount > 0)) listOf(LedgerLine(accountId, debit = kotlin.math.abs(amount)), LedgerLine(open, credit = kotlin.math.abs(amount)))
                    else listOf(LedgerLine(accountId, credit = kotlin.math.abs(amount)), LedgerLine(open, debit = kotlin.math.abs(amount)))
        Ledger.post("OPEN", date, "Opening balance: ${a.name}", lines, "account", accountId)
    }
}

data class Staff(val id: Long = 0, val name: String = "", val role: String = "Mechanic", val phone: String = "", val cnic: String = "", val salary: Long = 0,
                 val technicianId: Long? = null, val machineId: String = "", val joined: String = "", val active: Boolean = true)

object StaffBook {
    private fun map(r: Row) = Staff(r.long("id"), r.str("name"), r.str("role"), r.str("phone"), r.str("cnic"), r.long("salary"), r.longOrNull("technician_id"),
        r.str("machine_id"), r.str("joined"), r.bool("active"))

    fun list(all: Boolean = false) = Db.query("SELECT * FROM staff WHERE ?=1 OR active=1 ORDER BY name", all).map(::map)
    fun get(id: Long) = Db.one("SELECT * FROM staff WHERE id=?", id)?.let(::map) ?: fail("Staff member not found")

    fun save(s: Staff): Long {
        if (s.name.isBlank()) fail("Enter the name.")
        return if (s.id == 0L) Db.insert("INSERT INTO staff(name,role,phone,cnic,salary,technician_id,machine_id,joined) VALUES(?,?,?,?,?,?,?,?)",
            s.name, s.role, s.phone, s.cnic, s.salary, s.technicianId, s.machineId, s.joined)
        else { Db.exec("UPDATE staff SET name=?,role=?,phone=?,cnic=?,salary=?,technician_id=?,machine_id=?,joined=?,active=? WHERE id=?",
            s.name, s.role, s.phone, s.cnic, s.salary, s.technicianId, s.machineId, s.joined, s.active, s.id); s.id }
    }

    fun advanceBalance(staffId: Long) = Ledger.balance(Accounts.sys("STAFF_ADV"), partyType = "E", partyId = staffId)

    fun giveAdvance(staffId: Long, amount: Long, method: String, date: String): Long {
        if (amount <= 0) fail("Enter an amount.")
        return Ledger.post("CPV", date, "Advance to ${get(staffId).name}",
            listOf(LedgerLine(Accounts.sys("STAFF_ADV"), debit = amount, partyType = "E", partyId = staffId), LedgerLine(Accounts.forMethod(method), credit = amount)),
            "staff", staffId)
    }

    /** Commission earned in a month from labour lines done by the linked technician. */
    fun commission(staffId: Long, month: String): Long {
        val s = get(staffId)
        val techId = s.technicianId ?: return 0
        val bp = Db.scalarLong("SELECT commission_bp FROM technicians WHERE id=?", techId).toInt()
        val labour = Db.scalarLong("""SELECT coalesce(sum(l.net),0) FROM invoice_lines l JOIN invoices i ON i.id=l.invoice_id
            WHERE l.technician_id=? AND l.kind='LABOUR' AND substr(i.date,1,7)=?""", techId, month)
        return Money.pct(labour, bp)
    }

    fun payroll(month: String) = Db.query("SELECT p.*, s.name FROM payroll p JOIN staff s ON s.id=p.staff_id WHERE month=? ORDER BY s.name", month)

    /** Pays a month's salary: Dr Salaries + Commission / Cr Staff advances (deducted) + Cash/Bank. */
    fun paySalary(staffId: Long, month: String, basic: Long, commission: Long, advanceDeducted: Long, otherDeduction: Long, method: String, date: String): Long = Db.tx {
        if (Db.scalarLong("SELECT count(*) FROM payroll WHERE staff_id=? AND month=?", staffId, month) > 0) fail("Salary for $month is already paid.")
        if (advanceDeducted > advanceBalance(staffId)) fail("Deduction is more than the advance outstanding (${Money.rs(advanceBalance(staffId))}).")
        val net = basic + commission - advanceDeducted - otherDeduction
        if (net < 0) fail("Deductions are more than the pay.")
        val s = get(staffId)
        val ls = arrayListOf(LedgerLine(Accounts.sys("SALARIES"), debit = basic - otherDeduction), LedgerLine(Accounts.sys("COMMISSION"), debit = commission),
            LedgerLine(Accounts.sys("STAFF_ADV"), credit = advanceDeducted, partyType = "E", partyId = staffId), LedgerLine(Accounts.forMethod(method), credit = net))
        val vid = Ledger.post("SAL", date, "Salary $month — ${s.name}", ls, "staff", staffId)
        Db.insert("""INSERT INTO payroll(staff_id,month,basic,commission,advance_deducted,other_deduction,net,method,voucher_id) VALUES(?,?,?,?,?,?,?,?,?)""",
            staffId, month, basic, commission, advanceDeducted, otherDeduction, net, method, vid)
    }

    // ---------- attendance ----------

    fun attendance(month: String) = Db.query("""SELECT a.*, s.name FROM attendance a JOIN staff s ON s.id=a.staff_id WHERE substr(a.date,1,7)=? ORDER BY a.date, s.name""", month)

    fun markAttendance(staffId: Long, date: String, timeIn: String?, timeOut: String?) {
        Db.exec("""INSERT INTO attendance(staff_id,date,time_in,time_out) VALUES(?,?,?,?) ON CONFLICT(staff_id,date)
            DO UPDATE SET time_in=coalesce(excluded.time_in,time_in), time_out=coalesce(excluded.time_out,time_out)""", staffId, date, timeIn, timeOut)
    }

    /**
     * Imports an attendance log exported by a fingerprint machine (CSV/TXT): each line holds the machine's user ID and a date-time.
     * The first punch of a day is time in, the last is time out. Returns lines imported.
     */
    fun importAttendance(file: java.io.File): Int {
        val byMachine = list(true).filter { it.machineId.isNotBlank() }.associateBy { it.machineId.trim() }
        val punches = HashMap<Pair<Long, String>, MutableList<String>>()
        val dt = Regex("""(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2})""")
        var n = 0
        file.readLines().forEach { line ->
            val cells = line.split(',', '\t', ';').map { it.trim().trim('"') }
            val staff = cells.firstNotNullOfOrNull { byMachine[it] } ?: return@forEach
            val m = dt.find(line) ?: return@forEach
            punches.getOrPut(staff.id to m.groupValues[1]) { ArrayList() }.add(m.groupValues[2]); n++
        }
        Db.tx { punches.forEach { (k, times) -> times.sort(); markAttendance(k.first, k.second, times.first(), if (times.size > 1) times.last() else null) } }
        return n
    }
}

data class FixedAsset(val id: Long = 0, val name: String = "", val category: String = "", val bought: String = Dates.today(), val cost: Long = 0,
                      val salvage: Long = 0, val lifeMonths: Int = 60, val depreciated: Long = 0, val lastMonth: String? = null, val disposed: String? = null,
                      val notes: String = "") {
    val bookValue get() = cost - depreciated
    val monthly get() = if (lifeMonths <= 0) 0 else (cost - salvage) / lifeMonths
}

object Assets {
    private fun map(r: Row) = FixedAsset(r.long("id"), r.str("name"), r.str("category"), r.str("bought"), r.long("cost"), r.long("salvage"), r.int("life_months"),
        r.long("depreciated"), r.strOrNull("last_month"), r.strOrNull("disposed"), r.str("notes"))

    fun list() = Db.query("SELECT * FROM fixed_assets ORDER BY bought DESC").map(::map)

    /** Buying equipment: Dr Equipment / Cr cash-bank (or opening balances for things already owned). */
    fun add(a: FixedAsset, method: String?): Long = Db.tx {
        if (a.name.isBlank() || a.cost <= 0) fail("Enter the name and cost.")
        if (a.lifeMonths <= 0) fail("Enter the useful life in months.")
        val id = Db.insert("INSERT INTO fixed_assets(name,category,bought,cost,salvage,life_months,notes) VALUES(?,?,?,?,?,?,?)",
            a.name, a.category, a.bought, a.cost, a.salvage, a.lifeMonths, a.notes)
        val credit = if (method == null) Accounts.sys("OPENING") else Accounts.forMethod(method)
        Ledger.post(if (method == null) "OPEN" else "JV", a.bought, "Equipment: ${a.name}",
            listOf(LedgerLine(Accounts.sys("FIXED_ASSETS"), debit = a.cost), LedgerLine(credit, credit = a.cost)), "asset", id)
        id
    }

    /** Straight-line depreciation for a month for every asset in use. Returns total charged. */
    fun runDepreciation(month: String): Long = Db.tx {
        var total = 0L
        val lines = ArrayList<LedgerLine>()
        for (a in list()) {
            if (a.disposed != null || a.bought.take(7) > month || (a.lastMonth != null && a.lastMonth >= month)) continue
            val amount = minOf(a.monthly, a.cost - a.salvage - a.depreciated)
            if (amount <= 0) continue
            Db.exec("UPDATE fixed_assets SET depreciated=depreciated+?, last_month=? WHERE id=?", amount, month, a.id)
            total += amount
        }
        if (total > 0) {
            lines += LedgerLine(Accounts.sys("DEPRECIATION"), debit = total); lines += LedgerLine(Accounts.sys("ACC_DEP"), credit = total)
            val end = java.time.YearMonth.parse(month).atEndOfMonth().toString()
            Ledger.post("DEP", end, "Depreciation $month", lines)
        }
        total
    }

    fun dispose(id: Long, date: String) { Db.exec("UPDATE fixed_assets SET disposed=? WHERE id=?", date, id) }
}
