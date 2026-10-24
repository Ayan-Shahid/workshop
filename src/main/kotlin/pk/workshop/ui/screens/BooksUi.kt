package pk.workshop.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pk.workshop.core.Dates
import pk.workshop.core.Money
import pk.workshop.data.*
import pk.workshop.db.fail
import pk.workshop.io.Excel
import pk.workshop.print.Documents
import pk.workshop.print.Printers
import pk.workshop.ui.*
import pk.workshop.ui.Button

@Composable
fun BooksScreen() {
    var tab by remember { mutableStateOf(0) }
    Page("Bookkeeping") {
        Tabs(listOf("Vouchers", "Expenses", "Cash closing", "Ledger", "Trial balance", "Profit & loss", "Balance sheet", "Cash flow",
            "Bank reconciliation", "Chart of accounts", "Fixed assets", "Opening balances", "Month locks"), tab) { tab = it }
        when (tab) {
            0 -> VouchersTab(); 1 -> ExpensesTab(); 2 -> CashClosingTab(); 3 -> LedgerTab(); 4 -> TrialBalanceTab(); 5 -> PnlTab(); 6 -> BalanceSheetTab()
            7 -> CashFlowTab(); 8 -> BankReconTab(); 9 -> ChartTab(); 10 -> AssetsTab(); 11 -> OpeningTab(); 12 -> LocksTab()
        }
    }
}

@Composable
private fun PeriodBar(from: String, to: String, onFrom: (String) -> Unit, onTo: (String) -> Unit, extra: @Composable RowScope.() -> Unit = {}) {
    Toolbar {
        DateField("From", from, onFrom); DateField("To", to, onTo)
        TextButton({ onFrom(Dates.monthStart()); onTo(Dates.today()) }) { Text("This month") }
        TextButton({ onFrom(Dates.fiscalStart(Dates.today())); onTo(Dates.today()) }) { Text("This fiscal year") }
        extra()
    }
}

@Composable
private fun VouchersTab() {
    var from by remember { mutableStateOf(Dates.monthStart()) }
    var to by remember { mutableStateOf(Dates.today()) }
    var type by remember { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }
    var view by remember { mutableStateOf<Long?>(null) }
    var newV by remember { mutableStateOf<String?>(null) }
    val list = remember(App.version, from, to, type, q) { Ledger.vouchers(from, to, type, q) }
    PeriodBar(from, to, { from = it }, { to = it }) {
        SearchBox(q, { q = it }, Modifier.width(220.dp))
        Spacer(Modifier.weight(1f))
        Box {
            var menu by remember { mutableStateOf(false) }
            Button({ menu = true }) { IconText(Icons.Default.Add, "New voucher") }
            DropdownMenu(menu, { menu = false }) {
                Ledger.manualTypes.forEach { t -> DropdownMenuItem({ Text("$t — ${Ledger.typeNames[t]}") }, { newV = t; menu = false }) }
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        FilterChip(type == null, { type = null }, { Text("All") })
        listOf("CPV", "CRV", "BPV", "BRV", "JV", "SALE", "RCPT", "PUR", "PAY", "EXP", "SAL").forEach { t -> FilterChip(type == t, { type = if (type == t) null else t }, { Text(t) }) }
    }
    DataTable(list, listOf(C("No", 1.4f) { it.no }, C("Date", 0.8f) { Dates.show(it.date) }, C("Type", 1f) { Ledger.typeNames[it.type] ?: it.type },
        C("Narration", 3f) { it.narration }, C("Amount", 1f, true) { plain(it.total) }, C("", 0.8f, content = { v -> if (v.reversed) Chip("Reversed", MaterialTheme.colorScheme.error) })),
        Modifier.fillMaxSize(), onClick = { view = it.id }, empty = "No vouchers")
    view?.let { id -> VoucherDialog(id) { view = null } }
    newV?.let { t -> ManualVoucherDialog(t) { newV = null } }
}

@Composable
fun VoucherDialog(id: Long, onDismiss: () -> Unit) {
    val v = remember(App.version, id) { Ledger.voucher(id) }
    val lines = remember(App.version, id) { Ledger.lines(id) }
    var reverse by remember { mutableStateOf(false) }
    FormDialog("${v.no} · ${Ledger.typeNames[v.type] ?: v.type}", onDismiss, null, width = 820.dp, extraButtons = {
        OutlinedButton({ App.act { Printers.open(Documents.voucher(id)) } }) { IconText(Icons.Default.Print, "Print") }
        if (!v.reversed && v.type != "REV") OutlinedButton({ reverse = true }) { Text("Reverse") }
    }) {
        Kv("Date", Dates.show(v.date)); Kv("Narration", v.narration)
        v.attachment?.let { a -> TextButton({ App.act { Printers.open(java.io.File(a)) } }) { Text("Open attached bill") } }
        DataTable(lines, listOf(C("Account", 2.2f) { it.account }, C("Party / memo", 1.8f) { listOf(it.party, it.memo).filter { s -> s.isNotBlank() }.joinToString(" — ") },
            C("Debit", 1f, true) { if (it.debit > 0) plain(it.debit) else "" }, C("Credit", 1f, true) { if (it.credit > 0) plain(it.credit) else "" }),
            Modifier.heightIn(max = 400.dp), footer = listOf("Total", "", plain(lines.sumOf { it.debit }), plain(lines.sumOf { it.credit })))
    }
    if (reverse) {
        var reason by remember { mutableStateOf("") }
        FormDialog("Reverse ${v.no}", { reverse = false }, { if (App.act("Reversed") { Ledger.reverse(id, Dates.today(), reason.ifBlank { fail("Enter the reason.") }) }) { reverse = false; onDismiss() } }) {
            Text("Posted vouchers are never edited. A reversing voucher cancels this one today.")
            Field("Reason", reason, { reason = it }, Modifier.fillMaxWidth())
        }
    }
}

private data class VLine(val account: Account? = null, val debit: Long = 0, val credit: Long = 0, val partyType: String? = null, val partyId: Long? = null, val memo: String = "")

@Composable
private fun ManualVoucherDialog(type: String, onDone: () -> Unit) {
    val accounts = remember { Accounts.postable() }
    val cashBank = remember { Accounts.cashAndBank() }
    var date by remember { mutableStateOf(Dates.today()) }
    var narration by remember { mutableStateOf("") }
    var cb by remember { mutableStateOf(cashBank.firstOrNull { it.sys == if (type.startsWith("C")) "CASH" else "BANK" }) }
    var attach by remember { mutableStateOf<java.io.File?>(null) }
    val lines = remember { mutableStateListOf(VLine(), VLine()) }
    var pickParty by remember { mutableStateOf<Int?>(null) }
    val isJv = type == "JV"
    val payment = type == "CPV" || type == "BPV"
    FormDialog("${Ledger.typeNames[type]} voucher ($type)", onDone, {
        if (App.act("Voucher posted") {
                val ls = lines.filter { it.account != null && (it.debit != 0L || it.credit != 0L) }.map {
                    LedgerLine(it.account!!.id, if (isJv) it.debit else if (payment) it.debit else 0, if (isJv) it.credit else if (!payment) it.debit else 0, it.partyType, it.partyId, it.memo.ifBlank { null })
                }
                val saved = attach?.let { f -> val d = java.io.File(pk.workshop.security.Vault.dataDir, "attachments/vouchers").apply { mkdirs() }
                    java.io.File(d, System.currentTimeMillis().toString() + "-" + f.name).also { f.copyTo(it) }.path }
                Books.manual(type, date, narration, cb?.id, ls, saved)
            }) onDone()
    }, saveText = "Post voucher", width = 920.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DateField("Date", date, { date = it })
            if (!isJv) Dropdown(if (payment) "Paid from" else "Received into", cashBank, cb, { cb = it }, Modifier.width(260.dp), text = { it.name })
            TextButton({ attach = FilePick.open("Attach a photo of the bill") }) { IconText(Icons.Default.AttachFile, attach?.name ?: "Attach bill") }
        }
        Field("Narration", narration, { narration = it }, Modifier.fillMaxWidth())
        Text(if (isJv) "Debits must equal credits." else if (payment) "Accounts paid (debited). The cash/bank side is credited automatically." else "Accounts received from (credited). The cash/bank side is debited automatically.",
            style = MaterialTheme.typography.bodySmall)
        lines.forEachIndexed { i, l ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Dropdown("Account", accounts, l.account, { lines[i] = l.copy(account = it) }, Modifier.weight(2f), text = { it.label })
                if (l.account?.sys in setOf("AR", "AP", "STAFF_ADV")) TextButton({ pickParty = i }) { Text(if (l.partyId != null) Ledger.partyName(l.partyType, l.partyId).take(14) else "Party") }
                MoneyField(if (isJv) "Debit" else "Amount", l.debit, { lines[i] = l.copy(debit = it ?: 0) }, Modifier.weight(1f))
                if (isJv) MoneyField("Credit", l.credit, { lines[i] = l.copy(credit = it ?: 0) }, Modifier.weight(1f))
                Field("Memo", l.memo, { lines[i] = l.copy(memo = it) }, Modifier.weight(1.2f))
                IconButton({ if (lines.size > 1) lines.removeAt(i) }) { Icon(Icons.Default.Close, "Remove") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton({ lines += VLine() }) { IconText(Icons.Default.Add, "Add line") }
            Spacer(Modifier.weight(1f))
            if (isJv) Text("Debit ${plain(lines.sumOf { it.debit })} · Credit ${plain(lines.sumOf { it.credit })}", fontWeight = FontWeight.SemiBold,
                color = if (lines.sumOf { it.debit } == lines.sumOf { it.credit }) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
            else Text("Total ${plain(lines.sumOf { it.debit })}", fontWeight = FontWeight.SemiBold)
        }
    }
    pickParty?.let { i ->
        val sys = lines[i].account?.sys
        when (sys) {
            "AR" -> CustomerPicker({ lines[i] = lines[i].copy(partyType = "C", partyId = it.id); pickParty = null }, { pickParty = null })
            "AP" -> Picker("Supplier", { Suppliers.list(it) }, { it.name }, onPick = { lines[i] = lines[i].copy(partyType = "S", partyId = it.id); pickParty = null }, onDismiss = { pickParty = null })
            else -> Picker("Staff member", { q -> StaffBook.list().filter { it.name.contains(q, true) } }, { it.name }, onPick = { lines[i] = lines[i].copy(partyType = "E", partyId = it.id); pickParty = null }, onDismiss = { pickParty = null })
        }
    }
}

@Composable
private fun ExpensesTab() {
    val expenseAccounts = remember(App.version) { Accounts.byType("EXPENSE").filter { it.sys !in setOf("COGS", "STOCK_ADJ", "DEPRECIATION", "ROUNDING") } }
    var acct by remember { mutableStateOf(expenseAccounts.firstOrNull()) }
    var amount by remember { mutableStateOf<Long?>(0) }
    var method by remember { mutableStateOf("Cash") }
    var narration by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(Dates.today()) }
    var attach by remember { mutableStateOf<java.io.File?>(null) }
    var from by remember { mutableStateOf(Dates.monthStart()) }
    val list = remember(App.version, from) { Ledger.vouchers(from, Dates.today(), "EXP") }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Section("Add an expense", Modifier.width(420.dp)) {
            Dropdown("Category", expenseAccounts, acct, { acct = it }, text = { it.name }, modifier = Modifier.fillMaxWidth())
            MoneyField("Amount", amount, { amount = it }, Modifier.fillMaxWidth())
            Dropdown("Paid by", Defaults.payMethods.filter { it != "Cheque" }, method, { method = it }, Modifier.fillMaxWidth())
            Field("Details", narration, { narration = it }, Modifier.fillMaxWidth(), placeholder = "LESCO bill for September")
            Row(verticalAlignment = Alignment.CenterVertically) {
                DateField("Date", date, { date = it }); TextButton({ attach = FilePick.open("Photo of the bill") }) { Text(attach?.name ?: "Attach bill photo") }
            }
            Button({ App.act("Expense saved") {
                val saved = attach?.let { f -> val d = java.io.File(pk.workshop.security.Vault.dataDir, "attachments/expenses").apply { mkdirs() }
                    java.io.File(d, System.currentTimeMillis().toString() + "-" + f.name).also { f.copyTo(it) }.path }
                Books.expense(date, acct?.id ?: fail("Choose a category."), amount ?: 0, method, narration, saved)
                amount = 0; narration = ""; attach = null
            } }, Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Save expense") }
        }
        Column(Modifier.weight(1f)) {
            Toolbar { DateField("Since", from, { from = it }); Text("Total ${Money.rs(list.sumOf { it.total })}", fontWeight = FontWeight.SemiBold) }
            var view by remember { mutableStateOf<Long?>(null) }
            DataTable(list, listOf(C("Date", 0.8f) { Dates.show(it.date) }, C("No", 1f) { it.no }, C("Details", 2.5f) { it.narration }, C("Amount", 1f, true) { plain(it.total) }),
                Modifier.fillMaxSize(), onClick = { view = it.id })
            view?.let { VoucherDialog(it) { view = null } }
        }
    }
}

@Composable
private fun CashClosingTab() {
    var date by remember { mutableStateOf(Dates.today()) }
    val day = remember(App.version, date) { Books.cashDay(date) }
    var counted by remember { mutableStateOf<Long?>(null) }
    var note by remember { mutableStateOf("") }
    val closings = remember(App.version) { Books.closings() }
    // Count by notes
    val notes = listOf(5000L, 1000L, 500L, 100L, 50L, 20L, 10L, 5L, 2L, 1L)
    val pieces = remember(date) { mutableStateMapOf<Long, String>() }
    val byNotes = notes.sumOf { (pieces[it]?.toLongOrNull() ?: 0L) * it * 100 }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Section("Close the day", Modifier.width(460.dp)) {
            DateField("Date", date, { date = it })
            Kv("Opening cash", Money.rs(day.opening)); Kv("Cash in", Money.rs(day.cashIn)); Kv("Cash out", Money.rs(day.cashOut)); Kv("Should be in the drawer", Money.rs(day.expected), bold = true)
            Text("Count the notes", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            notes.chunked(5).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { n -> Field("Rs $n ×", pieces[n] ?: "", { pieces[n] = it.filter(Char::isDigit) }, Modifier.weight(1f), numeric = true) }
            } }
            val c = counted ?: byNotes
            MoneyField("Counted (or type the total)", counted ?: byNotes, { counted = it })
            val diff = c - day.expected
            Text(if (diff == 0L) "Matches" else if (diff < 0) "Short by ${Money.rs(-diff)}" else "Over by ${Money.rs(diff)}", fontWeight = FontWeight.SemiBold,
                color = if (diff == 0L) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
            Field("Note", note, { note = it }, Modifier.fillMaxWidth())
            Button({ App.act("Day closed") { Books.closeCash(date, c, note) } }, Modifier.fillMaxWidth()) { Text("Close cash for ${Dates.show(date)}") }
        }
        DataTable(closings, listOf(C("Date", 1f) { Dates.show(it.str("date")) }, C("Expected", 1f, true) { plain(it.long("expected")) }, C("Counted", 1f, true) { plain(it.long("counted")) },
            C("Difference", 1f, true) { plain(it.long("difference")) }, C("Note", 2f) { it.str("note") }), Modifier.weight(1f).fillMaxHeight(), empty = "No closings yet")
    }
}

@Composable
private fun LedgerTab() {
    val accounts = remember(App.version) { Accounts.all() }
    var acct by remember { mutableStateOf(accounts.firstOrNull { it.sys == "CASH" }) }
    var from by remember { mutableStateOf(Dates.monthStart()) }
    var to by remember { mutableStateOf(Dates.today()) }
    var view by remember { mutableStateOf<Long?>(null) }
    val data = remember(App.version, acct, from, to) { acct?.let { Ledger.ledger(it.id, from, to) } }
    PeriodBar(from, to, { from = it }, { to = it }) { Dropdown("Account", accounts, acct, { acct = it }, Modifier.width(320.dp), text = { it.label }) }
    if (data != null) {
        Kv("Opening balance", Money.rs(data.first), bold = true)
        DataTable(data.second, listOf(C("Date", 0.8f) { Dates.show(it.date) }, C("No", 1f) { it.no }, C("Narration", 2.6f) { it.narration }, C("Party", 1.2f) { it.party },
            C("Debit", 0.9f, true) { if (it.debit > 0) plain(it.debit) else "" }, C("Credit", 0.9f, true) { if (it.credit > 0) plain(it.credit) else "" }, C("Balance", 1f, true) { plain(it.balance) }),
            Modifier.fillMaxSize(), onClick = { view = it.voucherId },
            footer = listOf("", "", "Closing balance", "", plain(data.second.sumOf { it.debit }), plain(data.second.sumOf { it.credit }), plain(data.second.lastOrNull()?.balance ?: data.first)))
    }
    view?.let { VoucherDialog(it) { view = null } }
}

@Composable
private fun ExportRow(t: () -> Table, period: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ App.act { Printers.open(Documents.table(t(), period)) } }) { IconText(Icons.Default.PictureAsPdf, "PDF") }
        OutlinedButton({ App.act { Printers.open(Excel.exportFile(t())) } }) { IconText(Icons.Default.TableView, "Excel") }
    }
}

@Composable
private fun TrialBalanceTab() {
    var asOf by remember { mutableStateOf(Dates.today()) }
    val rows = remember(App.version, asOf) { Ledger.trialBalance(asOf) }
    val table = { Table("Trial balance", listOf("Code", "Account", "Debit", "Credit"), rows.map { listOf(it.account.code, it.account.name, it.debit, it.credit) }, setOf(2, 3)) }
    Toolbar { DateField("As of", asOf, { asOf = it }); Spacer(Modifier.weight(1f)); ExportRow(table, "As of ${Dates.show(asOf)}") }
    val d = rows.sumOf { it.debit }; val c = rows.sumOf { it.credit }
    Text(if (d == c) "Balanced" else "Out of balance by ${Money.rs(d - c)}", color = if (d == c) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
    DataTable(rows, listOf(C("Code", 0.6f) { it.account.code }, C("Account", 3f) { it.account.name }, C("Type", 1f) { it.account.type.lowercase() },
        C("Debit", 1f, true) { if (it.debit > 0) plain(it.debit) else "" }, C("Credit", 1f, true) { if (it.credit > 0) plain(it.credit) else "" }),
        Modifier.fillMaxSize(), footer = listOf("", "Total", "", plain(d), plain(c)))
}

@Composable
private fun StatementBlock(title: String, lines: List<Ledger.StatementLine>, total: Long) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    lines.forEach { Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) { Text(it.account.name, Modifier.weight(1f)); Text(plain(it.amount)) } }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Row(Modifier.fillMaxWidth()) { Text("Total $title".lowercase().replaceFirstChar { it.uppercase() }, Modifier.weight(1f), fontWeight = FontWeight.SemiBold); Text(plain(total), fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun PnlTab() {
    var from by remember { mutableStateOf(Dates.monthStart()) }
    var to by remember { mutableStateOf(Dates.today()) }
    val p = remember(App.version, from, to) { Ledger.profitAndLoss(from, to) }
    val table = { Table("Profit and loss", listOf("Section", "Account", "Amount"), p.income.map { listOf("Income", it.account.name, it.amount) } + p.expenses.map { listOf("Expense", it.account.name, it.amount) } +
        listOf(listOf("", "Net profit", p.profit)), setOf(2), totals = false) }
    PeriodBar(from, to, { from = it }, { to = it }) { Spacer(Modifier.weight(1f)); ExportRow(table, "${Dates.show(from)} to ${Dates.show(to)}") }
    Section(modifier = Modifier.width(640.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            StatementBlock("Income", p.income, p.totalIncome); StatementBlock("Expenses", p.expenses, p.totalExpenses)
            Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text(if (p.profit >= 0) "Net profit" else "Net loss", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                Text(Money.rs(kotlin.math.abs(p.profit)), style = MaterialTheme.typography.titleLarge, color = if (p.profit >= 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun BalanceSheetTab() {
    var asOf by remember { mutableStateOf(Dates.today()) }
    val b = remember(App.version, asOf) { Ledger.balanceSheet(asOf) }
    val table = { Table("Balance sheet", listOf("Section", "Account", "Amount"), b.assets.map { listOf("Assets", it.account.name, it.amount) } + b.liabilities.map { listOf("Liabilities", it.account.name, it.amount) } +
        b.equity.map { listOf("Capital", it.account.name, it.amount) } + listOf(listOf("Capital", "Profit not yet closed", b.currentProfit)), setOf(2), totals = false) }
    Toolbar { DateField("As of", asOf, { asOf = it }); Spacer(Modifier.weight(1f)); ExportRow(table, "As of ${Dates.show(asOf)}") }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Section(modifier = Modifier.weight(1f)) { Column(Modifier.verticalScroll(rememberScrollState())) { StatementBlock("Assets", b.assets, b.totalAssets) } }
        Section(modifier = Modifier.weight(1f)) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                StatementBlock("Liabilities", b.liabilities, b.totalLiabilities)
                StatementBlock("Capital", b.equity + listOfNotNull(if (b.currentProfit != 0L) Ledger.StatementLine(Account(0, "", "Profit for the period (not yet closed)", "EQUITY", null, null, false, true), b.currentProfit) else null), b.totalEquity)
                val ok = b.totalAssets == b.totalLiabilities + b.totalEquity
                Text(if (ok) "Assets equal liabilities plus capital." else "Does not balance — check opening balances.", color = if (ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}

@Composable
private fun CashFlowTab() {
    var from by remember { mutableStateOf(Dates.monthStart()) }
    var to by remember { mutableStateOf(Dates.today()) }
    val f = remember(App.version, from, to) { Ledger.cashFlow(from, to) }
    PeriodBar(from, to, { from = it }, { to = it })
    Section("Cash and bank movement", Modifier.width(560.dp)) {
        Kv("Opening cash and bank", Money.rs(f.opening), bold = true)
        Kv("Money in from business", Money.rs(f.operatingIn)); Kv("Money out on business", Money.rs(-f.operatingOut))
        Kv("Equipment bought / sold", Money.rs(f.investing)); Kv("Owner's money in / out", Money.rs(f.financing))
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Kv("Closing cash and bank", Money.rs(f.closing), bold = true)
    }
}

@Composable
private fun BankReconTab() {
    val banks = remember(App.version) { Accounts.cashAndBank().filter { it.sys != "CASH" } }
    var acct by remember { mutableStateOf(banks.firstOrNull()) }
    var from by remember { mutableStateOf(Dates.monthStart()) }
    var to by remember { mutableStateOf(Dates.today()) }
    var stmt by remember { mutableStateOf<Long?>(0) }
    val rows = remember(App.version, acct, from, to) { acct?.let { Books.bankLines(it.id, from, to) } ?: emptyList() }
    val cleared = remember(App.version, acct, to) { acct?.let { Books.clearedBalance(it.id, to) } ?: 0 }
    val book = remember(App.version, acct, to) { acct?.let { Ledger.balance(it.id, to = to) } ?: 0 }
    PeriodBar(from, to, { from = it }, { to = it }) { Dropdown("Account", banks, acct, { acct = it }, Modifier.width(260.dp), text = { it.name }) }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        Stat("Balance in books", Money.rs(book), Modifier.weight(1f)); Stat("Cleared (ticked) balance", Money.rs(cleared), Modifier.weight(1f))
        Column(Modifier.weight(1f)) { MoneyField("Balance on bank statement", stmt, { stmt = it }); Text("Difference: ${Money.rs((stmt ?: 0) - cleared)}", fontWeight = FontWeight.SemiBold) }
    }
    DataTable(rows, listOf(C("Cleared", 0.5f, content = { r -> Checkbox(r.strOrNull("cleared_date") != null, { c -> App.act { Books.setCleared(r.long("id"), c, to) } }) }),
        C("Date", 0.8f) { Dates.show(it.str("date")) }, C("No", 1f) { it.str("v_no") }, C("Narration", 2.6f) { it.str("narration") },
        C("In", 0.9f, true) { if (it.long("debit") > 0) plain(it.long("debit")) else "" }, C("Out", 0.9f, true) { if (it.long("credit") > 0) plain(it.long("credit")) else "" }), Modifier.fillMaxSize())
}

@Composable
private fun ChartTab() {
    val all = remember(App.version) { Accounts.all() }
    var edit by remember { mutableStateOf<Account?>(null) }
    Toolbar { Text("Built-in accounts are used by automatic postings; add your own under any group.", Modifier.weight(1f))
        Button({ edit = Account(0, "", "", "EXPENSE", all.firstOrNull { it.code == "5000" }?.id, null, false, true) }) { IconText(Icons.Default.Add, "New account") } }
    val balances = remember(App.version) { Ledger.trialBalance(Dates.today()).associate { it.account.id to (it.debit - it.credit) } }
    DataTable(all, listOf(C("Code", 0.6f) { it.code }, C("Account", 3f, content = { a ->
        val depth = generateSequence(a.parentId) { pid -> all.firstOrNull { it.id == pid }?.parentId }.count()
        Text("    ".repeat(depth) + a.name, fontWeight = if (a.isGroup) FontWeight.SemiBold else FontWeight.Normal) }),
        C("Type", 1f) { it.type.lowercase() }, C("Built-in", 0.6f) { if (it.sys != null) "yes" else "" },
        C("Balance (Dr+/Cr−)", 1.2f, true) { balances[it.id]?.let { b -> plain(b) } ?: "" }, C("", 0.6f) { if (!it.active) "inactive" else "" }),
        Modifier.fillMaxSize(), onClick = { edit = it })
    edit?.let { a0 ->
        var code by remember(a0) { mutableStateOf(a0.code) }; var name by remember(a0) { mutableStateOf(a0.name) }; var type by remember(a0) { mutableStateOf(a0.type) }
        var parent by remember(a0) { mutableStateOf(all.firstOrNull { it.id == a0.parentId }) }; var group by remember(a0) { mutableStateOf(a0.isGroup) }
        FormDialog(if (a0.id == 0L) "New account" else a0.name, { edit = null }, { if (App.act("Saved") { Accounts.save(a0.id.takeIf { it != 0L }, code, name, type, parent?.id, group) }) edit = null },
            extraButtons = { if (a0.id != 0L && a0.sys == null) TextButton({ App.act { Accounts.setActive(a0.id, !a0.active) }; edit = null }) { Text(if (a0.active) "Deactivate" else "Activate") } }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Field("Code", code, { code = it }, Modifier.weight(0.5f)); Field("Name", name, { name = it }, Modifier.weight(1.5f)) }
            Dropdown("Type", listOf("ASSET", "LIABILITY", "EQUITY", "INCOME", "EXPENSE"), type, { type = it }, text = { it.lowercase().replaceFirstChar { c -> c.uppercase() } })
            Dropdown("Under group", all.filter { it.isGroup }, parent, { parent = it; type = it.type }, text = { it.label })
            Check("This is a group (holds other accounts, no postings)", group) { group = it }
        }
    }
}

@Composable
private fun AssetsTab() {
    val list = remember(App.version) { Assets.list() }
    var add by remember { mutableStateOf(false) }
    var month by remember { mutableStateOf(Dates.today().take(7)) }
    Toolbar {
        Field("Month (yyyy-mm)", month, { month = it }, Modifier.width(160.dp))
        Button({ App.act { App.toast("Depreciation charged: ${Money.rs(Assets.runDepreciation(month))}") } }) { Text("Charge depreciation for $month") }
        Spacer(Modifier.weight(1f)); Button({ add = true }) { IconText(Icons.Default.Add, "Add equipment") }
    }
    DataTable(list, listOf(C("Equipment", 2f) { it.name }, C("Bought", 0.8f) { Dates.show(it.bought) }, C("Cost", 1f, true) { plain(it.cost) },
        C("Per month", 0.9f, true) { plain(it.monthly) }, C("Depreciated", 1f, true) { plain(it.depreciated) }, C("Book value", 1f, true) { plain(it.bookValue) },
        C("", 0.8f, content = { a -> if (a.disposed == null) TextButton({ App.act("Marked disposed") { Assets.dispose(a.id, Dates.today()) } }) { Text("Dispose") } else Text("Disposed") })),
        Modifier.fillMaxSize(), empty = "No equipment recorded", footer = listOf("", "", plain(list.sumOf { it.cost }), "", plain(list.sumOf { it.depreciated }), plain(list.sumOf { it.bookValue }), ""))
    if (add) {
        var a by remember { mutableStateOf(FixedAsset()) }; var paid by remember { mutableStateOf(false) }; var method by remember { mutableStateOf("Cash") }
        FormDialog("Add equipment", { add = false }, { if (App.act("Saved") { Assets.add(a, if (paid) method else null) }) add = false }) {
            Field("Name", a.name, { a = a.copy(name = it) }, Modifier.fillMaxWidth(), placeholder = "Car lift, compressor, scanner…")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { MoneyField("Cost", a.cost, { a = a.copy(cost = it ?: 0) }, Modifier.weight(1f)); MoneyField("Scrap value", a.salvage, { a = a.copy(salvage = it ?: 0) }, Modifier.weight(1f)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                DateField("Bought on", a.bought, { a = a.copy(bought = it) }); Field("Useful life (months)", a.lifeMonths.toString(), { a = a.copy(lifeMonths = it.toIntOrNull() ?: 0) }, Modifier.weight(1f), numeric = true)
            }
            Check("Paid for now (otherwise it was already owned at go-live)", paid) { paid = it }
            if (paid) Dropdown("Paid by", Defaults.payMethods, method, { method = it })
        }
    }
}

@Composable
private fun OpeningTab() {
    val accounts = remember(App.version) { Accounts.postable().filter { it.sys !in setOf("AR", "AP", "INVENTORY", "OPENING") && it.type != "INCOME" && it.type != "EXPENSE" } }
    var acct by remember { mutableStateOf(accounts.firstOrNull { it.sys == "CASH" }) }
    var amount by remember { mutableStateOf<Long?>(0) }
    var date by remember { mutableStateOf(Dates.today()) }
    Section("Opening balances at go-live", Modifier.width(640.dp)) {
        Text("Enter what you had on the day you started using the app. Customer and supplier balances are entered on their own pages; stock on each part (Inventory › Opening stock).")
        Dropdown("Account", accounts, acct, { acct = it }, Modifier.fillMaxWidth(), text = { it.label })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MoneyField("Balance (negative to reverse its usual side)", amount, { amount = it }, Modifier.weight(1f)); DateField("As of", date, { date = it })
        }
        Button({ App.act("Opening balance posted") { Books.openingBalance(acct?.id ?: fail("Choose an account."), amount ?: 0, date) } }) { Text("Post opening balance") }
        Text("Opening balances account now: ${Money.rs(Ledger.balance(Accounts.sys("OPENING")))} (move it to Owner's capital with a journal once everything is entered).",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun LocksTab() {
    val locks = remember(App.version) { Ledger.locks() }
    var month by remember { mutableStateOf(Dates.today().take(7)) }
    var closeYear by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Section("Lock a month", Modifier.width(420.dp)) {
            Text("After the accountant reviews a month, lock it so nothing in it can be changed.")
            Field("Month (yyyy-mm)", month, { month = it }, Modifier.width(200.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ App.act("$month locked") { Ledger.lock(month) } }) { Text("Lock") }
                OutlinedButton({ App.act("$month unlocked") { Ledger.unlock(month) } }) { Text("Unlock") }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("Year-end close moves the year's profit into retained earnings and locks all 12 months.")
            OutlinedButton({ closeYear = true }) { Text("Close a fiscal year") }
        }
        Section("Locked months", Modifier.weight(1f)) { if (locks.isEmpty()) Empty("No months locked"); locks.forEach { Text(it) } }
    }
    if (closeYear) {
        var d by remember { mutableStateOf(Dates.plusDays(Dates.fiscalStart(Dates.today()), -1)) }
        FormDialog("Close fiscal year", { closeYear = false }, { if (App.act("Year closed") { Ledger.closeYear(d) }) closeYear = false }, saveText = "Close year") {
            DateField("Any date in the year to close", d, { d = it })
            Text("Fiscal year ${Dates.show(Dates.fiscalStart(d))} to ${Dates.show(Dates.fiscalEnd(d))}. This cannot be undone.", color = MaterialTheme.colorScheme.error)
        }
    }
}
