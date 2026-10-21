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

@Composable
fun PurchasingScreen() {
    var tab by remember { mutableStateOf(0) }
    Page("Purchasing") {
        Tabs(listOf("Suppliers", "Purchase bills", "Purchase orders", "Returns", "Warranty claims"), tab) { tab = it }
        when (tab) { 0 -> SuppliersTab(); 1 -> BillsTab(); 2 -> OrdersTab(); 3 -> ReturnsTab(); 4 -> ClaimsTab() }
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
    DataTable(list, listOf(C("Supplier", 2f) { it.name }, C("Phone", 1f) { it.phone }, C("NTN", 1f) { it.ntn },
        C("We owe", 1f, true) { plain(balances[it.id] ?: 0) },
        C("", 2f, content = { s -> Row {
            TextButton({ pay = s }) { Text("Pay") }; TextButton({ stmt = s }) { Text("Statement") }; TextButton({ opening = s }) { Text("Opening balance") }
        } })), Modifier.fillMaxSize(), onClick = { edit = it }, footer = listOf("${list.size}", "", "", plain(balances.values.sum()), ""))
    edit?.let { s0 ->
        var s by remember(s0) { mutableStateOf(s0) }
        FormDialog(if (s0.id == 0L) "New supplier" else s0.name, { edit = null }, { if (App.act("Saved") { Suppliers.save(s) }) edit = null }) {
            Field("Name", s.name, { s = s.copy(name = it) }, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Field("Phone", s.phone, { s = s.copy(phone = it) }, Modifier.weight(1f)); Field("NTN", s.ntn, { s = s.copy(ntn = it) }, Modifier.weight(1f)) }
            Field("Address", s.address, { s = s.copy(address = it) }, Modifier.fillMaxWidth()); Field("Notes", s.notes, { s = s.copy(notes = it) }, Modifier.fillMaxWidth())
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
            OutlinedTextField(t, { t = it; Qty.parse(it)?.let { q -> val i = lines.indexOf(l); if (i >= 0) lines[i] = l.copy(second = q) } }, singleLine = true, modifier = Modifier.width(110.dp).height(52.dp)) }),
        C(costLabel, 1f, content = { l -> var t by remember(l) { mutableStateOf(if (l.third == 0L) "" else Money.plain(l.third).replace(",", "")) }
            OutlinedTextField(t, { t = it; Money.parse(it)?.let { c -> val i = lines.indexOf(l); if (i >= 0) lines[i] = l.copy(third = c) } }, singleLine = true, modifier = Modifier.width(120.dp).height(52.dp)) }),
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
        FormDialog("${p.docNo} — ${p.supplier}", { view = null }, null, width = 760.dp) {
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
fun PurchaseBillDialog(fromOrder: Long? = null, onDone: () -> Unit) {
    var supplier by remember { mutableStateOf<Supplier?>(null) }
    var pickS by remember { mutableStateOf(true) }
    var billNo by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(Dates.today()) }
    var landed by remember { mutableStateOf<Long?>(0) }
    var payNow by remember { mutableStateOf<Long?>(0) }
    var method by remember { mutableStateOf("Cash") }
    val lines = remember { mutableStateListOf<Triple<Part, Long, Long>>() }
    FormDialog("Enter supplier bill", onDone, {
        if (App.act("Bill posted; stock added") {
                Purchasing.postBill(supplier?.id ?: fail("Choose the supplier."), billNo, date, lines.map { DocLine(it.first.id, it.second, it.third) }, landed ?: 0, "", payNow ?: 0, method)
            }) onDone()
    }, saveText = "Post bill", width = 900.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(supplier?.name ?: "No supplier chosen", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); TextButton({ pickS = true }) { Text("Choose supplier") }
            Field("Their bill no", billNo, { billNo = it }, Modifier.width(180.dp)); DateField("Bill date", date, { date = it })
        }
        DocLinesEditor(lines)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MoneyField("Freight, loading (spread over items)", landed, { landed = it }, Modifier.weight(1f))
            MoneyField("Paid now", payNow, { payNow = it }, Modifier.weight(1f)); Dropdown("Paid by", Defaults.payMethods, method, { method = it }, Modifier.weight(1f))
        }
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
            TextButton({ receive = o.long("id") }) { Text("Goods arrived") }
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
        val ls = remember(id) { Purchasing.orderLines(id) }
        FormDialog("Purchase order", { view = null }, null) {
            ls.forEach { Text("${it.str("name")} — ${Qty.fmt(it.long("qty"))} ${it.str("unit")} × ${plain(it.long("cost"))}") }
        }
    }
}

@Composable
private fun ReturnsTab() {
    var create by remember { mutableStateOf(false) }
    val list = remember(App.version) { Purchasing.list("0000-00-00", "9999-12-31").filter { it.kind == "Return" } }
    Toolbar { Spacer(Modifier.weight(1f)); Button({ create = true }) { IconText(Icons.Default.Undo, "Return parts to supplier") } }
    DataTable(list, listOf(C("Doc", 1f) { it.docNo }, C("Date", 1f) { Dates.show(it.date) }, C("Supplier", 2f) { it.supplier }, C("Value", 1f, true) { plain(-it.total) }, C("Notes", 2f) { it.notes }),
        Modifier.fillMaxSize(), empty = "No returns")
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
    val list = remember(App.version) { Purchasing.claims() }
    Toolbar { Text("Faulty parts under warranty sent back to the supplier.", Modifier.weight(1f)); Button({ create = true }) { IconText(Icons.Default.Add, "New claim") } }
    DataTable(list, listOf(C("Date", 0.8f) { Dates.show(it.str("date")) }, C("Part", 1.8f) { it.str("pname") }, C("Supplier", 1.4f) { it.str("sname") },
        C("Qty", 0.5f, true) { Qty.fmt(it.long("qty")) }, C("Reason", 1.6f) { it.str("reason") }, C("Status", 0.9f, content = { Chip(it.str("status")) }),
        C("", 0.8f, content = { r -> if (r.str("status") == "Sent") TextButton({ resolve = r.long("id") }) { Text("Resolve") } })), Modifier.fillMaxSize(), empty = "No warranty claims")
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
