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
import pk.workshop.core.Dates
import pk.workshop.core.Money
import pk.workshop.core.Qty
import pk.workshop.data.*
import pk.workshop.db.fail
import pk.workshop.print.Documents
import pk.workshop.print.Printers
import pk.workshop.print.WhatsApp
import pk.workshop.ui.*
import pk.workshop.ui.Button

// ---------------- customers & vehicles ----------------

@Composable
fun CustomerForm(initial: Customer, onDismiss: () -> Unit, onSaved: (Long) -> Unit) {
    var c by remember { mutableStateOf(initial) }
    FormDialog(if (initial.id == 0L) "New customer" else "Edit customer", onDismiss, {
        var id = 0L
        if (App.act("Customer saved") { id = Customers.save(c) }) onSaved(id)
    }) {
        Field("Name", c.name, { c = c.copy(name = it) }, Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Mobile (WhatsApp)", c.phone, { c = c.copy(phone = it) }, Modifier.weight(1f), placeholder = "0300-1234567")
            Field("Second number", c.phone2, { c = c.copy(phone2 = it) }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dropdown("Type", Defaults.customerTypes, c.type, { c = c.copy(type = it) }, Modifier.weight(1f))
            Field("CNIC (optional)", c.cnic, { c = c.copy(cnic = it) }, Modifier.weight(1f), placeholder = "35202-1234567-1")
        }
        Field("Address", c.address, { c = c.copy(address = it) }, Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("City", c.city, { c = c.copy(city = it) }, Modifier.weight(1f))
            Field("NTN (companies)", c.ntn, { c = c.copy(ntn = it) }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MoneyField("Credit limit (0 = none)", c.creditLimit, { c = c.copy(creditLimit = it ?: 0) }, Modifier.weight(1f))
            Field("Payment terms (days)", c.termsDays.toString(), { c = c.copy(termsDays = it.toIntOrNull() ?: 0) }, Modifier.weight(1f), numeric = true)
        }
        Field("Notes", c.notes, { c = c.copy(notes = it) }, Modifier.fillMaxWidth(), singleLine = false, minLines = 2)
        if (initial.id != 0L) Check("Active", c.active) { c = c.copy(active = it) }
    }
}

@Composable
fun VehicleForm(initial: Vehicle, onDismiss: () -> Unit, onSaved: (Long) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    var pickOwner by remember { mutableStateOf(false) }
    FormDialog(if (initial.id == 0L) "New vehicle" else "Edit vehicle", onDismiss, {
        var id = 0L
        if (App.act("Vehicle saved") { id = Vehicles.save(v) }) onSaved(id)
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Owner: " + (if (v.customerId != 0L) Customers.get(v.customerId).name else "not chosen"), Modifier.weight(1f))
            TextButton({ pickOwner = true }) { Text(if (initial.id == 0L) "Choose owner" else "Transfer to new owner") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Registration no", v.regNo, { v = v.copy(regNo = it.uppercase()) }, Modifier.weight(1f), placeholder = "LEA-1234")
            Dropdown("Make", Defaults.makes, v.make.ifBlank { null }, { v = v.copy(make = it) }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Model", v.model, { v = v.copy(model = it) }, Modifier.weight(1f), placeholder = "Corolla")
            Field("Variant", v.variant, { v = v.copy(variant = it) }, Modifier.weight(1f))
            Field("Year", v.year, { v = v.copy(year = it) }, Modifier.weight(0.6f), numeric = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Colour", v.color, { v = v.copy(color = it) }, Modifier.weight(1f))
            Dropdown("Fuel", Defaults.fuels, v.fuel, { v = v.copy(fuel = it) }, Modifier.weight(1f))
            Dropdown("Transmission", listOf("Manual", "Automatic"), v.transmission, { v = v.copy(transmission = it) }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Engine no", v.engineNo, { v = v.copy(engineNo = it) }, Modifier.weight(1f))
            Field("Chassis no", v.chassisNo, { v = v.copy(chassisNo = it) }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Odometer (km)", v.odometer.toString(), { v = v.copy(odometer = it.toLongOrNull() ?: 0) }, Modifier.weight(1f), numeric = true)
            Field("Next service at (km)", v.nextServiceKm?.toString() ?: "", { v = v.copy(nextServiceKm = it.toLongOrNull()) }, Modifier.weight(1f), numeric = true)
            DateField("Next service date", v.nextServiceDate ?: "", { v = v.copy(nextServiceDate = it) })
        }
    }
    if (pickOwner) CustomerPicker({ v = v.copy(customerId = it.id); pickOwner = false }, { pickOwner = false })
}

@Composable
fun CustomerPicker(onPick: (Customer) -> Unit, onDismiss: () -> Unit) {
    var creating by remember { mutableStateOf<String?>(null) }
    Picker("Choose customer", { Customers.list(it).filter { c -> c.active } }, { it.name }, { listOf(it.phone, it.type).filter { s -> s.isNotBlank() }.joinToString(" · ") },
        onPick, onDismiss, "New customer") { creating = it }
    creating?.let { name -> CustomerForm(Customer(name = name), { creating = null }) { id -> creating = null; onPick(Customers.get(id)) } }
}

@Composable
fun PartPicker(onPick: (Part) -> Unit, onDismiss: () -> Unit) {
    Picker("Choose part", { Inventory.list(it) }, { "${it.name}  —  ${Money.rs(it.price)}" },
        { listOf(it.partNo, it.brand, it.grade, "Stock ${Qty.fmt(it.qty)} ${it.unit}", it.location).filter { s -> s.isNotBlank() }.joinToString(" · ") }, onPick, onDismiss)
}

/** Finds a vehicle by registration; offers to create customer + vehicle when new. */
@Composable
fun VehiclePicker(onPick: (Vehicle) -> Unit, onDismiss: () -> Unit) {
    var newReg by remember { mutableStateOf<String?>(null) }
    Picker("Find vehicle (registration, chassis, owner or phone)", { Vehicles.search(it) }, { it.label }, { "${it.ownerName} · ${it.ownerPhone}" },
        onPick, onDismiss, "New customer and vehicle") { newReg = it }
    newReg?.let { reg -> NewCustomerVehicle(reg, { newReg = null }) { id -> newReg = null; onPick(Vehicles.get(id)) } }
}

@Composable
fun NewCustomerVehicle(reg: String, onDismiss: () -> Unit, onSaved: (Long) -> Unit) {
    var c by remember { mutableStateOf(Customer(type = "Regular")) }
    var v by remember { mutableStateOf(Vehicle(regNo = reg.uppercase())) }
    FormDialog("New customer and vehicle", onDismiss, {
        var vid = 0L
        if (App.act("Saved") { pk.workshop.db.Db.tx { val cid = Customers.save(c); vid = Vehicles.save(v.copy(customerId = cid)) } }) onSaved(vid)
    }) {
        Text("Customer", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Name", c.name, { c = c.copy(name = it) }, Modifier.weight(1f))
            Field("Mobile", c.phone, { c = c.copy(phone = it) }, Modifier.weight(1f), placeholder = "0300-1234567")
        }
        Text("Vehicle", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Registration no", v.regNo, { v = v.copy(regNo = it.uppercase()) }, Modifier.weight(1f))
            Dropdown("Make", Defaults.makes, v.make.ifBlank { null }, { v = v.copy(make = it) }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Model", v.model, { v = v.copy(model = it) }, Modifier.weight(1f))
            Field("Year", v.year, { v = v.copy(year = it) }, Modifier.weight(1f), numeric = true)
            Dropdown("Fuel", Defaults.fuels, v.fuel, { v = v.copy(fuel = it) }, Modifier.weight(1f))
        }
    }
}

// ---------------- payments ----------------

@Composable
fun ReceivePaymentDialog(customerId: Long, invoiceId: Long?, suggested: Long, onDismiss: () -> Unit, onDone: () -> Unit = {}) {
    val cust = remember { Customers.get(customerId) }
    var method by remember { mutableStateOf("Cash") }
    var amount by remember { mutableStateOf<Long?>(suggested) }
    var wht by remember { mutableStateOf<Long?>(0) }
    var ref by remember { mutableStateOf("") }
    var chq by remember { mutableStateOf(Dates.today()) }
    var date by remember { mutableStateOf(Dates.today()) }
    var tendered by remember { mutableStateOf<Long?>(null) }
    FormDialog("Receive payment — ${cust.name}", onDismiss, {
        if (App.act("Payment received") { Billing.receive(customerId, amount ?: fail("Enter a valid amount."), method, ref, date, invoiceId, wht ?: 0, if (method == "Cheque") chq else null) }) { onDone(); onDismiss() }
    }, saveText = "Receive") {
        Text("Balance owed now: ${Money.rs(Customers.balance(customerId))}" + if (method == "Loyalty points") " · points: ${cust.points}" else "")
        Dropdown("Method", Defaults.payMethods + "Loyalty points", method, { method = it })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MoneyField("Amount", amount, { amount = it }, Modifier.weight(1f))
            DateField("Date", date, { date = it })
        }
        if (method == "Cash") {
            MoneyField("Cash handed over (to work out change)", tendered, { tendered = it }, Modifier.fillMaxWidth())
            val change = (tendered ?: 0) - (amount ?: 0)
            if ((tendered ?: 0) > 0) Text(if (change >= 0) "Change to give: ${Money.rs(change)}" else "Short by ${Money.rs(-change)}", fontWeight = FontWeight.SemiBold)
        }
        if (method != "Cash" && method != "Loyalty points") Field(if (method == "Cheque") "Cheque no and bank" else "Transaction ID / reference", ref, { ref = it }, Modifier.fillMaxWidth())
        if (method == "Cheque") DateField("Cheque date (post-dated?)", chq, { chq = it })
        if (cust.type == "Company / fleet" || cust.type == "Insurance company") MoneyField("Income tax withheld by the company", wht, { wht = it }, Modifier.fillMaxWidth())
    }
}

// ---------------- invoice view ----------------

@Composable
fun InvoiceDialog(invoiceId: Long, onDismiss: () -> Unit) {
    val inv = remember(App.version) { Billing.get(invoiceId) }
    val lines = remember(App.version) { Billing.lines(invoiceId) }
    val pays = remember(App.version) { Billing.invoicePayments(invoiceId) }
    var pay by remember { mutableStateOf(false) }
    var credit by remember { mutableStateOf(false) }
    var fbr by remember { mutableStateOf(false) }
    FormDialog("${inv.kind} ${inv.no}", onDismiss, null, width = 860.dp, extraButtons = {
        if (inv.kind != "Credit note" && inv.customerId != null) {
            if (inv.due > 0) Button({ pay = true }) { Text("Receive payment") }
            OutlinedButton({ credit = true }) { Text("Credit note / return") }
        }
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f)) {
                Kv("Date", Dates.show(inv.date)); Kv("Customer", "${inv.customer} ${inv.customerPhone}")
                if (inv.vehicle.isNotBlank()) Kv("Vehicle", inv.vehicle)
                if (inv.jobNo.isNotBlank()) Kv("Job card", inv.jobNo)
                if (inv.fbrNo.isNotBlank()) Kv("FBR invoice no", inv.fbrNo)
                if (inv.notes.isNotBlank()) Kv("Notes", inv.notes)
            }
            Column(Modifier.weight(1f)) {
                Kv("Parts", Money.rs(inv.goodsNet)); Kv("Sales tax (FBR)", Money.rs(inv.goodsTax)); Kv("Labour & services", Money.rs(inv.servicesNet))
                Kv("Services tax", Money.rs(inv.servicesTax)); if (inv.discount != 0L) Kv("Discount given", Money.rs(inv.discount))
                Kv("Total", Money.rs(inv.total), bold = true)
                if (inv.insurerShare > 0) {
                    Kv("Insurer pays", Money.rs(inv.insurerShare) + (inv.insurerId?.let { " (" + Customers.get(it).name + ")" } ?: ""))
                    Kv("Customer pays", Money.rs(inv.total - inv.insurerShare))
                }
                if (inv.kind != "Credit note") { Kv("Paid", Money.rs(inv.paid)); Kv("Balance due", Money.rs(inv.due), bold = true) }
            }
        }
        DataTable(lines, listOf(
            C("Type", 0.6f) { it.kind.lowercase() }, C("Description", 2.4f) { it.description }, C("Qty", 0.6f, true) { Qty.fmt(it.qty) },
            C("Rate", 0.9f, true) { plain(it.rate) }, C("Disc.", 0.8f, true) { plain(it.discount) }, C("Tax", 0.8f, true) { plain(it.tax) },
            C("Amount", 1f, true) { plain(it.net + it.tax) }), Modifier.heightIn(max = 260.dp))
        if (pays.isNotEmpty()) {
            Text("Payments", style = MaterialTheme.typography.titleSmall)
            pays.forEach { Text("${Dates.show(it.str("date"))} · ${it.str("pay_no")} · ${it.str("method")} ${it.str("reference")} · ${Money.rs(it.long("amount"))}") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ App.act { Printers.open(Documents.invoice(invoiceId)) } }) { IconText(Icons.Default.PictureAsPdf, "Open A4 PDF") }
            OutlinedButton({ App.act("Sent to printer") { Printers.a4(Documents.invoice(invoiceId)) } }) { IconText(Icons.Default.Print, "Print A4") }
            OutlinedButton({ App.act("Receipt printed") { Printers.receipt(invoiceId) } }) { IconText(Icons.Default.Receipt, "Print receipt") }
            OutlinedButton({ App.act { val f = Documents.invoice(invoiceId); Printers.openFolder(f); WhatsApp.send(inv.customerPhone, WhatsApp.invoiceText(inv), "invoice", invoiceId) } }) {
                IconText(Icons.Default.Chat, "WhatsApp")
            }
            if (Fbr.enabled()) OutlinedButton({ fbr = true }) { Text("FBR number") }
        }
    }
    if (pay && inv.customerId != null) ReceivePaymentDialog(inv.customerId, invoiceId, inv.due, { pay = false })
    if (credit) CreditNoteDialog(invoiceId, { credit = false })
    if (fbr) {
        var n by remember { mutableStateOf(inv.fbrNo.takeIf { it != "PENDING" } ?: "") }
        FormDialog("FBR invoice number", { fbr = false }, { if (App.act("Saved") { Fbr.setNumber(invoiceId, n) }) fbr = false }) {
            Text("Enter the number your licensed integrator (e.g. PRAL) returned for ${inv.no}. It prints on the invoice with its QR code.")
            Field("FBR invoice number", n, { n = it }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun CreditNoteDialog(invoiceId: Long, onDismiss: () -> Unit) {
    val lines = remember { Billing.lines(invoiceId) }
    val qty = remember { mutableStateMapOf<Int, Long>() }
    var reason by remember { mutableStateOf("") }
    FormDialog("Credit note for ${Billing.get(invoiceId).no}", onDismiss, {
        if (App.act("Credit note issued") { Billing.creditNote(invoiceId, qty.toMap(), reason) }) onDismiss()
    }, saveText = "Issue credit note", width = 720.dp) {
        Text("Enter what is being returned or corrected. Returned parts go back into stock.")
        lines.forEachIndexed { i, l ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${l.description} (billed ${Qty.fmt(l.qty)} × ${plain(l.rate)})", Modifier.weight(1f))
                QtyField("Credit qty", qty[i] ?: 0, { qty[i] = it ?: 0 }, Modifier.width(140.dp))
                TextButton({ qty[i] = l.qty }) { Text("All") }
            }
        }
        Field("Reason", reason, { reason = it }, Modifier.fillMaxWidth())
    }
}
