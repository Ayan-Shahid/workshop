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
import pk.workshop.core.Qty
import pk.workshop.data.*
import pk.workshop.db.fail
import pk.workshop.print.Documents
import pk.workshop.print.Printers
import pk.workshop.print.WhatsApp
import pk.workshop.ui.*

@Composable
fun JobsScreen() {
    var q by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var openOnly by remember { mutableStateOf(true) }
    var newJob by remember { mutableStateOf(false) }
    val jobs = remember(App.version, q, status, openOnly) { Jobs.list(status, q, openOnly && status == null) }
    Page("Job cards", actions = { Button({ newJob = true }) { IconText(Icons.Default.Add, "New job card") } }) {
        Toolbar {
            SearchBox(q, { q = it }, Modifier.width(320.dp), "Search jobs, customers, reg no")
            ScanBox({ code -> Jobs.byNo(code)?.let { App.go(Screen.Job(it.id)) } ?: App.toast("No job card $code") }, Modifier.width(300.dp), "Scan job card QR")
            Check("Open only", openOnly) { openOnly = it }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 12.dp)) {
            FilterChip(status == null, { status = null }, { Text("All") })
            Defaults.jobStatuses.forEach { s -> FilterChip(status == s, { status = if (status == s) null else s }, { Text(s) }) }
        }
        DataTable(jobs, listOf(
            C("Job no", 1.3f) { it.jobNo }, C("In", 0.9f) { Dates.show(it.dateIn.take(10)) }, C("Vehicle", 1.6f) { it.vehicleLabel },
            C("Customer", 1.4f) { "${it.customerName} ${it.customerPhone}" }, C("Department", 0.9f) { it.department },
            C("Priority", 0.7f, content = { j -> if (j.priority == "Urgent") Chip("Urgent", MaterialTheme.colorScheme.error) else Text("Normal") }),
            C("Promised", 1f) { Dates.show(it.promisedAt) }, C("Status", 1.6f, content = { Chip(it.status) }),
        ), Modifier.fillMaxSize(), onClick = { App.go(Screen.Job(it.id)) }, empty = "No job cards")
    }
    if (newJob) NewJobDialog({ newJob = false }) { id -> newJob = false; App.go(Screen.Job(id)) }
}

@Composable
fun NewJobDialog(onDismiss: () -> Unit, prefill: Booking? = null, onCreated: (Long) -> Unit) {
    var vehicle by remember { mutableStateOf<Vehicle?>(prefill?.vehicleId?.let { Vehicles.get(it) } ?: prefill?.regNo?.let { Vehicles.byReg(it) }) }
    var pick by remember { mutableStateOf(vehicle == null) }
    var j by remember { mutableStateOf(JobCard(complaint = prefill?.service ?: "", promisedAt = Dates.plusDays(Dates.today(), 1))) }
    val complaints = remember { Services.complaints() }
    FormDialog("New job card", onDismiss, {
        var id = 0L
        if (App.act("Job card opened") { id = Jobs.create(j.copy(vehicleId = vehicle?.id ?: fail("Choose the vehicle."))); if (prefill != null) Bookings.setStatus(prefill.id, "Arrived", id) }) onCreated(id)
    }, saveText = "Open job card", width = 720.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(vehicle?.let { "${it.label} — ${it.ownerName} ${it.ownerPhone}" } ?: "No vehicle chosen", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            TextButton({ pick = true }) { Text("Change vehicle") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Odometer (km)", if (j.odometer == 0L) "" else j.odometer.toString(), { j = j.copy(odometer = it.filter(Char::isDigit).toLongOrNull() ?: 0) }, Modifier.weight(1f), numeric = true,
                placeholder = vehicle?.odometer?.let { "last: %,d".format(it) })
            Dropdown("Fuel level", listOf("Empty", "Quarter", "Half", "Three quarters", "Full"), j.fuel, { j = j.copy(fuel = it) }, Modifier.weight(1f))
            Dropdown("Priority", listOf("Normal", "Urgent"), j.priority, { j = j.copy(priority = it) }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dropdown("Department", Defaults.departments, j.department, { j = j.copy(department = it) }, Modifier.weight(1f))
            Field("Bay", j.bay, { j = j.copy(bay = it) }, Modifier.weight(0.6f))
            DateField("Promised date", j.promisedAt, { j = j.copy(promisedAt = it) })
        }
        Field("Customer's complaint (in their words)", j.complaint, { j = j.copy(complaint = it) }, Modifier.fillMaxWidth(), singleLine = false, minLines = 2)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            complaints.forEach { (_, t) -> AssistChip({ j = j.copy(complaint = (j.complaint + if (j.complaint.isBlank()) t else ", $t")) }, { Text(t) }) }
        }
        Field("Inspection: dents, scratches, warning lights", j.inspection, { j = j.copy(inspection = it) }, Modifier.fillMaxWidth(), singleLine = false, minLines = 2)
        Field("Items in the car (spare wheel, jack, tools, documents)", j.itemsInCar, { j = j.copy(itemsInCar = it) }, Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Brought by", j.broughtBy, { j = j.copy(broughtBy = it) }, Modifier.weight(1f), placeholder = "Owner, driver…")
            Field("Their phone", j.broughtPhone, { j = j.copy(broughtPhone = it) }, Modifier.weight(1f))
        }
    }
    if (pick) VehiclePicker({ vehicle = it; pick = false; j = j.copy(odometer = 0) }, { pick = false })
}

@Composable
fun JobScreen(id: Long) {
    val j = remember(App.version, id) { Jobs.get(id) }
    val lines = remember(App.version, id) { Jobs.lines(id) }
    val techs = remember(App.version) { Technicians.list() }
    val v = remember(App.version, id) { Vehicles.get(j.vehicleId) }
    val running = remember(App.version, id) { Jobs.runningTimer(id) }
    val atts = remember(App.version, id) { Jobs.attachments(id) }
    val closed = j.invoiceId != null || j.status == "Delivered" || j.status == "Cancelled"
    var edit by remember(id) { mutableStateOf(j) }
    LaunchedEffect(j) { edit = j }
    var addPart by remember { mutableStateOf(false) }
    var addLabour by remember { mutableStateOf<String?>(null) }
    var approve by remember { mutableStateOf(false) }
    var invoice by remember { mutableStateOf(false) }
    var showInv by remember { mutableStateOf(false) }
    var deliver by remember { mutableStateOf(false) }
    var cancel by remember { mutableStateOf(false) }
    var editLine by remember { mutableStateOf<JobLine?>(null) }
    var editVehicle by remember { mutableStateOf(false) }

    Page("${j.jobNo} · ${j.vehicleLabel}", back = true, actions = {
        Chip(j.status)
        if (j.comebackOf != null) Chip("Comeback", MaterialTheme.colorScheme.error)
        if (!closed) Dropdown("Status", Defaults.jobStatuses.filter { it != "Delivered" }, j.status, { s -> App.act("Status: $s") { Jobs.setStatus(id, s) } }, Modifier.width(200.dp))
    }) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // Left: details
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Section("Customer and vehicle", actions = { TextButton({ editVehicle = true }) { Text("Edit vehicle") } }) {
                    Kv("Customer", j.customerName, bold = true); Kv("Phone", j.customerPhone)
                    Kv("Vehicle", v.label); Kv("Chassis", v.chassisNo); Kv("Brought by", "${j.broughtBy} ${j.broughtPhone}")
                    Kv("Checked in", Dates.show(j.dateIn))
                    TextButton({ App.go(Screen.Customer(j.customerId)) }) { Text("Open customer history") }
                }
                Section("Check-in", actions = { if (!closed) Button({ App.act("Saved") { Jobs.update(edit) } }) { Text("Save") } }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field("Odometer", edit.odometer.toString(), { edit = edit.copy(odometer = it.toLongOrNull() ?: 0) }, Modifier.weight(1f), numeric = true)
                        Dropdown("Fuel", listOf("Empty", "Quarter", "Half", "Three quarters", "Full"), edit.fuel, { edit = edit.copy(fuel = it) }, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Dropdown("Department", Defaults.departments, edit.department, { edit = edit.copy(department = it) }, Modifier.weight(1f))
                        Dropdown("Priority", listOf("Normal", "Urgent"), edit.priority, { edit = edit.copy(priority = it) }, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Field("Bay", edit.bay, { edit = edit.copy(bay = it) }, Modifier.weight(1f)); DateField("Promised", edit.promisedAt, { edit = edit.copy(promisedAt = it) })
                    }
                    Field("Complaint", edit.complaint, { edit = edit.copy(complaint = it) }, Modifier.fillMaxWidth(), singleLine = false, minLines = 2)
                    Field("Diagnosis and scan findings (fault codes)", edit.diagnosis, { edit = edit.copy(diagnosis = it) }, Modifier.fillMaxWidth(), singleLine = false, minLines = 2)
                    Field("Inspection", edit.inspection, { edit = edit.copy(inspection = it) }, Modifier.fillMaxWidth(), singleLine = false, minLines = 2)
                    Field("Items in the car", edit.itemsInCar, { edit = edit.copy(itemsInCar = it) }, Modifier.fillMaxWidth())
                    Field("Quality check / road test note", edit.qcNote, { edit = edit.copy(qcNote = it) }, Modifier.fillMaxWidth())
                }
                Section("Photos and scanner reports", actions = {
                    TextButton({ FilePick.open("Attach photo or report")?.let { f -> App.act("Attached") { Jobs.attach(id, f, f.name) } } }) { IconText(Icons.Default.AttachFile, "Attach") }
                }) {
                    if (atts.isEmpty()) Text("Attach check-in photos or the OBD scanner's report (PDF or photo).", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    atts.forEach { a -> TextButton({ App.act { Printers.open(java.io.File(a.str("path"))) } }) { Text(a.str("label")) } }
                }
                Section("Approval") {
                    if (j.approvedBy.isNotBlank()) Text("Approved by ${j.approvedBy} on ${Dates.show(j.approvedAt)}")
                    else Text("Not approved yet. Make an estimate, then record who approved it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // Right: work and actions
            Column(Modifier.weight(1.6f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Section("Work and parts", Modifier.weight(1f), actions = {
                    if (!closed) {
                        OutlinedButton({ addPart = true }) { IconText(Icons.Default.Inventory2, "Part") }
                        OutlinedButton({ addLabour = "LABOUR" }) { IconText(Icons.Default.Build, "Labour") }
                        OutlinedButton({ addLabour = "SUBLET" }) { IconText(Icons.Default.Handshake, "Sublet") }
                    }
                }) {
                    if (!closed) ScanBox({ code ->
                        val p = Inventory.byBarcode(code)
                        if (p == null) App.toast("Unknown code $code") else App.act("Added ${p.name}") { Jobs.addLine(JobLine(jobId = id, kind = "PART", partId = p.id, qty = 1000, rate = p.price)) }
                    }, Modifier.fillMaxWidth().padding(bottom = 8.dp), "Scan a part's barcode to issue it to this job")
                    DataTable(lines, listOf(
                        C("Type", 0.7f) { it.kind.lowercase() + if (it.customerSupplied) " (cust.)" else "" },
                        C("Description", 2.4f) { it.description }, C("Qty", 0.6f, true) { Qty.fmt(it.qty) }, C("Rate", 0.9f, true) { plain(it.rate) },
                        C("Disc.", 0.7f, true) { if (it.discount != 0L) plain(it.discount) else "" }, C("Technician", 1.1f) { it.technician }, C("Amount", 1f, true) { plain(it.net) },
                        C("", 0.4f, content = { l -> if (!closed) IconButton({ App.act("Removed") { Jobs.removeLine(l.id) } }) { Icon(Icons.Default.Delete, "Remove") } }),
                    ), Modifier.fillMaxWidth().weight(1f), onClick = { if (!closed) editLine = it }, empty = "No work added yet",
                        footer = listOf("", "Total before tax", "", "", "", "", plain(lines.sumOf { it.net }), ""))
                }
                Section("Actions") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!closed) {
                            OutlinedButton({ App.act { val e = Jobs.saveEstimate(id); Printers.open(Documents.estimate(e)) } }) { IconText(Icons.Default.RequestQuote, "Estimate") }
                            OutlinedButton({ approve = true }) { IconText(Icons.Default.ThumbUp, "Record approval") }
                            if (running) OutlinedButton({ App.act("Timer stopped") { Jobs.stopTime(id) } }) { IconText(Icons.Default.Stop, "Stop timer") }
                            else OutlinedButton({ App.act("Timer started") { Jobs.startTime(id, lines.firstOrNull { it.technicianId != null }?.technicianId) } }) { IconText(Icons.Default.PlayArrow, "Start timer") }
                        }
                        OutlinedButton({ App.act { Printers.open(Documents.jobCard(id)) } }) { IconText(Icons.Default.Print, "Job card") }
                        OutlinedButton({ App.act("Key tag printed") { Printers.label(Documents.keyTag(id)) } }) { IconText(Icons.Default.Key, "Key tag") }
                        OutlinedButton({ App.act("Sticker printed") { Printers.label(Documents.serviceSticker(j.vehicleId)) } }) { IconText(Icons.Default.Label, "Service sticker") }
                        OutlinedButton({ App.act { WhatsApp.send(j.customerPhone, WhatsApp.carReadyText(j), "job", id) } }) { IconText(Icons.Default.Chat, "Car ready") }
                        if (j.invoiceId == null && !closed) Button({ invoice = true }) { IconText(Icons.Default.ReceiptLong, "Make invoice") }
                        if (j.invoiceId != null) Button({ showInv = true }) { IconText(Icons.Default.ReceiptLong, "Invoice") }
                        if (j.invoiceId != null && j.status != "Delivered") Button({ deliver = true }) { IconText(Icons.Default.ExitToApp, "Deliver car") }
                        if (j.status == "Delivered") OutlinedButton({ App.act { Printers.open(Documents.gatePass(id)) } }) { IconText(Icons.Default.Print, "Gate pass") }
                        if (!closed) TextButton({ cancel = true }) { Text("Cancel job", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }

    if (addPart) AddPartLine(id, techs) { addPart = false }
    addLabour?.let { kind -> AddLabourLine(id, kind, techs) { addLabour = null } }
    editLine?.let { l -> EditLine(l, techs) { editLine = null } }
    if (editVehicle) VehicleForm(v, { editVehicle = false }) { editVehicle = false }
    if (approve) {
        var by by remember { mutableStateOf(j.customerName) }
        var how by remember { mutableStateOf("In person") }
        FormDialog("Record approval", { approve = false }, { if (App.act("Approval recorded") { Jobs.approve(id, by, how) }) approve = false }) {
            Field("Approved by", by, { by = it }, Modifier.fillMaxWidth())
            Dropdown("How", listOf("In person", "Signed estimate", "WhatsApp", "Phone call"), how, { how = it })
        }
    }
    if (invoice) {
        var disc by remember { mutableStateOf<Long?>(0) }
        var notes by remember { mutableStateOf("") }
        var insurer by remember { mutableStateOf<Customer?>(null) }
        var share by remember { mutableStateOf<Long?>(0) }
        var pickIns by remember { mutableStateOf(false) }
        val preview = remember(disc) { try { Billing.compute(Billing.linesFromJob(id), disc ?: 0, Dates.today()) } catch (e: Exception) { null } }
        val warn = remember(preview) { preview?.let { Customers.creditWarning(j.customerId, it.total) } }
        FormDialog("Invoice ${j.jobNo}", { invoice = false }, {
            if (App.act("Invoice made") { val inv = Billing.invoiceJob(id, disc ?: 0, notes, insurer?.id, share ?: 0); invoice = false; showInv = true; inv.toString() }) {}
        }, saveText = "Make invoice") {
            if (preview != null) {
                Kv("Parts", Money.rs(preview.goodsNet)); Kv("Sales tax ${preview.goodsRate?.let { pk.workshop.core.Pct.fmt(it.rateBp) } ?: "(not registered)"}", Money.rs(preview.goodsTax))
                Kv("Labour & services", Money.rs(preview.servicesNet)); Kv("Services tax ${preview.servicesRate?.let { pk.workshop.core.Pct.fmt(it.rateBp) } ?: ""}", Money.rs(preview.servicesTax))
                Kv("Total", Money.rs(preview.total), bold = true)
            }
            MoneyField("Discount on the whole bill", disc, { disc = it })
            if (warn != null && Settings.bool(Settings.CREDIT_WARN)) Text(warn, color = MaterialTheme.colorScheme.error)
            Field("Notes on invoice", notes, { notes = it }, Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Insurance claim: ${insurer?.name ?: "none"}", Modifier.weight(1f)); TextButton({ pickIns = true }) { Text("Choose insurer") }
            }
            if (insurer != null) MoneyField("Insurer pays", share, { share = it })
        }
        if (pickIns) Picker("Insurance company", { Customers.list(it, "Insurance company") }, { it.name }, onPick = { insurer = it; pickIns = false }, onDismiss = { pickIns = false })
    }
    if (showInv && j.invoiceId != null) InvoiceDialog(j.invoiceId) { showInv = false }
    if (deliver && j.invoiceId != null) {
        val due = Billing.due(j.invoiceId)
        var pay by remember { mutableStateOf(false) }
        FormDialog("Deliver ${j.vehicleLabel}", { deliver = false }, if (due <= 0) ({
            if (App.act("Delivered") { Jobs.deliver(id, false); Printers.open(Documents.gatePass(id)) }) deliver = false
        }) else null, saveText = "Deliver and print gate pass", extraButtons = {
            if (due > 0) {
                Button({ pay = true }) { Text("Take payment") }
                OutlinedButton({ if (App.act("Delivered on credit") { Jobs.deliver(id, true); Printers.open(Documents.gatePass(id)) }) deliver = false }) { Text("Deliver on credit") }
            }
        }) {
            if (due > 0) Text("${Money.rs(due)} is unpaid. Take payment first, or deliver on credit if this customer has an account.")
            else Text("The invoice is paid. The gate pass will print.")
            Customers.creditWarning(j.customerId, 0)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        if (pay) ReceivePaymentDialog(j.customerId, j.invoiceId, due, { pay = false })
    }
    if (cancel) Confirm("Cancel this job?", "Parts issued to it go back into stock.", "Cancel job", { App.act("Job cancelled") { Jobs.setStatus(id, "Cancelled") } }, { cancel = false })
}

@Composable
private fun AddPartLine(jobId: Long, techs: List<Technician>, onDone: () -> Unit) {
    var part by remember { mutableStateOf<Part?>(null) }
    var pick by remember { mutableStateOf(true) }
    var qty by remember { mutableStateOf<Long?>(1000) }
    var rate by remember { mutableStateOf<Long?>(0) }
    var disc by remember { mutableStateOf<Long?>(0) }
    var tech by remember { mutableStateOf<Technician?>(null) }
    var custSupplied by remember { mutableStateOf(false) }
    var desc by remember { mutableStateOf("") }
    FormDialog("Add part", onDone, {
        if (App.act("Part added") {
                if (custSupplied) Jobs.addLine(JobLine(jobId = jobId, kind = "PART", description = desc.ifBlank { part?.name ?: "" }, qty = qty ?: 1000, rate = 0, customerSupplied = true, technicianId = tech?.id))
                else Jobs.addLine(JobLine(jobId = jobId, kind = "PART", partId = part?.id ?: fail("Choose a part."), qty = qty ?: fail("Enter the quantity."), rate = rate ?: 0, discount = disc ?: 0, technicianId = tech?.id))
            }) onDone()
    }, saveText = "Add") {
        Check("Customer brought this part (labour only, no warranty)", custSupplied) { custSupplied = it }
        if (custSupplied) Field("Part description", desc, { desc = it }, Modifier.fillMaxWidth())
        else Row(verticalAlignment = Alignment.CenterVertically) {
            Text(part?.let { "${it.name} · in stock ${Qty.fmt(it.qty)} ${it.unit}" } ?: "No part chosen", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            TextButton({ pick = true }) { Text("Choose part") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QtyField("Quantity" + (part?.let { " (${it.unit})" } ?: ""), qty, { qty = it }, Modifier.weight(1f))
            if (!custSupplied) { MoneyField("Rate", rate, { rate = it }, Modifier.weight(1f)); MoneyField("Discount", disc, { disc = it }, Modifier.weight(1f)) }
        }
        Dropdown("Fitted by", listOf<Technician?>(null) + techs, tech, { tech = it }, text = { it?.name ?: "—" })
    }
    if (pick && !custSupplied) PartPicker({ part = it; rate = it.price; pick = false }, { pick = false })
}

@Composable
private fun AddLabourLine(jobId: Long, kind: String, techs: List<Technician>, onDone: () -> Unit) {
    val services = remember { Services.list() }
    var desc by remember { mutableStateOf("") }
    var rate by remember { mutableStateOf<Long?>(0) }
    var qty by remember { mutableStateOf<Long?>(1000) }
    var disc by remember { mutableStateOf<Long?>(0) }
    var cost by remember { mutableStateOf<Long?>(0) }
    var vendor by remember { mutableStateOf("") }
    var tech by remember { mutableStateOf<Technician?>(techs.firstOrNull()) }
    FormDialog(if (kind == "SUBLET") "Add outside (sublet) work" else "Add labour", onDone, {
        if (App.act("Added") { Jobs.addLine(JobLine(jobId = jobId, kind = kind, description = desc, qty = qty ?: 1000, rate = rate ?: 0, discount = disc ?: 0,
                technicianId = if (kind == "LABOUR") tech?.id else null, cost = cost ?: 0, vendor = vendor)) }) onDone()
    }, saveText = "Add") {
        if (kind == "LABOUR") FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            services.forEach { s -> AssistChip({ desc = s.name; rate = s.price }, { Text("${s.name} · ${plain(s.price)}") }) }
        }
        Field("Description", desc, { desc = it }, Modifier.fillMaxWidth(), placeholder = if (kind == "SUBLET") "Denting and painting, machine shop…" else "Labour")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QtyField(if (kind == "LABOUR") "Qty / hours" else "Qty", qty, { qty = it }, Modifier.weight(1f))
            MoneyField("Rate charged", rate, { rate = it }, Modifier.weight(1f)); MoneyField("Discount", disc, { disc = it }, Modifier.weight(1f))
        }
        if (kind == "LABOUR") Dropdown("Technician", listOf<Technician?>(null) + techs, tech, { tech = it }, text = { it?.name ?: "—" })
        else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Vendor", vendor, { vendor = it }, Modifier.weight(1f)); MoneyField("Vendor's cost to us", cost, { cost = it }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun EditLine(l: JobLine, techs: List<Technician>, onDone: () -> Unit) {
    var e by remember { mutableStateOf(l) }
    FormDialog("Edit line", onDone, { if (App.act("Saved") { Jobs.updateLine(e) }) onDone() }) {
        Field("Description", e.description, { e = e.copy(description = it) }, Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MoneyField("Rate", e.rate, { e = e.copy(rate = it ?: 0) }, Modifier.weight(1f)); MoneyField("Discount", e.discount, { e = e.copy(discount = it ?: 0) }, Modifier.weight(1f))
        }
        Dropdown("Technician", listOf<Technician?>(null) + techs, techs.firstOrNull { it.id == e.technicianId }, { e = e.copy(technicianId = it?.id) }, text = { it?.name ?: "—" })
        if (e.kind == "SUBLET") MoneyField("Vendor's cost to us", e.cost, { e = e.copy(cost = it ?: 0) })
    }
}
