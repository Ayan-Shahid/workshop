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
import pk.workshop.core.Pct
import pk.workshop.core.Qty
import pk.workshop.data.*
import pk.workshop.db.fail
import pk.workshop.io.Excel
import pk.workshop.print.Documents
import pk.workshop.print.Printers
import pk.workshop.ui.*
import pk.workshop.ui.Button

@Composable
fun InventoryScreen() {
    var tab by rememberTab("Inventory")
    Page("Inventory") {
        Tabs(listOf("Parts", "Stock counts", "Price update", "Stock movements"), tab) { tab = it }
        when (tab) { 0 -> PartsTab(); 1 -> CountsTab(); 2 -> PriceTab(); 3 -> MovesTab() }
    }
}

@Composable
private fun PartsTab() {
    var q by remember { mutableStateOf("") }
    var low by remember { mutableStateOf(false) }
    var inactive by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<Part?>(null) }
    var adjust by remember { mutableStateOf<Part?>(null) }
    var labels by remember { mutableStateOf<Part?>(null) }
    var opening by remember { mutableStateOf<Part?>(null) }
    val list = remember(App.version, q, low, inactive) { Inventory.list(q, low, inactive) }
    Toolbar {
        SearchBox(q, { q = it }, Modifier.width(300.dp), "Search parts")
        ScanBox({ c -> Inventory.byBarcode(c)?.let { edit = it } ?: run { edit = Part(barcodes = listOf(c)); App.toast("New code — add the part") } }, Modifier.width(240.dp), "Scan to find or add")
        Spacer(Modifier.weight(1f))
        OutlinedButton({ FilePick.open("Import parts from Excel", listOf(".xlsx", ".xls"))?.let { f -> App.act { App.toast("Imported ${Excel.importParts(f)} parts") } } }) { IconText(Icons.Default.UploadFile, "Import Excel") }
        OutlinedButton({ App.act { Printers.open(Excel.template("parts")) } }) { Text("Template") }
        Button({ edit = Part() }) { IconText(Icons.Default.Add, "New part") }
    }
    Row(Modifier.padding(bottom = 8.dp)) { Check("Low stock only", low) { low = it }; Check("Show inactive", inactive) { inactive = it } }
    DataTable(list, listOf(
        C("Part", 2.2f) { it.name + if (!it.active) " (inactive)" else "" }, C("Part no", 1f) { it.partNo }, C("Brand", 0.9f) { it.brand }, C("Grade", 0.9f) { it.grade },
        C("Shelf", 0.6f) { it.location },
        C("Stock", 0.8f, true, content = { p -> Text("${Qty.fmt(p.qty)} ${p.unit}", color = if (p.low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, fontWeight = if (p.low) FontWeight.SemiBold else FontWeight.Normal) }),
        C("Avg cost", 0.8f, true) { plain(it.avgCost) }, C("Price", 0.8f, true) { plain(it.price) },
        C("", 1.4f, content = { p -> Row {
            IconButton({ adjust = p }) { Icon(Icons.Default.Tune, "Adjust stock") }
            IconButton({ labels = p }) { Icon(Icons.Default.QrCode, "Labels") }
            IconButton({ opening = p }) { Icon(Icons.Default.Inventory, "Opening stock") }
        } }),
    ), Modifier.fillMaxSize(), onClick = { edit = it }, footer = listOf("${list.size} parts · stock value " + rs(Money.roundToRupee(list.sumOf { Money.times(it.avgCost, maxOf(it.qty, 0)) }))))

    edit?.let { p0 -> PartForm(p0) { edit = null } }
    adjust?.let { p ->
        var delta by remember { mutableStateOf<Long?>(0) }; var reason by remember { mutableStateOf("") }; var add by remember { mutableStateOf(false) }
        FormDialog("Adjust stock — ${p.name}", { adjust = null }, { if (App.act("Stock adjusted") { Inventory.adjust(p.id, if (add) delta ?: 0 else -(delta ?: 0), reason) }) adjust = null }) {
            Text("In stock: ${Qty.fmt(p.qty)} ${p.unit}. The value difference is posted to stock adjustments.")
            Row { FilterChip(!add, { add = false }, { Text("Remove (damage, loss)") }); Spacer(Modifier.width(8.dp)); FilterChip(add, { add = true }, { Text("Add (found)") }) }
            QtyField("Quantity", delta, { delta = it }); Field("Reason", reason, { reason = it }, Modifier.fillMaxWidth())
        }
    }
    labels?.let { p ->
        var n by remember { mutableStateOf("1") }
        FormDialog("Print labels — ${p.name}", { labels = null }, { if (App.act("Labels sent to printer") { Printers.label(Documents.partLabels(p.id, n.toIntOrNull() ?: 1)) }) labels = null },
            saveText = "Print", extraButtons = { OutlinedButton({ App.act { Printers.open(Documents.partLabels(p.id, n.toIntOrNull() ?: 1)) } }) { Text("Preview PDF") }
                OutlinedButton({ App.act { Printers.label(Documents.binLabel(p.location.ifBlank { fail("This part has no shelf location.") })) } }) { Text("Shelf label") } }) {
            Text("Code: ${p.barcodes.firstOrNull() ?: Inventory.internalCode(p.id)} · label ${Settings.get(Settings.LABEL_W, "50")} × ${Settings.get(Settings.LABEL_H, "25")} mm")
            Field("How many labels", n, { n = it }, Modifier.width(200.dp), numeric = true)
        }
    }
    opening?.let { p ->
        var qty by remember { mutableStateOf<Long?>(0) }; var cost by remember { mutableStateOf<Long?>(p.lastCost) }
        FormDialog("Opening stock — ${p.name}", { opening = null }, { if (App.act("Opening stock added") { Inventory.openingStock(p.id, qty ?: 0, cost ?: 0) }) opening = null }) {
            Text("Stock you already had when you started the app. It is valued at cost against opening balances.")
            QtyField("Quantity (${p.unit})", qty, { qty = it }); MoneyField("Cost per ${p.unit}", cost, { cost = it })
        }
    }
}

@Composable
fun PartForm(p0: Part, onDone: () -> Unit) {
    var p by remember { mutableStateOf(p0) }
    var codes by remember { mutableStateOf(p0.barcodes.joinToString(", ")) }
    val moves = remember(p0.id, App.version) { if (p0.id == 0L) emptyList() else Inventory.moves(p0.id) }
    FormDialog(if (p0.id == 0L) "New part" else p0.name, onDone, {
        if (App.act("Part saved") { Inventory.save(p.copy(barcodes = codes.split(',').map { it.trim() }.filter { it.isNotEmpty() })) }) onDone()
    }, width = 760.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Name", p.name, { p = p.copy(name = it) }, Modifier.weight(2f)); Field("Part no (OEM)", p.partNo, { p = p.copy(partNo = it) }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Brand", p.brand, { p = p.copy(brand = it) }, Modifier.weight(1f)); Field("Category", p.category, { p = p.copy(category = it) }, Modifier.weight(1f))
            Dropdown("Grade", Defaults.partGrades, p.grade, { p = p.copy(grade = it) }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dropdown("Unit", Defaults.units, p.unit, { p = p.copy(unit = it) }, Modifier.weight(1f)); Field("Shelf / bin", p.location, { p = p.copy(location = it) }, Modifier.weight(1f))
            QtyField("Reorder level", p.reorderLevel, { p = p.copy(reorderLevel = it ?: 0) }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MoneyField("Sale price", p.price, { p = p.copy(price = it ?: 0) }, Modifier.weight(1f))
            Field("Average cost (from purchases)", plain(p.avgCost), {}, Modifier.weight(1f), enabled = false)
            Field("Margin", if (p.price > 0 && p.avgCost > 0) "%.0f%%".format((p.price - p.avgCost) * 100.0 / p.price) else "", {}, Modifier.weight(0.7f), enabled = false)
        }
        if (p.price in 1 until p.avgCost) Text("The sale price is below cost.", color = MaterialTheme.colorScheme.error)
        Field("Fits (makes, models, years)", p.fits, { p = p.copy(fits = it) }, Modifier.fillMaxWidth(), placeholder = "Corolla 2014-2020, Yaris")
        Field("Barcodes (comma separated; blank = generate one)", codes, { codes = it }, Modifier.fillMaxWidth())
        if (p0.id != 0L) Check("Active", p.active) { p = p.copy(active = it) }
        if (moves.isNotEmpty()) {
            Text("Recent movements", style = MaterialTheme.typography.titleSmall)
            DataTable(moves.take(30), listOf(C("Date", 1f) { Dates.show(it.date) }, C("Type", 1f) { it.kind }, C("Qty", 0.7f, true) { Qty.fmt(it.qty) },
                C("Cost", 0.8f, true) { plain(it.unitCost) }, C("Ref", 1.4f) { listOf(it.ref, it.note).filter { s -> s.isNotBlank() }.joinToString(" ") }), Modifier.heightIn(max = 220.dp))
        }
    }
}

@Composable
private fun CountsTab() {
    var open by remember { mutableStateOf<Long?>(null) }
    var start by remember { mutableStateOf(false) }
    val counts = remember(App.version) { Inventory.counts() }
    Toolbar { Text("Count stock shelf by shelf; approve to post the differences.", Modifier.weight(1f)); Button({ start = true }) { IconText(Icons.Default.Add, "Start a count") } }
    DataTable(counts, listOf(C("#", 0.4f) { it.str("id") }, C("Date", 1f) { Dates.show(it.str("date")) }, C("Note", 2f) { it.str("note") },
        C("Lines", 0.6f, true) { it.str("n") }, C("Status", 1f, content = { Chip(it.str("status")) })), Modifier.fillMaxSize(), onClick = { open = it.long("id") })
    if (start) {
        var note by remember { mutableStateOf("Full count") }; var shelf by remember { mutableStateOf("") }
        FormDialog("Start stock count", { start = false }, { if (App.act { open = Inventory.startCount(note, shelf.ifBlank { null }) }) start = false }) {
            Field("Note", note, { note = it }, Modifier.fillMaxWidth()); Field("Only shelves starting with (blank = all)", shelf, { shelf = it }, Modifier.fillMaxWidth())
        }
    }
    open?.let { id ->
        val lines = remember(App.version, id) { Inventory.countLines(id) }
        val status = remember(App.version, id) { counts.firstOrNull { it.long("id") == id }?.str("status") ?: "Open" }
        var last by remember { mutableStateOf("") }
        FormDialog("Stock count #$id", { open = null }, null, width = 900.dp, extraButtons = {
            if (status == "Open") Button({ if (App.act("Count approved; stock adjusted") { Inventory.approveCount(id) }) open = null }) { BtnText("Approve count") }
        }) {
            if (status == "Open") {
                ScanBox({ c -> last = Inventory.scanCount(id, c); App.refresh() }, Modifier.fillMaxWidth(), "Scan each item (adds 1 to its count)")
                if (last.isNotBlank()) Text("Counted: $last", color = MaterialTheme.colorScheme.tertiary)
            }
            Text("Differences: ${lines.count { it.counted != it.system }} of ${lines.size}")
            DataTable(lines, listOf(C("Shelf", 0.6f) { it.location }, C("Part", 2f) { it.name }, C("System", 0.8f, true) { Qty.fmt(it.system) },
                C("Counted", 1f, content = { l -> if (status == "Open") {
                    var t by remember(l.id, l.counted) { mutableStateOf(Qty.fmt(l.counted)) }
                    OutlinedTextField(t, { t = it; Qty.parse(it)?.let { q -> Inventory.setCounted(l.id, q) } }, singleLine = true, colors = fieldColors(), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), modifier = Modifier.width(110.dp).height(52.dp))
                } else Text(Qty.fmt(l.counted)) }),
                C("Difference", 0.8f, true, content = { l -> val d = l.counted - l.system; Text(if (d == 0L) "" else Qty.fmt(d), color = if (d < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary) })),
                Modifier.heightIn(max = 460.dp))
        }
    }
}

@Composable
private fun PriceTab() {
    var brand by remember { mutableStateOf<String?>(null) }
    var cat by remember { mutableStateOf<String?>(null) }
    var pct by remember { mutableStateOf("5") }
    var round by remember { mutableStateOf<Long?>(1000) }
    var confirm by remember { mutableStateOf(false) }
    val brands = remember(App.version) { listOf<String?>(null) + Inventory.brands() }
    val cats = remember(App.version) { listOf<String?>(null) + Inventory.categories() }
    val affected = remember(brand, cat, App.version) { Inventory.list().count { (brand == null || it.brand == brand) && (cat == null || it.category == cat) } }
    Section("Change sale prices by a percentage", Modifier.width(620.dp)) {
        Text("Part prices move with the rupee. Raise (or lower, with a minus) every price of a brand or category at once.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            Dropdown("Brand", brands, brand, { brand = it }, Modifier.weight(1f), text = { it ?: "All brands" })
            Dropdown("Category", cats, cat, { cat = it }, Modifier.weight(1f), text = { it ?: "All categories" })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Change %", pct, { pct = it }, Modifier.weight(1f), numeric = true); MoneyField("Round to nearest", round, { round = it }, Modifier.weight(1f))
        }
        Button({ confirm = true }, Modifier.padding(top = 8.dp)) { Text("Update $affected prices") }
    }
    if (confirm) Confirm("Update prices?", "$affected parts will change by $pct%.", "Update", {
        App.act { val n = Inventory.bulkPrice(brand, cat, Pct.parse(pct) ?: fail("Enter a percentage."), round ?: 0); App.toast("$n prices updated") }
    }, { confirm = false })
}

@Composable
private fun MovesTab() {
    var from by remember { mutableStateOf(Dates.monthStart()) }
    var to by remember { mutableStateOf(Dates.today()) }
    val rows = remember(App.version, from, to) {
        pk.workshop.db.Db.query("""SELECT m.*, p.name, p.unit FROM stock_moves m JOIN parts p ON p.id=m.part_id WHERE m.date BETWEEN ? AND ? ORDER BY m.date DESC, m.id DESC LIMIT 3000""", from, to)
    }
    Toolbar { DateField("From", from, { from = it }); DateField("To", to, { to = it }) }
    DataTable(rows, listOf(C("Date", 0.8f) { Dates.show(it.str("date")) }, C("Part", 2f) { it.str("name") }, C("Movement", 1f) { it.str("kind") },
        C("Qty", 0.8f, true) { Qty.fmt(it.long("qty")) + " " + it.str("unit") }, C("Unit cost", 0.8f, true) { plain(it.long("unit_cost")) },
        C("Reference", 1.4f) { listOf(it.str("ref_type"), it.str("note")).filter { s -> s.isNotBlank() }.joinToString(" ") }), Modifier.fillMaxSize())
}
