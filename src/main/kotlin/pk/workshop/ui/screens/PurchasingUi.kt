package pk.workshop.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pk.workshop.core.Dates
import pk.workshop.core.Money
import pk.workshop.core.Qty
import pk.workshop.data.*
import pk.workshop.db.fail
import pk.workshop.ui.*
import pk.workshop.ui.Button

@Composable
fun PurchasingScreen() {
    var tab by rememberTab("Purchasing")
    Page("Purchasing") {
        Tabs(listOf("Suppliers", "Purchase bills", "Purchase orders", "Returns", "Warranty claims", "Payments made"), tab) { tab = it }
        when (tab) { 0 -> SuppliersTab(); 1 -> BillsTab(); 2 -> OrdersTab(); 3 -> ReturnsTab(); 4 -> ClaimsTab(); 5 -> SupplierPaymentsTab() }
    }
}

@Composable
private fun SuppliersTab() {
    var q by remember { mutableStateOf("") }
    var edit by remember { mutableStateOf<Supplier?>(null) }
    var pay by remember { mutableStateOf<Supplier?>(null) }
    var stmt by remember { mutableStateOf<Supplier?>(null) }
    var opening by remember { mutableStateOf<Supplier?>(null) }
    val list = remember(App.version, q) { Suppliers.list(q) }
    val balances = remember(App.version) { list.associate { it.id to Suppliers.balance(it.id) } }
    Toolbar { SearchBox(q, { q = it }, Modifier.width(300.dp)); Spacer(Modifier.weight(1f)); Button({ edit = Supplier() }) { IconText(Icons.Default.Add, "New supplier") } }
    DataTable(list, listOf(C("Supplier", 2f) { it.name + if (!it.active) " (archived)" else "" }, C("Phone", 1f) { it.phone }, C("NTN", 1f) { it.ntn },
        C("We owe", 1f, true) { plain(balances[it.id] ?: 0) },
        C("", 2f, content = { s -> Row {
            TextButton({ pay = s }) { BtnText("Pay") }; TextButton({ stmt = s }) { BtnText("Statement") }; TextButton({ opening = s }) { Text("Opening balance") }
        } })), Modifier.fillMaxSize(), onClick = { edit = it }, footer = listOf("${list.size}", "", "", plain(balances.values.sum()), ""))
    edit?.let { s0 ->
        var s by remember(s0) { mutableStateOf(s0) }
        FormDialog(if (s0.id == 0L) "New supplier" else s0.name, { edit = null }, { if (App.act("Saved") { Suppliers.save(s) }) edit = null }, extraButtons = {
            if (s0.id != 0L) DeleteButton("Delete ${s0.name}?", "A supplier with bills or payments is archived (hidden from lists) instead of erased, so old bills stay correct.") {
                App.act { App.toast(Edits.message(Edits.deleteSupplier(s0.id), s0.name)) }; edit = null
            }
        }) {
            Field("Name", s.name, { s = s.copy(name = it) }, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Field("Phone", s.phone, { s = s.copy(phone = it) }, Modifier.weight(1f)); Field("NTN", s.ntn, { s = s.copy(ntn = it) }, Modifier.weight(1f)) }
            Field("Address", s.address, { s = s.copy(address = it) }, Modifier.fillMaxWidth()); Field("Notes", s.notes, { s = s.copy(notes = it) }, Modifier.fillMaxWidth())
            if (s0.id != 0L) Check("Active (unticked = archived)", s.active) { s = s.copy(active = it) }
        }
    }
    pay?.let { s ->
        var amt by remember { mutableStateOf<Long?>(maxOf(balances[s.id] ?: 0, 0)) }; var method by remember { mutableStateOf("Cash") }; var ref by remember { mutableStateOf("") }
        var date by remember { mutableStateOf(Dates.today()) }
        FormDialog("Pay ${s.name}", { pay = null }, { if (App.act("Payment recorded") { Purchasing.paySupplier(s.id, amt ?: 0, method, ref, date) }) pay = null }, saveText = "Pay") {
            Text("We owe ${Money.rs(balances[s.id] ?: 0)}")
            Dropdown("Method", Defaults.payMethods, method, { method = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { MoneyField("Amount", amt, { amt = it }, Modifier.weight(1f)); DateField("Date", date, { date = it }) }
            Field("Reference", ref, { ref = it }, Modifier.fillMaxWidth())
        }
    }
    stmt?.let { s -> StatementDialog("S", s.id) { stmt = null } }
    opening?.let { s ->
        var amt by remember { mutableStateOf<Long?>(0) }
        FormDialog("Opening balance — ${s.name}", { opening = null }, { if (App.act("Saved") { Suppliers.openingBalance(s.id, amt ?: 0) }) opening = null }) {
            Text("What you already owed this supplier when you started the app."); MoneyField("Amount owed", amt, { amt = it })
        }
    }
}

/** Editable list of parts with quantity and cost, with scanning. */
@Composable
fun DocLinesEditor(lines: SnapshotStateList<Triple<Part, Long, Long>>, costLabel: String = "Cost") {
    var pick by remember { mutableStateOf(false) }
    Toolbar {
        ScanBox({ c -> Inventory.byBarcode(c)?.let { p -> val i = lines.indexOfFirst { it.first.id == p.id }
            if (i >= 0) lines[i] = lines[i].copy(second = lines[i].second + 1000) else lines += Triple(p, 1000L, p.lastCost) } ?: App.toast("Unknown code $c") }, Modifier.weight(1f))
        OutlinedButton({ pick = true }) { IconText(Icons.Default.Add, "Add part") }
    }
    DataTable(lines.toList(), listOf(
        C("Part", 2.5f) { it.first.name },
        C("Qty", 1f, content = { l -> var t by remember(l) { mutableStateOf(Qty.fmt(l.second)) }
            OutlinedTextField(t, { t = it; Qty.parse(it)?.let { q -> val i = lines.indexOf(l); if (i >= 0) lines[i] = l.copy(second = q) } }, singleLine = true, colors = fieldColors(), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), modifier = Modifier.width(110.dp).height(52.dp)) }),
        C(costLabel, 1f, content = { l -> var t by remember(l) { mutableStateOf(if (l.third == 0L) "" else Money.plain(l.third).replace(",", "")) }
            OutlinedTextField(t, { t = it; Money.parse(it)?.let { c -> val i = lines.indexOf(l); if (i >= 0) lines[i] = l.copy(third = c) } }, singleLine = true, colors = fieldColors(), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), modifier = Modifier.width(120.dp).height(52.dp)) }),
        C("Amount", 1f, true) { plain(Money.times(it.third, it.second)) },
        C("", 0.4f, content = { l -> IconButton({ lines.remove(l) }) { Icon(Icons.Default.Delete, "Remove") } }),
    ), Modifier.heightIn(max = 340.dp), empty = "Scan or add parts", footer = listOf("${lines.size} items", "", "", plain(lines.sumOf { Money.times(it.third, it.second) }), ""))
    if (pick) PartPicker({ p -> lines += Triple(p, 1000L, p.lastCost); pick = false }, { pick = false })
}

@Composable
private fun BillsTab() {
    var from by remember { mutableStateOf(Dates.monthStart()) }
    var to by remember { mutableStateOf(Dates.today()) }
    var newBill by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<Purchase?>(null) }
    val list = remember(App.version, from, to) { Purchasing.list(from, to).filter { it.kind == "Bill" } }
    Toolbar { DateField("From", from, { from = it }); DateField("To", to, { to = it }); Spacer(Modifier.weight(1f)); Button({ newBill = true }) { IconText(Icons.Default.Add, "Enter supplier bill") } }
    DataTable(list, listOf(C("Doc", 1f) { it.docNo }, C("Date", 0.8f) { Dates.show(it.date) }, C("Supplier", 1.6f) { it.supplier }, C("Their bill no", 1f) { it.billNo },
        C("Total", 1f, true) { plain(it.total) }, C("Paid", 1f, true) { plain(it.paid) }, C("Unpaid", 1f, true) { plain(it.total - it.paid) }),
        Modifier.fillMaxSize(), onClick = { view = it }, footer = listOf("${list.size}", "", "", "", plain(list.sumOf { it.total }), plain(list.sumOf { it.paid }), plain(list.sumOf { it.total - it.paid })))
    if (newBill) PurchaseBillDialog { newBill = false }
    view?.let { p ->
        val ls = remember(p.id) { Purchasing.lines(p.id) }
        var editing by remember(p.id) { mutableStateOf(false) }
        if (editing) PurchaseBillDialog(editId = p.id) { editing = false; view = null }
        else FormDialog("${p.docNo} — ${p.supplier}", { view = null }, null, width = 760.dp, extraButtons = {
            DeleteButton("Delete ${p.docNo}?", "The bill's stock comes off the shelf and what you owe ${p.supplier} goes down by ${Money.rs(p.total)}." +
                if (p.paid > 0) " The ${Money.rs(p.paid)} paid against it stays as a payment to ${p.supplier}." else "") {
                if (App.act("${p.docNo} deleted") { Edits.deletePurchase(p.id) }) view = null
            }
            OutlinedButton({ editing = true }) { IconText(Icons.Default.Edit, "Edit") }
        }) {
            Kv("Supplier bill no", p.billNo); Kv("Date", Dates.show(p.date)); Kv("Freight / loading", Money.rs(p.landed)); Kv("Total", Money.rs(p.total), bold = true)
            DataTable(ls, listOf(C("Part", 2f) { it.str("name") }, C("Qty", 0.7f, true) { Qty.fmt(it.long("qty")) }, C("Cost", 0.8f, true) { plain(it.long("cost")) },
                C("Amount", 0.9f, true) { plain(Money.times(it.long("cost"), it.long("qty"))) }), Modifier.heightIn(max = 300.dp))
            OutlinedButton({ App.act { pk.workshop.print.Printers.label(pk.workshop.print.Documents.labels(ls.flatMap { r ->
                val part = Inventory.get(r.long("part_id")); List((r.long("qty") / 1000).toInt().coerceIn(0, 200)) {
                    pk.workshop.print.Documents.Label(part.name, listOf(part.partNo, part.brand, part.location).filter { s -> s.isNotBlank() }.joinToString(" · "),
                        part.barcodes.firstOrNull() ?: Inventory.internalCode(part.id), big = "Rs " + Money.plain(part.price)) } }, p.docNo)) } }) {
                IconText(Icons.Default.QrCode, "Print a label for every unit")
            }
        }
    }
}

@Composable
fun PurchaseBillDialog(fromOrder: Long? = null, editId: Long? = null, onDone: () -> Unit) {
    val old = remember(editId) { editId?.let { Purchasing.get(it) } }
    val isReturn = old?.kind == "Return"
    var supplier by remember { mutableStateOf<Supplier?>(old?.let { Suppliers.get(it.supplierId) }) }
    var pickS by remember { mutableStateOf(old == null) }
    var billNo by remember { mutableStateOf(old?.billNo ?: "") }
    var date by remember { mutableStateOf(old?.date ?: Dates.today()) }
    var landed by remember { mutableStateOf<Long?>(old?.landed ?: 0) }
    var payNow by remember { mutableStateOf<Long?>(0) }
    var method by remember { mutableStateOf("Cash") }
    var notes by remember { mutableStateOf(old?.notes ?: "") }
    val lines = remember { mutableStateListOf<Triple<Part, Long, Long>>().apply {
        if (editId != null) Purchasing.lines(editId).forEach { add(Triple(Inventory.get(it.long("part_id")), kotlin.math.abs(it.long("qty")), it.long("cost"))) }
    } }
    FormDialog(if (old != null) "Change ${old.docNo}" else "Enter supplier bill", onDone, {
        if (App.act(if (old != null) "${old.docNo} changed" else "Bill posted; stock added") {
                val ls = lines.map { DocLine(it.first.id, it.second, it.third) }
                val sid = supplier?.id ?: fail("Choose the supplier.")
                if (old != null) Edits.editPurchase(old.id, sid, billNo, date, ls, if (isReturn) 0 else landed ?: 0, notes)
                else Purchasing.postBill(sid, billNo, date, ls, landed ?: 0, "", payNow ?: 0, method)
            }) onDone()
    }, saveText = if (old != null) "Save changes" else "Post bill", width = 900.dp) {
        if (old != null) Text("Saving replaces ${old.docNo} under the same number. Payments against it stay linked.", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(supplier?.name ?: "No supplier chosen", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); TextButton({ pickS = true }) { Text("Choose supplier") }
            if (!isReturn) Field("Their bill no", billNo, { billNo = it }, Modifier.width(180.dp)); DateField(if (isReturn) "Date" else "Bill date", date, { date = it })
        }
        DocLinesEditor(lines, if (isReturn) "Credit per unit" else "Cost")
        if (!isReturn) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MoneyField("Freight, loading (spread over items)", landed, { landed = it }, Modifier.weight(1f))
            if (old == null) { MoneyField("Paid now", payNow, { payNow = it }, Modifier.weight(1f)); Dropdown("Paid by", Defaults.payMethods, method, { method = it }, Modifier.weight(1f)) }
        }
        if (old != null) Field(if (isReturn) "Reason" else "Notes", notes, { notes = it }, Modifier.fillMaxWidth())
        Text("Bill total: ${Money.rs(lines.sumOf { Money.times(it.third, it.second) } + (landed ?: 0))}", fontWeight = FontWeight.SemiBold)
    }
    if (pickS) Picker("Choose supplier", { Suppliers.list(it) }, { it.name }, { it.phone }, { supplier = it; pickS = false }, { pickS = false })
}

@Composable
private fun OrdersTab() {
    var create by remember { mutableStateOf(false) }
    var receive by remember { mutableStateOf<Long?>(null) }
    var view by remember { mutableStateOf<Long?>(null) }
    val orders = remember(App.version) { Purchasing.orders() }
    val suggestions = remember(App.version) { Purchasing.reorderSuggestions() }
    Toolbar { Text("${suggestions.size} parts at or below their reorder level.", Modifier.weight(1f)); Button({ create = true }) { IconText(Icons.Default.Add, "New order") } }
    DataTable(orders, listOf(C("PO", 1f) { it.str("po_no") }, C("Date", 0.8f) { Dates.show(it.str("date")) }, C("Supplier", 1.6f) { it.str("sname") },
        C("Value", 1f, true) { plain(it.long("total")) }, C("Status", 0.9f, content = { Chip(it.str("status")) }),
        C("", 1.6f, content = { o -> if (o.str("status") == "Open") Row {
            TextButton({ receive = o.long("id") }) { BtnText("Goods arrived") }
            TextButton({ App.act("Order cancelled") { Purchasing.cancelOrder(o.long("id")) } }) { Text("Cancel") }
        } })), Modifier.fillMaxSize(), onClick = { view = it.long("id") }, empty = "No purchase orders")
    if (create) {
        var supplier by remember { mutableStateOf<Supplier?>(null) }; var pickS by remember { mutableStateOf(false) }
        val lines = remember { mutableStateListOf<Triple<Part, Long, Long>>() }
        FormDialog("New purchase order", { create = false }, {
            if (App.act("Order saved") { Purchasing.createOrder(supplier?.id ?: fail("Choose the supplier."), Dates.today(), lines.map { DocLine(it.first.id, it.second, it.third) }, "") }) create = false
        }, width = 900.dp, extraButtons = { TextButton({ lines.clear(); suggestions.forEach { (p, q) -> lines += Triple(p, q, p.lastCost) } }) { Text("Fill from reorder suggestions") } }) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text(supplier?.name ?: "No supplier chosen", Modifier.weight(1f), fontWeight = FontWeight.SemiBold); TextButton({ pickS = true }) { Text("Choose supplier") } }
            DocLinesEditor(lines, "Expected cost")
        }
        if (pickS) Picker("Choose supplier", { Suppliers.list(it) }, { it.name }, onPick = { supplier = it; pickS = false }, onDismiss = { pickS = false })
    }
    receive?.let { id ->
        var billNo by remember { mutableStateOf("") }; var landed by remember { mutableStateOf<Long?>(0) }
        FormDialog("Goods arrived", { receive = null }, { if (App.act("Bill posted; stock added") { Purchasing.receiveOrder(id, billNo, Dates.today(), landed ?: 0) }) receive = null }) {
            Text("This posts the order as a supplier bill and adds the stock."); Field("Supplier's bill no", billNo, { billNo = it }, Modifier.fillMaxWidth())
            MoneyField("Freight / loading", landed, { landed = it })
        }
    }
    view?.let { id ->
        val o = remember(id, App.version) { orders.firstOrNull { it.long("id") == id } }
        val lines = remember(id) { mutableStateListOf<Triple<Part, Long, Long>>().apply {
            Purchasing.orderLines(id).forEach { add(Triple(Inventory.get(it.long("part_id")), it.long("qty"), it.long("cost"))) } } }
        val open = o?.str("status") != "Received"
        FormDialog("Purchase order ${o?.str("po_no") ?: ""}", { view = null }, if (open) ({
            if (App.act("Order saved") { Edits.editOrder(id, o!!.long("supplier_id"), o.str("date"), lines.map { DocLine(it.first.id, it.second, it.third) }, o.str("notes")) }) view = null
        }) else null, width = 900.dp, extraButtons = {
            if (open) DeleteButton("Delete this order?", "The purchase order is deleted. Nothing in stock or the books changes.") {
                if (App.act("Order deleted") { Edits.deleteOrder(id) }) view = null
            }
        }) {
            Text((o?.str("sname") ?: "") + " · " + (o?.str("status") ?: ""), fontWeight = FontWeight.SemiBold)
            if (open) DocLinesEditor(lines, "Expected cost")
            else lines.forEach { Text("${it.first.name} — ${Qty.fmt(it.second)} ${it.first.unit} × ${plain(it.third)}") }
            if (!open) Text("This order has arrived as a bill; change the bill instead.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ReturnsTab() {
    var create by remember { mutableStateOf(false) }
    val list = remember(App.version) { Purchasing.list("0000-00-00", "9999-12-31").filter { it.kind == "Return" } }
    Toolbar { Spacer(Modifier.weight(1f)); Button({ create = true }) { IconText(Icons.Default.Undo, "Return parts to supplier") } }
    var view by remember { mutableStateOf<Purchase?>(null) }
    DataTable(list, listOf(C("Doc", 1f) { it.docNo }, C("Date", 1f) { Dates.show(it.date) }, C("Supplier", 2f) { it.supplier }, C("Value", 1f, true) { plain(-it.total) }, C("Notes", 2f) { it.notes }),
        Modifier.fillMaxSize(), onClick = { view = it }, empty = "No returns")
    view?.let { r ->
        var editing by remember(r.id) { mutableStateOf(false) }
        if (editing) PurchaseBillDialog(editId = r.id) { editing = false; view = null }
        else FormDialog("${r.docNo} — return to ${r.supplier}", { view = null }, null, width = 700.dp, extraButtons = {
            DeleteButton("Delete ${r.docNo}?", "The returned parts come back onto the shelf and you owe ${r.supplier} ${Money.rs(-r.total)} more again.") {
                if (App.act("${r.docNo} deleted") { Edits.deletePurchase(r.id) }) view = null
            }
            OutlinedButton({ editing = true }) { IconText(Icons.Default.Edit, "Edit") }
        }) {
            Kv("Date", Dates.show(r.date)); Kv("Reason", r.notes); Kv("Value", Money.rs(-r.total), bold = true)
            Purchasing.lines(r.id).forEach { Text("${it.str("name")} — ${Qty.fmt(-it.long("qty"))} ${it.str("unit")} × ${plain(it.long("cost"))}") }
        }
    }
    if (create) {
        var supplier by remember { mutableStateOf<Supplier?>(null) }; var pickS by remember { mutableStateOf(false) }; var notes by remember { mutableStateOf("") }
        val lines = remember { mutableStateListOf<Triple<Part, Long, Long>>() }
        FormDialog("Return to supplier", { create = false }, {
            if (App.act("Return posted") { Purchasing.postReturn(supplier?.id ?: fail("Choose the supplier."), Dates.today(), lines.map { DocLine(it.first.id, it.second, it.third) }, notes) }) create = false
        }, width = 900.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text(supplier?.name ?: "No supplier chosen", Modifier.weight(1f), fontWeight = FontWeight.SemiBold); TextButton({ pickS = true }) { Text("Choose supplier") } }
            DocLinesEditor(lines, "Credit per unit"); Field("Reason", notes, { notes = it }, Modifier.fillMaxWidth())
        }
        if (pickS) Picker("Choose supplier", { Suppliers.list(it) }, { it.name }, onPick = { supplier = it; pickS = false }, onDismiss = { pickS = false })
    }
}

@Composable
private fun ClaimsTab() {
    var create by remember { mutableStateOf(false) }
    var resolve by remember { mutableStateOf<Long?>(null) }
    var view by remember { mutableStateOf<pk.workshop.db.Row?>(null) }
    val list = remember(App.version) { Purchasing.claims() }
    Toolbar { Text("Faulty parts under warranty sent back to the supplier.", Modifier.weight(1f)); Button({ create = true }) { IconText(Icons.Default.Add, "New claim") } }
    DataTable(list, listOf(C("Date", 0.8f) { Dates.show(it.str("date")) }, C("Part", 1.8f) { it.str("pname") }, C("Supplier", 1.4f) { it.str("sname") },
        C("Qty", 0.5f, true) { Qty.fmt(it.long("qty")) }, C("Reason", 1.6f) { it.str("reason") }, C("Status", 0.9f, content = { Chip(it.str("status")) }),
        C("", 0.8f, content = { r -> if (r.str("status") == "Sent") TextButton({ resolve = r.long("id") }) { Text("Resolve") } })), Modifier.fillMaxSize(), onClick = { view = it }, empty = "No warranty claims")
    view?.let { r ->
        val sent = r.str("status") == "Sent"
        var qty by remember(r) { mutableStateOf<Long?>(r.long("qty")) }; var reason by remember(r) { mutableStateOf(r.str("reason")) }
        FormDialog("Warranty claim #${r.long("id")} — ${r.str("pname")}", { view = null }, if (sent) ({
            if (App.act("Claim saved") { Edits.editClaim(r.long("id"), qty ?: 0, reason) }) view = null }) else null, extraButtons = {
            DeleteButton("Delete this claim?", when (r.str("status")) {
                "Replaced" -> "The replacement part that went into stock is taken out again."
                "Credited" -> "The supplier's credit is removed, so you owe them that amount again."
                else -> "The claim is deleted. Nothing in stock or the books changes."
            }) { if (App.act("Claim deleted") { Edits.deleteClaim(r.long("id")) }) view = null }
        }) {
            Kv("Supplier", r.str("sname")); Kv("Status", r.str("status"))
            if (sent) { QtyField("Quantity", qty, { qty = it }); Field("Fault", reason, { reason = it }, Modifier.fillMaxWidth()) }
            else { Kv("Quantity", Qty.fmt(r.long("qty"))); Kv("Fault", r.str("reason")) }
        }
    }
    if (create) {
        var part by remember { mutableStateOf<Part?>(null) }; var supplier by remember { mutableStateOf<Supplier?>(null) }
        var qty by remember { mutableStateOf<Long?>(1000) }; var reason by remember { mutableStateOf("") }
        var pickP by remember { mutableStateOf(false) }; var pickS by remember { mutableStateOf(false) }
        FormDialog("New warranty claim", { create = false }, { if (App.act("Claim recorded") { Purchasing.createClaim(part?.id ?: fail("Choose the part."), supplier?.id ?: fail("Choose the supplier."), qty ?: 1000, reason) }) create = false }) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text(part?.name ?: "No part", Modifier.weight(1f)); TextButton({ pickP = true }) { Text("Choose part") } }
            Row(verticalAlignment = Alignment.CenterVertically) { Text(supplier?.name ?: "No supplier", Modifier.weight(1f)); TextButton({ pickS = true }) { Text("Choose supplier") } }
            QtyField("Quantity", qty, { qty = it }); Field("Fault", reason, { reason = it }, Modifier.fillMaxWidth())
        }
        if (pickP) PartPicker({ part = it; pickP = false }, { pickP = false })
        if (pickS) Picker("Choose supplier", { Suppliers.list(it) }, { it.name }, onPick = { supplier = it; pickS = false }, onDismiss = { pickS = false })
    }
    resolve?.let { id ->
        var outcome by remember { mutableStateOf("Replaced") }; var credit by remember { mutableStateOf<Long?>(0) }
        FormDialog("Resolve claim", { resolve = null }, { if (App.act("Claim closed") { Purchasing.resolveClaim(id, outcome, credit ?: 0) }) resolve = null }) {
            Dropdown("Outcome", listOf("Replaced", "Credited", "Rejected"), outcome, { outcome = it })
            if (outcome == "Credited") MoneyField("Credit from supplier", credit, { credit = it })
            Text(when (outcome) { "Replaced" -> "The replacement part goes into stock."; "Credited" -> "Reduces what you owe the supplier."; else -> "No stock or money changes." },
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SupplierPaymentsTab() {
    var from by remember { mutableStateOf(Dates.monthStart()) }
    var to by remember { mutableStateOf(Dates.today()) }
    var open by remember { mutableStateOf<Long?>(null) }
    val list = remember(App.version, from, to) { Billing.payments(from, to, "OUT") }
    Toolbar { DateField("From", from, { from = it }); DateField("To", to, { to = it }); Text("Click a payment to change or delete it.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    DataTable(list, listOf(C("No", 1f) { it.no }, C("Date", 0.8f) { Dates.show(it.date) }, C("To", 1.6f) { it.party }, C("Method", 1f) { it.method },
        C("Reference", 1.2f) { it.reference }, C("For bill", 1f) { it.invoiceNo }, C("Amount", 1f, true) { plain(it.amount) }),
        Modifier.fillMaxSize(), onClick = { open = it.id }, empty = "No payments to suppliers in these dates",
        footer = listOf("${list.size}", "", "", "", "", "", plain(list.sumOf { it.amount })))
    open?.let { PaymentDialog(it) { open = null } }
}
