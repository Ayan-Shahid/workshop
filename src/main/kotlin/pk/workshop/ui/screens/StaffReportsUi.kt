package pk.workshop.ui.screens

import androidx.compose.foundation.layout.*
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
import pk.workshop.core.Pct
import pk.workshop.data.*
import pk.workshop.io.Excel
import pk.workshop.print.Documents
import pk.workshop.print.Printers
import pk.workshop.ui.*
import pk.workshop.ui.Button

@Composable
fun StaffScreen() {
    var tab by rememberTab("Staff")
    Page("Staff") {
        Tabs(listOf("Staff", "Technicians", "Salaries", "Attendance"), tab) { tab = it }
        when (tab) { 0 -> StaffTab(); 1 -> TechTab(); 2 -> PayrollTab(); 3 -> AttendanceTab() }
    }
}

@Composable
private fun StaffTab() {
    val list = remember(App.version) { StaffBook.list(true) }
    val techs = remember(App.version) { Technicians.list(true) }
    var edit by remember { mutableStateOf<Staff?>(null) }
    var adv by remember { mutableStateOf<Staff?>(null) }
    Toolbar { Spacer(Modifier.weight(1f)); Button({ edit = Staff() }) { IconText(Icons.Default.PersonAdd, "Add staff") } }
    DataTable(list, listOf(C("Name", 1.5f) { it.name + if (!it.active) " (left)" else "" }, C("Role", 1f) { it.role }, C("Phone", 1f) { it.phone },
        C("Salary", 1f, true) { plain(it.salary) }, C("Advance owed", 1f, true) { plain(StaffBook.advanceBalance(it.id)) },
        C("", 1f, content = { s -> TextButton({ adv = s }) { BtnText("Give advance") } })), Modifier.fillMaxSize(), onClick = { edit = it })
    edit?.let { s0 ->
        var s by remember(s0) { mutableStateOf(s0) }
        FormDialog(if (s0.id == 0L) "Add staff" else s0.name, { edit = null }, { if (App.act("Saved") { StaffBook.save(s) }) edit = null }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Field("Name", s.name, { s = s.copy(name = it) }, Modifier.weight(1f)); Field("Role", s.role, { s = s.copy(role = it) }, Modifier.weight(1f)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Field("Phone", s.phone, { s = s.copy(phone = it) }, Modifier.weight(1f)); Field("CNIC", s.cnic, { s = s.copy(cnic = it) }, Modifier.weight(1f)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { MoneyField("Monthly salary", s.salary, { s = s.copy(salary = it ?: 0) }, Modifier.weight(1f)); DateField("Joined", s.joined, { s = s.copy(joined = it) }) }
            Dropdown("Linked technician (for commission)", listOf<Technician?>(null) + techs, techs.firstOrNull { it.id == s.technicianId }, { s = s.copy(technicianId = it?.id) }, text = { it?.name ?: "None" })
            Field("ID on attendance machine", s.machineId, { s = s.copy(machineId = it) }, Modifier.fillMaxWidth())
            if (s0.id != 0L) Check("Still working here", s.active) { s = s.copy(active = it) }
        }
    }
    adv?.let { s ->
        var amt by remember { mutableStateOf<Long?>(0) }; var method by remember { mutableStateOf("Cash") }
        FormDialog("Advance to ${s.name}", { adv = null }, { if (App.act("Advance recorded") { StaffBook.giveAdvance(s.id, amt ?: 0, method, Dates.today()) }) adv = null }) {
            Text("Already owes ${Money.rs(StaffBook.advanceBalance(s.id))}. Advances are deducted when salary is paid.")
            MoneyField("Amount", amt, { amt = it }); Dropdown("Paid by", Defaults.payMethods, method, { method = it })
        }
    }
}

@Composable
private fun TechTab() {
    val list = remember(App.version) { Technicians.list(true) }
    var edit by remember { mutableStateOf<Technician?>(null) }
    Toolbar { Text("Technicians appear on job cards; commission is a percentage of the labour they bill.", Modifier.weight(1f)); Button({ edit = Technician() }) { IconText(Icons.Default.Add, "Add technician") } }
    DataTable(list, listOf(C("Name", 2f) { it.name + if (!it.active) " (inactive)" else "" }, C("Department", 1f) { it.department }, C("Phone", 1f) { it.phone }, C("Commission", 1f, true) { Pct.fmt(it.commissionBp) }),
        Modifier.fillMaxSize(), onClick = { edit = it })
    edit?.let { t0 ->
        var t by remember(t0) { mutableStateOf(t0) }; var pct by remember(t0) { mutableStateOf(Pct.fmt(t0.commissionBp).removeSuffix("%")) }
        FormDialog(if (t0.id == 0L) "Add technician" else t0.name, { edit = null }, { if (App.act("Saved") { Technicians.save(t.copy(commissionBp = Pct.parse(pct) ?: 0)) }) edit = null }) {
            Field("Name", t.name, { t = t.copy(name = it) }, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Field("Phone", t.phone, { t = t.copy(phone = it) }, Modifier.weight(1f)); Dropdown("Department", Defaults.departments, t.department.ifBlank { null }, { t = t.copy(department = it) }, Modifier.weight(1f)) }
            Field("Commission % on labour", pct, { pct = it }, Modifier.width(220.dp), numeric = true)
            if (t0.id != 0L) Check("Active", t.active) { t = t.copy(active = it) }
        }
    }
}

@Composable
private fun PayrollTab() {
    var month by remember { mutableStateOf(Dates.today().take(7)) }
    val staff = remember(App.version) { StaffBook.list() }
    val paid = remember(App.version, month) { StaffBook.payroll(month) }
    var pay by remember { mutableStateOf<Staff?>(null) }
    Toolbar { Field("Month (yyyy-mm)", month, { month = it }, Modifier.width(180.dp)); Text("Paid: ${Money.rs(paid.sumOf { it.long("net") })}", fontWeight = FontWeight.SemiBold) }
    DataTable(staff, listOf(C("Name", 1.6f) { it.name }, C("Salary", 1f, true) { plain(it.salary) }, C("Commission", 1f, true) { plain(StaffBook.commission(it.id, month)) },
        C("Advance owed", 1f, true) { plain(StaffBook.advanceBalance(it.id)) },
        C("Status", 1.2f, content = { s -> val p = paid.firstOrNull { it.long("staff_id") == s.id }; if (p != null) Chip("Paid ${plain(p.long("net"))}", MaterialTheme.colorScheme.tertiary) else TextButton({ pay = s }) { BtnText("Pay salary") } })),
        Modifier.fillMaxSize())
    pay?.let { s ->
        var basic by remember { mutableStateOf<Long?>(s.salary) }; var comm by remember { mutableStateOf<Long?>(StaffBook.commission(s.id, month)) }
        var advDed by remember { mutableStateOf<Long?>(minOf(StaffBook.advanceBalance(s.id), s.salary)) }; var other by remember { mutableStateOf<Long?>(0) }; var method by remember { mutableStateOf("Cash") }
        val net = (basic ?: 0) + (comm ?: 0) - (advDed ?: 0) - (other ?: 0)
        FormDialog("Salary $month — ${s.name}", { pay = null }, { if (App.act("Salary paid") { StaffBook.paySalary(s.id, month, basic ?: 0, comm ?: 0, advDed ?: 0, other ?: 0, method, Dates.today()) }) pay = null }, saveText = "Pay ${Money.rs(net)}") {
            MoneyField("Basic salary", basic, { basic = it }); MoneyField("Commission", comm, { comm = it })
            MoneyField("Advance deducted (owes ${plain(StaffBook.advanceBalance(s.id))})", advDed, { advDed = it }); MoneyField("Other deduction (absence, fine)", other, { other = it })
            Dropdown("Paid by", Defaults.payMethods, method, { method = it }); Text("Net pay: ${Money.rs(net)}", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun AttendanceTab() {
    var month by remember { mutableStateOf(Dates.today().take(7)) }
    val rows = remember(App.version, month) { StaffBook.attendance(month) }
    val staff = remember(App.version) { StaffBook.list() }
    var mark by remember { mutableStateOf(false) }
    Toolbar {
        Field("Month (yyyy-mm)", month, { month = it }, Modifier.width(180.dp))
        Spacer(Modifier.weight(1f))
        OutlinedButton({ FilePick.open("Attendance log from the fingerprint machine", listOf(".csv", ".txt", ".dat"))?.let { f -> App.act { App.toast("Imported ${StaffBook.importAttendance(f)} punches") } } }) { IconText(Icons.Default.Fingerprint, "Import machine log") }
        Button({ mark = true }) { Text("Mark by hand") }
    }
    DataTable(rows, listOf(C("Date", 1f) { Dates.show(it.str("date")) }, C("Name", 2f) { it.str("name") }, C("In", 1f) { it.str("time_in") }, C("Out", 1f) { it.str("time_out") }),
        Modifier.fillMaxSize(), empty = "No attendance this month. Import the machine's log (each line: user ID and date-time) or mark by hand.")
    if (mark) {
        var s by remember { mutableStateOf(staff.firstOrNull()) }; var d by remember { mutableStateOf(Dates.today()) }; var tin by remember { mutableStateOf("09:00") }; var tout by remember { mutableStateOf("18:00") }
        FormDialog("Mark attendance", { mark = false }, { if (App.act("Saved") { StaffBook.markAttendance(s?.id ?: return@act, d, tin, tout) }) mark = false }) {
            Dropdown("Staff", staff, s, { s = it }, text = { it.name }); DateField("Date", d, { d = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Field("In", tin, { tin = it }, Modifier.weight(1f)); Field("Out", tout, { tout = it }, Modifier.weight(1f)) }
        }
    }
}

// ---------------- reports ----------------

private data class ReportDef(val group: String, val name: String, val build: (String, String) -> Table)

private val reportDefs = listOf(
    ReportDef("Sales", "Sales by day") { f, t -> Reports.sales(f, t) },
    ReportDef("Sales", "Money received by method") { f, t -> Reports.salesByMethod(f, t) },
    ReportDef("Profit", "Profit per bill") { f, t -> Reports.profitByInvoice(f, t) },
    ReportDef("Profit", "Profit by item or service") { f, t -> Reports.profitByService(f, t) },
    ReportDef("Stock", "Stock value and movement") { _, _ -> Reports.stock() },
    ReportDef("Stock", "Dead stock (180 days)") { _, _ -> Reports.deadStock() },
    ReportDef("Money owed", "Receivables ageing") { _, t -> Billing.ageing(t).let { a -> Table("Who owes what (ageing)", listOf("Customer", "Phone", "Current", "1-30 days", "31-60", "61-90", "Over 90", "Total"),
        a.map { listOf(it.name, it.phone, it.current, it.d30, it.d60, it.d90, it.over90, it.total) }, setOf(2, 3, 4, 5, 6, 7)) } },
    ReportDef("Money owed", "Supplier balances") { _, t -> Table("What we owe suppliers", listOf("Supplier", "Phone", "Balance"),
        Suppliers.list().map { listOf(it.name, it.phone, Ledger.balance(Accounts.sys("AP"), to = t, partyType = "S", partyId = it.id)) }.filter { (it[2] as Long) != 0L }, setOf(2)) },
    ReportDef("Workshop", "Technician performance") { f, t -> Reports.technicians(f, t) },
    ReportDef("Workshop", "Most-requested services") { f, t -> Reports.services(f, t) },
    ReportDef("Customers", "Top customers") { f, t -> Reports.customers(f, t) },
    ReportDef("Customers", "Customers not seen in 3 months") { _, _ -> Reports.lapsedCustomers() },
    ReportDef("Customers", "Vehicles by make") { _, _ -> Reports.vehiclesByMake() },
    ReportDef("Purchases", "Purchases by supplier") { f, t -> Reports.purchases(f, t) },
    ReportDef("Purchases", "Purchase price changes") { f, t -> Reports.priceChanges(f, t) },
    ReportDef("Accounts", "Spending by category") { f, t -> Reports.expenses(f, t) },
    ReportDef("Accounts", "Sales tax for returns") { f, t -> Reports.tax(f, t) },
    ReportDef("Control", "Change log") { f, t -> Reports.auditLog(f, t) },
)

@Composable
fun ReportsScreen() {
    var sel by remember { mutableStateOf(reportDefs.first()) }
    var from by remember { mutableStateOf(Dates.monthStart()) }
    var to by remember { mutableStateOf(Dates.today()) }
    val table = remember(sel, from, to, App.version) { try { sel.build(from, to) } catch (e: Exception) { Table("Error: ${e.message}", listOf(""), emptyList()) } }
    val period = "${Dates.show(from)} to ${Dates.show(to)}"
    Page("Reports", actions = {
        OutlinedButton({ App.act { Printers.open(Documents.table(table, period)) } }) { IconText(Icons.Default.PictureAsPdf, "PDF") }
        OutlinedButton({ App.act("Sent to printer") { Printers.a4(Documents.table(table, period)) } }) { IconText(Icons.Default.Print, "Print") }
        OutlinedButton({ App.act { Printers.open(Excel.exportFile(table)) } }) { IconText(Icons.Default.TableView, "Excel") }
    }) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.width(270.dp).tourAnchor("reports.list").verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                reportDefs.groupBy { it.group }.forEach { (g, defs) ->
                    Text(g, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                    defs.forEach { d -> NavigationDrawerItem({ Text(d.name, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }, sel == d, { sel = d }, Modifier.height(38.dp)) }
                }
            }
            Column(Modifier.weight(1f)) {
                Toolbar {
                    DateField("From", from, { from = it }); DateField("To", to, { to = it })
                    TextButton({ from = Dates.today(); to = Dates.today() }) { Text("Today") }
                    TextButton({ from = Dates.monthStart(); to = Dates.today() }) { Text("This month") }
                    TextButton({ from = Dates.fiscalStart(Dates.today()); to = Dates.today() }) { Text("This fiscal year") }
                }
                Text(table.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                val cols = table.columns.mapIndexed { i, c -> C<List<Any?>>(c, if (i in table.money || i in table.qty) 1f else if (i == 0) 1.6f else 1.2f, i in table.money || i in table.qty) { r -> Documents.cell(table, i, r.getOrNull(i)) } }
                val footer = if (table.totals && table.money.isNotEmpty()) table.columns.indices.map { i -> if (i == 0) "Total" else if (i in table.money) plain(Money.roundToRupee(table.rows.sumOf { (it[i] as? Long) ?: 0L })) else "" } else null
                DataTable(table.rows, cols, Modifier.fillMaxSize(), empty = "No data for these dates", footer = footer)
            }
        }
    }
}
