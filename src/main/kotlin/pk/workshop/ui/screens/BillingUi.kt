package pk.workshop.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import pk.workshop.core.Dates
import pk.workshop.core.Money
import pk.workshop.core.Pct
import pk.workshop.core.Qty
import pk.workshop.data.*
import pk.workshop.db.fail
import pk.workshop.print.Documents
import pk.workshop.print.Printers
import pk.workshop.print.WhatsApp
import pk.workshop.ui.*
import pk.workshop.ui.Button

@Composable
fun BookingsScreen() {
    var from by remember { mutableStateOf(Dates.today()) }
    var to by remember { mutableStateOf(Dates.plusDays(Dates.today(), 7)) }
    var edit by remember { mutableStateOf<Booking?>(null) }
    var toJob by remember { mutableStateOf<Booking?>(null) }
    val list = remember(App.version, from, to) { Bookings.list(from, to) }
    Page("Bookings", actions = { Button({ edit = Booking(whenAt = Dates.plusDays(Dates.today(), 1) + "T10:00") }) { IconText(Icons.Default.Add, "New booking") } }) {
        Toolbar { DateField("From", from, { from = it }); DateField("To", to, { to = it }); Text("${list.size} bookings") }
        DataTable(list, listOf(
            C("When", 1.7f) { Dates.show(it.whenAt) }, C("Customer", 1.4f) { "${it.name} ${it.phone}" }, C("Vehicle", 1f) { it.regNo },
            C("Service", 1.5f) { it.service }, C("Pickup", 1.4f) { if (it.pickup) "${it.pickupAddress} (${it.driver})" else "Walk-in" },
            C("Status", 1f, content = { Chip(it.status) }),
            C("", 1.6f, content = { b ->
                Row {
                    if (b.status == "Booked") TextButton({ toJob = b }) { BtnText("Car arrived") }
                    IconButton({ App.act { WhatsApp.send(b.phone, WhatsApp.bookingText(b), "booking", b.id) } }) { Icon(Icons.Default.Chat, "WhatsApp") }
                    if (b.status == "Booked") IconButton({ App.act("Cancelled") { Bookings.setStatus(b.id, "Cancelled") } }) { Icon(Icons.Default.Close, "Cancel") }
                }
            }),
        ), Modifier.fillMaxSize(), onClick = { edit = it }, empty = "No bookings in these dates")
    }
    edit?.let { b0 ->
        var b by remember(b0) { mutableStateOf(b0) }
        var date by remember(b0) { mutableStateOf(b0.whenAt.take(10)) }
        var time by remember(b0) { mutableStateOf(b0.whenAt.drop(11).take(5).ifBlank { "10:00" }) }
        var pickC by remember { mutableStateOf(false) }
        FormDialog(if (b0.id == 0L) "New booking" else "Booking #${b0.id}", { edit = null }, {
            if (App.act("Booking saved") { Bookings.save(b.copy(whenAt = "${date}T$time")) }) edit = null
        }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Existing customer: " + (b.customerId?.let { Customers.get(it).name } ?: "none"), Modifier.weight(1f)); TextButton({ pickC = true }) { Text("Choose") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Name", b.name, { b = b.copy(name = it) }, Modifier.weight(1f)); Field("Phone", b.phone, { b = b.copy(phone = it) }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Field("Registration no", b.regNo, { b = b.copy(regNo = it.uppercase()) }, Modifier.weight(1f))
                DateField("Date", date, { date = it }); Field("Time (24h)", time, { time = it }, Modifier.width(110.dp), placeholder = "10:00")
            }
            Field("Service wanted", b.service, { b = b.copy(service = it) }, Modifier.fillMaxWidth())
            Check("Pickup and drop-off", b.pickup) { b = b.copy(pickup = it) }
            if (b.pickup) {
                Field("Pickup address", b.pickupAddress, { b = b.copy(pickupAddress = it) }, Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("Driver", b.driver, { b = b.copy(driver = it) }, Modifier.weight(1f)); Field("Return time", b.returnAt, { b = b.copy(returnAt = it) }, Modifier.weight(1f))
                }
            }
            Field("Notes", b.notes, { b = b.copy(notes = it) }, Modifier.fillMaxWidth())
        }
        if (pickC) CustomerPicker({ c -> b = b.copy(customerId = c.id, name = c.name, phone = c.phone, regNo = Vehicles.forCustomer(c.id).firstOrNull()?.regNo ?: b.regNo,
            vehicleId = Vehicles.forCustomer(c.id).firstOrNull()?.id); pickC = false }, { pickC = false })
    }
    toJob?.let { b -> NewJobDialog({ toJob = null }, prefill = b) { id -> toJob = null; App.go(Screen.Job(id)) } }
}

@Composable
fun BillingScreen() {
    var tab by rememberTab("Billing")
    Page("Billing", actions = { Button({ App.go(Screen.CounterSale()) }) { IconText(Icons.Default.PointOfSale, "Counter sale") } }) {
        Tabs(listOf("Invoices", "Payments received", "Cheques", "FBR queue"), tab) { tab = it }
        when (tab) { 0 -> InvoicesTab(); 1 -> PaymentsTab(); 2 -> ChequesTab(); 3 -> FbrTab() }
    }
}

@Composable
private fun InvoicesTab() {
    var q by remember { mutableStateOf("") }
    var from by remember { mutableStateOf(Dates.monthStart()) }
    var to by remember { mutableStateOf(Dates.today()) }
    var unpaid by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<Long?>(null) }
    val list = remember(App.version, q, from, to, unpaid) { Billing.list(from, to, q, unpaid) }
    Toolbar {
        SearchBox(q, { q = it }, Modifier.width(260.dp), "Search invoices")
        DateField("From", from, { from = it }); DateField("To", to, { to = it }); Check("Unpaid only", unpaid) { unpaid = it }
        ScanBox({ c -> Billing.byNo(c)?.let { open = it.id } ?: App.toast("No invoice $c") }, Modifier.width(220.dp), "Scan invoice QR")
    }
    DataTable(list, listOf(
        C("No", 1.1f) { it.no }, C("Date", 0.8f) { Dates.show(it.date) }, C("Type", 0.8f) { it.kind }, C("Customer", 1.6f) { it.customer },
        C("Vehicle", 1.3f) { it.vehicle }, C("Total", 0.9f, true) { plain(it.total) }, C("Paid", 0.9f, true) { plain(it.paid) },
        C("Due", 0.9f, true, content = { i -> Text(if (i.kind == "Credit note") "" else plain(i.due), color = if (i.due > 0 && i.kind != "Credit note") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }),
    ), Modifier.fillMaxSize(), onClick = { open = it.id }, empty = "No invoices",
        footer = listOf("${list.size} bills", "", "", "", "", plain(list.sumOf { it.total }), plain(list.sumOf { it.paid }), plain(list.filter { it.kind != "Credit note" }.sumOf { it.due })))
    open?.let { InvoiceDialog(it) { open = null } }
}

@Composable
private fun PaymentsTab() {
    var from by remember { mutableStateOf(Dates.today()) }
    var to by remember { mutableStateOf(Dates.today()) }
    val list = remember(App.version, from, to) { Billing.payments(from, to, "IN") }
    Toolbar { DateField("From", from, { from = it }); DateField("To", to, { to = it }) }
    DataTable(list, listOf(C("No", 1f) { it.no }, C("Date", 0.8f) { Dates.show(it.date) }, C("From", 1.6f) { it.party }, C("Method", 1f) { it.method },
        C("Reference", 1.2f) { it.reference }, C("For", 1f) { it.invoiceNo }, C("Tax withheld", 0.9f, true) { if (it.wht > 0) plain(it.wht) else "" }, C("Amount", 1f, true) { plain(it.amount) }),
        Modifier.fillMaxSize(), footer = listOf("${list.size}", "", "", "", "", "", plain(list.sumOf { it.wht }), plain(list.sumOf { it.amount })))
}

@Composable
private fun ChequesTab() {
    var status by remember { mutableStateOf("In hand") }
    val list = remember(App.version, status) { Billing.cheques(status) }
    Toolbar { listOf("In hand", "Cleared", "Bounced").forEach { s -> FilterChip(status == s, { status = s }, { Text(s) }) } }
    DataTable(list, listOf(C("Cheque date", 1f) { Dates.show(it.str("cheque_date")) }, C("Customer", 1.6f) { it.str("party") }, C("Cheque / bank", 1.5f) { it.str("reference") },
        C("Amount", 1f, true) { plain(it.long("amount")) }, C("Status", 0.9f, content = { Chip(it.str("cheque_status")) }),
        C("", 1.6f, content = { r -> if (r.str("cheque_status") == "In hand") Row {
            TextButton({ App.act("Cheque cleared") { Billing.chequeCleared(r.long("id")) } }) { BtnText("Cleared") }
            TextButton({ App.act("Marked bounced; amount is owed again") { Billing.chequeBounced(r.long("id")) } }) { Text("Bounced", color = MaterialTheme.colorScheme.error) }
        } })), Modifier.fillMaxSize(), empty = "No cheques")
}

@Composable
private fun FbrTab() {
    val list = remember(App.version) { Fbr.pending() }
    var open by remember { mutableStateOf<Long?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!Fbr.enabled()) Text("FBR digital invoicing is off. Turn it on in Settings once your licensed integrator (e.g. PRAL) has set up your account.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Text("Invoices waiting for their FBR number. Enter the number your integrator returns; it prints on the invoice with the QR code.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        DataTable(list, listOf(C("Invoice", 1f) { it.str("inv_no") }, C("Date", 1f) { Dates.show(it.str("date")) }, C("Total", 1f, true) { plain(it.long("total")) }),
            Modifier.fillMaxSize(), onClick = { open = it.long("id") }, empty = "Nothing waiting")
    }
    open?.let { InvoiceDialog(it) { open = null } }
}

// ---------------- counter sale (POS) ----------------

private data class CartLine(val kind: String, val partId: Long?, val description: String, val qty: Long, val rate: Long, val discount: Long, val stock: Long?, val cost: Long)

@Composable
fun CounterSaleScreen(customerId: Long?) {
    var customer by remember { mutableStateOf<Customer?>(customerId?.let { Customers.get(it) }) }
    val cart = remember { mutableStateListOf<CartLine>() }
    var discount by remember { mutableStateOf<Long?>(0) }
    var pickPart by remember { mutableStateOf(false) }
    var pickCust by remember { mutableStateOf(false) }
    var addService by remember { mutableStateOf(false) }
    var payFor by remember { mutableStateOf<Long?>(null) }
    var lastInv by remember { mutableStateOf<Long?>(null) }
    val totals = remember(cart.toList(), discount) {
        try { Billing.compute(cart.map { InvLine(it.kind, it.partId, it.description, it.qty, it.rate, it.discount) }, discount ?: 0, Dates.today()) } catch (e: Exception) { null }
    }
    fun addPart(p: Part, qty: Long = 1000) {
        val i = cart.indexOfFirst { it.partId == p.id }
        if (i >= 0) cart[i] = cart[i].copy(qty = cart[i].qty + qty) else cart += CartLine("PART", p.id, p.name, qty, p.price, 0, p.qty, p.avgCost)
    }
    fun post(): Long? {
        var id: Long? = null
        App.act("Sale saved") {
            if (cart.isEmpty()) fail("Add something to sell.")
            val cid = customer?.id ?: Customers.walkIn()
            id = Billing.post("Counter", Dates.today(), cid, null, null, cart.map { InvLine(it.kind, it.partId, it.description, it.qty, it.rate, it.discount) }, discount ?: 0, "")
        }
        return id
    }
    val showDisplay = Settings.bool(Settings.DISPLAY)

    Page("Counter sale", actions = {
        if (lastInv != null) OutlinedButton({ App.act("Receipt printed") { Printers.receipt(lastInv!!) } }) { IconText(Icons.Default.Receipt, "Reprint last receipt") }
        OutlinedButton({ cart.clear(); discount = 0 }) { Text("Clear") }
    }) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(2f)) {
                Toolbar {
                    ScanBox({ c -> Inventory.byBarcode(c)?.let { addPart(it) } ?: App.toast("Unknown code $c") }, Modifier.weight(1f))
                    OutlinedButton({ pickPart = true }) { IconText(Icons.Default.Search, "Find part") }
                    OutlinedButton({ addService = true }) { IconText(Icons.Default.Build, "Labour / service") }
                }
                DataTable(cart.toList(), listOf(
                    C("Item", 2.5f) { it.description + if (it.stock != null && it.qty > it.stock) "  (only ${Qty.fmt(it.stock)} in stock)" else "" },
                    C("Qty", 1f, content = { l -> CartNumber(Qty.fmt(l.qty)) { t -> Qty.parse(t)?.let { q -> cart[cart.indexOf(l)] = l.copy(qty = q) } } }),
                    C("Rate", 1f, content = { l -> CartNumber(Money.plain(l.rate).replace(",", "")) { t -> Money.parse(t)?.let { r -> cart[cart.indexOf(l)] = l.copy(rate = r) } } }),
                    C("Discount", 1f, content = { l -> CartNumber(if (l.discount == 0L) "" else Money.plain(l.discount).replace(",", "")) { t -> Money.parse(t)?.let { d -> cart[cart.indexOf(l)] = l.copy(discount = d) } } }),
                    C("Amount", 1f, true) { plain(Money.times(it.rate, it.qty) - it.discount) },
                    C("", 0.4f, content = { l -> IconButton({ cart.remove(l) }) { Icon(Icons.Default.Delete, "Remove") } }),
                ), Modifier.fillMaxSize(), empty = "Scan a barcode or find a part to start")
            }
            Section("Bill", Modifier.weight(1f).fillMaxHeight()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(customer?.name ?: "Walk-in customer", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    TextButton({ pickCust = true }) { BtnText("Customer") }
                }
                customer?.let { c -> Text("Owes ${Money.rs(Customers.balance(c.id))} · ${c.points} points", style = MaterialTheme.typography.bodySmall) }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                MoneyField("Discount on whole bill", discount, { discount = it }, Modifier.fillMaxWidth())
                if (totals != null) {
                    Kv("Parts", Money.rs(totals.goodsNet)); Kv("Sales tax ${totals.goodsRate?.let { Pct.fmt(it.rateBp) } ?: ""}", Money.rs(totals.goodsTax))
                    if (totals.servicesNet != 0L) { Kv("Labour", Money.rs(totals.servicesNet)); Kv("Services tax", Money.rs(totals.servicesTax)) }
                    if (totals.rounding != 0L) Kv("Rounding", Money.plain(totals.rounding, true))
                    Spacer(Modifier.height(8.dp))
                    Text(Money.rs(totals.total), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(Money.words(totals.total), style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.weight(1f))
                Button({ post()?.let { id -> lastInv = id; payFor = id } }, Modifier.fillMaxWidth().height(52.dp), enabled = cart.isNotEmpty()) {
                    BtnText("Take payment", fontSize = 18.sp)
                }
                OutlinedButton({ if (customer == null) App.toast("Choose the customer for a credit sale.") else post()?.let { id ->
                    lastInv = id; Customers.creditWarning(customer!!.id, 0)?.let { App.toast(it) }; cart.clear(); discount = 0; App.act { Printers.receipt(id) } } },
                    Modifier.fillMaxWidth().padding(top = 8.dp), enabled = cart.isNotEmpty()) { BtnText("Sell on credit (udhaar)") }
            }
        }
    }
    if (pickPart) PartPicker({ addPart(it); pickPart = false }, { pickPart = false })
    if (pickCust) CustomerPicker({ customer = it; pickCust = false }, { pickCust = false })
    if (addService) {
        var d by remember { mutableStateOf("") }; var r by remember { mutableStateOf<Long?>(0) }
        FormDialog("Add labour or service", { addService = false }, { cart += CartLine("LABOUR", null, d.ifBlank { "Labour" }, 1000, r ?: 0, 0, null, 0); addService = false }, saveText = "Add") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Services.list().forEach { s -> AssistChip({ d = s.name; r = s.price }, { Text(s.name) }) } }
            Field("Description", d, { d = it }, Modifier.fillMaxWidth()); MoneyField("Rate", r, { r = it })
        }
    }
    payFor?.let { id ->
        val inv = Billing.get(id)
        SplitPaymentDialog(inv) {
            payFor = null; cart.clear(); discount = 0
            App.act { Printers.receipt(id) }
        }
    }
    if (showDisplay) CustomerDisplay(cart.map { it.description to Money.times(it.rate, it.qty) - it.discount }, totals?.total ?: 0)
}

@Composable
private fun CartNumber(value: String, onChange: (String) -> Unit) {
    var t by remember(value) { mutableStateOf(value) }
    OutlinedTextField(t, { t = it; onChange(it) }, singleLine = true, colors = fieldColors(), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), modifier = Modifier.width(110.dp).height(52.dp), textStyle = MaterialTheme.typography.bodyMedium)
}

/** Takes one or more payments against a just-made invoice (cash + JazzCash, etc.). */
@Composable
fun SplitPaymentDialog(inv0: Invoice, onDone: () -> Unit) {
    var paidSoFar by remember { mutableStateOf(0L) }
    var method by remember { mutableStateOf("Cash") }
    var amount by remember { mutableStateOf<Long?>(inv0.total) }
    var ref by remember { mutableStateOf("") }
    var tendered by remember { mutableStateOf<Long?>(null) }
    val remaining = inv0.total - paidSoFar
    FormDialog("Payment for ${inv0.no} — ${Money.rs(inv0.total)}", onDone, {
        if (App.act { Billing.receive(inv0.customerId!!, amount ?: fail("Enter a valid amount."), method, ref, Dates.today(), inv0.id) }) {
            paidSoFar += amount ?: 0
            if (inv0.total - paidSoFar <= 0) onDone() else { amount = inv0.total - paidSoFar; ref = ""; method = "Cash" }
        }
    }, saveText = "Receive", extraButtons = { if (paidSoFar > 0) TextButton(onDone) { Text("Leave the rest on account") } }) {
        Text("Remaining: ${Money.rs(remaining)}", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Defaults.payMethods.forEach { m -> FilterChip(method == m, { method = m }, { Text(m) }) } }
        MoneyField("Amount", amount, { amount = it })
        if (method == "Cash") {
            MoneyField("Cash handed over", tendered, { tendered = it })
            if ((tendered ?: 0) > 0) Text("Change: ${Money.rs((tendered ?: 0) - (amount ?: 0))}", fontWeight = FontWeight.SemiBold)
        } else Field(if (method == "Cheque") "Cheque no and bank" else "Transaction ID", ref, { ref = it }, Modifier.fillMaxWidth())
        Text("For a split payment, enter the first part (e.g. cash), then the rest by another method.", style = MaterialTheme.typography.bodySmall)
    }
}

/** Second window for a customer-facing screen at the counter. */
@Composable
private fun CustomerDisplay(items: List<Pair<String, Long>>, total: Long) {
    Window(onCloseRequest = {}, title = "Customer display", state = rememberWindowState(width = 640.dp, height = 480.dp)) {
        AppTheme("dark") {
            Surface(Modifier.fillMaxSize().carbonBackground(), color = androidx.compose.ui.graphics.Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground) {
                Column(Modifier.padding(32.dp)) {
                    Text(Settings.get(Settings.BUSINESS), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    items.takeLast(8).forEach { (d, a) -> Row { Text(d, Modifier.weight(1f), fontSize = 20.sp); Text(Money.plain(a), fontSize = 20.sp) } }
                    Spacer(Modifier.weight(1f))
                    Text("Total  ${Money.rs(total)}", fontSize = 44.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
