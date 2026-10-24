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
import pk.workshop.db.Db
import pk.workshop.print.Documents
import pk.workshop.print.Printers
import pk.workshop.print.WhatsApp
import pk.workshop.ui.*
import pk.workshop.ui.Button

@Composable
fun CustomersScreen() {
    var q by remember { mutableStateOf("") }
    var type by remember { mutableStateOf<String?>(null) }
    var add by remember { mutableStateOf(false) }
    val list = remember(App.version, q, type) { Customers.list(q, type) }
    val balances = remember(App.version) {
        Db.query("SELECT party_id, sum(debit)-sum(credit) b FROM voucher_lines WHERE party_type='C' AND account_id=? GROUP BY party_id", Accounts.sys("AR"))
            .associate { it.long("party_id") to it.long("b") }
    }
    Page("Customers", actions = {
        OutlinedButton({ App.act { Printers.open(pk.workshop.io.Excel.exportFile(Table("Customers", listOf("Name", "Phone", "Type", "CNIC", "Address", "City", "NTN", "Owes"),
            list.map { listOf(it.name, it.phone, it.type, it.cnic, it.address, it.city, it.ntn, balances[it.id] ?: 0L) }, setOf(7)))) } }) { IconText(Icons.Default.TableView, "Export") }
        Button({ add = true }) { IconText(Icons.Default.PersonAdd, "New customer") }
    }) {
        Toolbar { SearchBox(q, { q = it }, Modifier.width(360.dp), "Name, phone, CNIC, reg no or chassis") }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 12.dp)) {
            FilterChip(type == null, { type = null }, { Text("All") })
            Defaults.customerTypes.forEach { t -> FilterChip(type == t, { type = if (type == t) null else t }, { Text(t) }) }
        }
        DataTable(list, listOf(
            C("Name", 1.6f) { it.name + if (!it.active) " (inactive)" else "" }, C("Phone", 1.1f) { it.phone }, C("Type", 1.1f) { it.type }, C("City", 0.9f) { it.city },
            C("Credit limit", 0.9f, true) { if (it.creditLimit > 0) plain(it.creditLimit) else "" }, C("Points", 0.6f, true) { if (it.points > 0) it.points.toString() else "" },
            C("Owes", 1f, true, content = { c -> val b = balances[c.id] ?: 0; Text(if (b != 0L) plain(b) else "", color = if (b > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium) }),
        ), Modifier.fillMaxSize(), onClick = { App.go(Screen.Customer(it.id)) }, empty = "No customers")
    }
    if (add) CustomerForm(Customer(type = "Regular"), { add = false }) { id -> add = false; App.go(Screen.Customer(id)) }
}

@Composable
fun CustomerScreen(id: Long) {
    val c = remember(App.version, id) { Customers.get(id) }
    val vehicles = remember(App.version, id) { Vehicles.forCustomer(id) }
    val invoices = remember(App.version, id) { Billing.list("0000-00-00", "9999-12-31", customerId = id) }
    val balance = remember(App.version, id) { Customers.balance(id) }
    var edit by remember { mutableStateOf(false) }
    var addVehicle by remember { mutableStateOf(false) }
    var editVehicle by remember { mutableStateOf<Vehicle?>(null) }
    var history by remember { mutableStateOf<Vehicle?>(null) }
    var pay by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf(false) }
    var stmt by remember { mutableStateOf(false) }
    var openInv by remember { mutableStateOf<Long?>(null) }
    var newJob by remember { mutableStateOf(false) }
    Page(c.name, back = true, actions = {
        OutlinedButton({ edit = true }) { IconText(Icons.Default.Edit, "Edit") }
        OutlinedButton({ stmt = true }) { IconText(Icons.Default.Description, "Statement") }
        if (balance > 0) OutlinedButton({ App.act { WhatsApp.send(c.phone, WhatsApp.paymentReminderText(c.name, balance), "customer", id) } }) { IconText(Icons.Default.Chat, "Payment reminder") }
        OutlinedButton({ App.go(Screen.CounterSale(id)) }) { IconText(Icons.Default.PointOfSale, "Sell parts") }
        Button({ pay = true }) { IconText(Icons.Default.Payments, "Receive payment") }
    }) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Stat(if (balance >= 0) "Owes us" else "Credit with us", Money.rs(kotlin.math.abs(balance)), Modifier.weight(1f), accent = balance > 0)
                    Stat("Loyalty points", c.points.toString(), Modifier.weight(1f))
                }
                Section("Details") {
                    Kv("Phone", listOf(c.phone, c.phone2).filter { it.isNotBlank() }.joinToString(", ")); Kv("Type", c.type)
                    if (c.cnic.isNotBlank()) Kv("CNIC", c.cnic); if (c.ntn.isNotBlank()) Kv("NTN", c.ntn)
                    Kv("Address", listOf(c.address, c.city).filter { it.isNotBlank() }.joinToString(", "))
                    if (c.creditLimit > 0) Kv("Credit limit", Money.rs(c.creditLimit)); if (c.termsDays > 0) Kv("Payment terms", "${c.termsDays} days")
                    if (c.notes.isNotBlank()) Kv("Notes", c.notes)
                    TextButton({ opening = true }) { Text("Enter opening balance (go-live)") }
                }
                Section("Vehicles", actions = { TextButton({ addVehicle = true }) { IconText(Icons.Default.Add, "Add") } }) {
                    if (vehicles.isEmpty()) Empty("No vehicles")
                    vehicles.forEach { v ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(v.label, fontWeight = FontWeight.SemiBold)
                                Text("Odometer ${"%,d".format(v.odometer)} km" + (v.nextServiceDate?.let { " · next service ${Dates.show(it)}" } ?: ""), style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton({ history = v }) { Text("History") }
                            TextButton({ editVehicle = v }) { Text("Edit") }
                        }
                    }
                    if (vehicles.isNotEmpty()) Button({ newJob = true }, Modifier.padding(top = 8.dp)) { IconText(Icons.Default.Build, "New job card") }
                }
            }
            Section("Bills", Modifier.weight(1.5f).fillMaxHeight()) {
                DataTable(invoices, listOf(C("No", 1.2f) { it.no }, C("Date", 0.9f) { Dates.show(it.date) }, C("Vehicle", 1.3f) { it.vehicle },
                    C("Total", 1f, true) { plain(it.total) }, C("Due", 1f, true) { if (it.kind == "Credit note") "" else plain(it.due) }),
                    Modifier.fillMaxSize(), onClick = { openInv = it.id }, empty = "No bills yet")
            }
        }
    }
    if (edit) CustomerForm(c, { edit = false }) { edit = false }
    if (addVehicle) VehicleForm(Vehicle(customerId = id), { addVehicle = false }) { addVehicle = false }
    editVehicle?.let { v -> VehicleForm(v, { editVehicle = null }) { editVehicle = null } }
    history?.let { v ->
        val rows = remember(v) { Vehicles.history(v.id) }
        FormDialog("Service history — ${v.label}", { history = null }, null, width = 900.dp) {
            DataTable(rows, listOf(C("Job", 1f) { it.str("job_no") }, C("Date", 1f) { Dates.show(it.str("date_in")) }, C("Km", 0.7f, true) { "%,d".format(it.long("odometer")) },
                C("Complaint", 1.5f) { it.str("complaint") }, C("Work done", 2.5f) { it.str("work") }, C("Bill", 0.8f, true) { plain(it.long("total")) }),
                Modifier.heightIn(max = 500.dp), onClick = { r -> history = null; App.go(Screen.Job(r.long("id"))) })
            OutlinedButton({ App.act("Sticker printed") { Printers.label(Documents.serviceSticker(v.id)) } }) { Text("Print service sticker") }
        }
    }
    if (pay) ReceivePaymentDialog(id, null, maxOf(balance, 0), { pay = false })
    if (opening) {
        var amt by remember { mutableStateOf<Long?>(0) }
        var date by remember { mutableStateOf(Dates.today()) }
        FormDialog("Opening balance", { opening = false }, { if (App.act("Opening balance saved") { Customers.openingBalance(id, amt ?: 0, date) }) opening = false }) {
            Text("What ${c.name} already owed when you started using this app.")
            MoneyField("Amount owed", amt, { amt = it }); DateField("As of", date, { date = it })
        }
    }
    if (stmt) StatementDialog("C", id) { stmt = false }
    openInv?.let { InvoiceDialog(it) { openInv = null } }
    if (newJob) NewJobDialog({ newJob = false }, prefill = Booking(customerId = id, vehicleId = vehicles.firstOrNull()?.id, name = c.name, whenAt = Dates.now())) { j ->
        newJob = false; App.go(Screen.Job(j))
    }
}

@Composable
fun StatementDialog(partyType: String, partyId: Long, onDismiss: () -> Unit) {
    var from by remember { mutableStateOf(Dates.fiscalStart(Dates.today())) }
    var to by remember { mutableStateOf(Dates.today()) }
    val acct = Accounts.sys(if (partyType == "C") "AR" else "AP")
    val data = remember(from, to, App.version) { Ledger.ledger(acct, from, to, partyType, partyId) }
    val name = Ledger.partyName(partyType, partyId)
    val phone = if (partyType == "C") Customers.get(partyId).phone else Suppliers.get(partyId).phone
    FormDialog("Statement — $name", onDismiss, null, width = 900.dp, extraButtons = {
        OutlinedButton({ App.act { Printers.open(Documents.statement(partyType, partyId, from, to)) } }) { Text("PDF") }
        OutlinedButton({ App.act { val f = Documents.statement(partyType, partyId, from, to); Printers.openFolder(f)
            WhatsApp.send(phone, "Assalam-o-Alaikum $name, your statement from ${Settings.get(Settings.BUSINESS)} is attached. Balance: ${Money.rs(data.second.lastOrNull()?.balance ?: data.first)}.") } }) { Text("WhatsApp") }
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { DateField("From", from, { from = it }); DateField("To", to, { to = it }) }
        Kv("Opening balance", Money.rs(data.first), bold = true)
        DataTable(data.second, listOf(C("Date", 0.9f) { Dates.show(it.date) }, C("Ref", 1.1f) { it.no }, C("Details", 2.6f) { it.narration },
            C("Debit", 0.9f, true) { if (it.debit > 0) plain(it.debit) else "" }, C("Credit", 0.9f, true) { if (it.credit > 0) plain(it.credit) else "" },
            C("Balance", 1f, true) { plain(it.balance) }), Modifier.heightIn(max = 460.dp))
        Kv("Closing balance", Money.rs(data.second.lastOrNull()?.balance ?: data.first), bold = true)
    }
}
