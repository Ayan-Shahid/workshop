package pk.workshop.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pk.workshop.core.Dates
import pk.workshop.core.Money
import pk.workshop.core.Pct
import pk.workshop.data.*
import pk.workshop.io.Backup
import pk.workshop.print.Documents
import pk.workshop.print.Printers
import pk.workshop.security.Vault
import pk.workshop.ui.*
import pk.workshop.ui.Button

@Composable
fun SettingsScreen() {
    var tab by rememberTab("Settings")
    Page("Settings") {
        Tabs(listOf("Business", "Tax and FBR", "Printers and machines", "Lists", "Appearance", "Backup", "Password", "Updates"), tab) { tab = it }
        // Seeing the tax tab counts as having checked the rates (Getting started)
        LaunchedEffect(tab) { if (tab == 1) pk.workshop.data.Settings.setBool(pk.workshop.data.Guide.TAXES_CHECKED, true) }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            when (tab) { 0 -> BusinessTab(); 1 -> TaxTab(); 2 -> PrintersTab(); 3 -> ListsTab(); 4 -> AppearanceTab(); 5 -> BackupTab(); 6 -> PasswordTab(); 7 -> UpdatesTab() }
        }
    }
}

@Composable
private fun SettingField(label: String, key: String, modifier: Modifier = Modifier.fillMaxWidth(), multi: Boolean = false) {
    var v by remember(key) { mutableStateOf(Settings.get(key)) }
    Field(label, v, { v = it; Settings.set(key, it) }, modifier, singleLine = !multi, minLines = if (multi) 2 else 1)
}

@Composable
private fun BusinessTab() {
    Section("Business details (printed on every invoice)", Modifier.width(720.dp)) {
        SettingField("Business name", Settings.BUSINESS)
        SettingField("Address", Settings.ADDRESS)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { SettingField("Phone", Settings.PHONE, Modifier.weight(1f)); SettingField("NTN", Settings.NTN, Modifier.weight(1f)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingField("FBR sales-tax number (STRN)", Settings.STRN, Modifier.weight(1f)); SettingField("Provincial registration (PRA/SRB/KPRA)", Settings.PROV_REG, Modifier.weight(1f))
        }
        var prov by remember { mutableStateOf(Settings.get(Settings.PROVINCE, "Punjab")) }
        Dropdown("Province", listOf("Punjab", "Sindh", "Khyber Pakhtunkhwa", "Balochistan", "Islamabad (ICT)", "AJK", "Gilgit-Baltistan"), prov, { prov = it; Settings.set(Settings.PROVINCE, it) })
        SettingField("Invoice terms and warranty", Settings.TERMS, multi = true)
        SettingField("Invoice footer", Settings.FOOTER)
        Text("Changes save as you type.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TaxTab() {
    val rates = remember(App.version) { Tax.all() }
    var registered by remember { mutableStateOf(Settings.bool(Settings.TAX_REGISTERED)) }
    var fbr by remember { mutableStateOf(Settings.bool("fbr.enabled")) }
    var add by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Section("Sales tax", Modifier.width(620.dp)) {
            Check("The business is registered for sales tax (charge tax on bills)", registered) { registered = it; Settings.setBool(Settings.TAX_REGISTERED, it); App.refresh() }
            Text("Parts are taxed at the FBR rate; labour and services at your province's rate. Rates are dated, so a budget change never alters old bills.", style = MaterialTheme.typography.bodySmall)
            DataTable(rates, listOf(C("Applies to", 1f) { if (it.kind == "GOODS") "Parts (goods)" else "Labour and services" }, C("Authority", 1.2f) { it.authority },
                C("Rate", 0.6f, true) { Pct.fmt(it.rateBp) }, C("From", 0.8f) { Dates.show(it.from) },
                C("", 0.4f, content = { r -> IconButton({ App.act { Tax.delete(r.id) } }) { Icon(Icons.Default.Delete, "Delete") } })), Modifier.heightIn(max = 260.dp).padding(vertical = 8.dp))
            OutlinedButton({ add = true }) { IconText(Icons.Default.Add, "Add a rate") }
            Text("Confirm the rate for auto-workshop labour in your province with your tax adviser.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Section("FBR digital invoicing", Modifier.width(460.dp)) {
            Check("FBR integration is set up for this business", fbr) { fbr = it; Settings.setBool("fbr.enabled", it) }
            Text("Every sales-tax-registered business must report invoices to FBR through a licensed integrator (PRAL integrates free). " +
                "With this on, each new invoice waits in Billing › FBR queue until its FBR number is recorded; the number and its QR code then print on the invoice. " +
                "A reported invoice can only be corrected within 72 hours, so use credit notes for changes.", style = MaterialTheme.typography.bodySmall)
            SettingField("Integrator name", "fbr.integrator")
            SettingField("POS / integration ID", "fbr.pos_id")
        }
    }
    if (add) {
        var kind by remember { mutableStateOf("SERVICES") }; var auth by remember { mutableStateOf("PRA (Punjab)") }; var pct by remember { mutableStateOf("16") }; var from by remember { mutableStateOf(Dates.today()) }
        FormDialog("Add tax rate", { add = false }, { if (App.act("Rate added") { Tax.add(kind, auth, Pct.parse(pct) ?: pk.workshop.db.fail("Enter the rate."), from) }) add = false }) {
            Dropdown("Applies to", listOf("GOODS", "SERVICES"), kind, { kind = it; auth = if (it == "GOODS") "FBR" else auth }, text = { if (it == "GOODS") "Parts (goods)" else "Labour and services" })
            Dropdown("Authority", listOf("FBR", "PRA (Punjab)", "SRB (Sindh)", "KPRA (KP)", "BRA (Balochistan)", "FBR (Islamabad)"), auth, { auth = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Field("Rate %", pct, { pct = it }, Modifier.weight(1f), numeric = true); DateField("From date", from, { from = it }) }
        }
    }
}

@Composable
private fun PrintersTab() {
    val names = remember { try { listOf("") + Printers.names() } catch (e: Exception) { listOf("") } }
    var receipt by remember { mutableStateOf(Settings.get(Settings.RECEIPT_PRINTER)) }
    var a4 by remember { mutableStateOf(Settings.get(Settings.A4_PRINTER)) }
    var label by remember { mutableStateOf(Settings.get(Settings.LABEL_PRINTER)) }
    var mode by remember { mutableStateOf(Settings.get("printer.receipt_mode", "raw")) }
    var drawer by remember { mutableStateOf(Settings.bool(Settings.DRAWER)) }
    var display by remember { mutableStateOf(Settings.bool(Settings.DISPLAY)) }
    fun show(n: String) = n.ifBlank { "Windows default printer" }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Section("Printers", Modifier.width(560.dp)) {
            if (names.size <= 1) Text("No printers found on this PC. Install the printer's Windows driver first.", color = MaterialTheme.colorScheme.error)
            Dropdown("Receipt printer (80 mm)", names, receipt, { receipt = it; Settings.set(Settings.RECEIPT_PRINTER, it) }, text = ::show)
            Dropdown("Receipt printing", listOf("raw", "driver"), mode, { mode = it; Settings.set("printer.receipt_mode", it) },
                text = { if (it == "raw") "Direct ESC/POS (fast, prints QR, cuts paper)" else "Through the Windows driver (any printer)" })
            Dropdown("A4 printer (invoices, reports)", names, a4, { a4 = it; Settings.set(Settings.A4_PRINTER, it) }, text = ::show)
            Dropdown("Barcode label printer", names, label, { label = it; Settings.set(Settings.LABEL_PRINTER, it) }, text = ::show)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { SettingField("Label width (mm)", Settings.LABEL_W, Modifier.weight(1f)); SettingField("Label height (mm)", Settings.LABEL_H, Modifier.weight(1f)) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ App.act("Test sent") { Printers.raw(receipt.ifBlank { null }, "Workshop Manager test print\n\n\n\n".toByteArray() + byteArrayOf(0x1D, 'V'.code.toByte(), 66, 0)) } }) { Text("Test receipt printer") }
                OutlinedButton({ App.act("Test label sent") { Printers.label(Documents.labels(listOf(Documents.Label("Test label", "Workshop Manager", "TEST123")), "test")) } }) { Text("Test label") }
            }
        }
        Section("Machines", Modifier.width(460.dp)) {
            Check("Open the cash drawer on cash payments (drawer plugged into the receipt printer)", drawer) { drawer = it; Settings.setBool(Settings.DRAWER, it) }
            OutlinedButton({ App.act("Drawer command sent") { Printers.openDrawer() } }) { Text("Test cash drawer") }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Check("Show a customer display window at the counter (second screen)", display) { display = it; Settings.setBool(Settings.DISPLAY, it) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Barcode scanners need no setup: they type the code like a keyboard. Choose a 2D scanner so it also reads the QR codes on job cards and invoices.",
                style = MaterialTheme.typography.bodySmall)
            Text("Fingerprint attendance: set each person's machine ID in Staff, then import the machine's log in Staff › Attendance.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ListsTab() {
    val services = remember(App.version) { Services.list(true) }
    val complaints = remember(App.version) { Services.complaints() }
    var edit by remember { mutableStateOf<ServiceItem?>(null) }
    var newC by remember { mutableStateOf("") }
    var points by remember { mutableStateOf(Settings.get(Settings.POINTS_PER_100, "1")) }
    var credit by remember { mutableStateOf(Settings.bool(Settings.CREDIT_WARN, true)) }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Section("Labour packages and rates", Modifier.width(560.dp), actions = { TextButton({ edit = ServiceItem() }) { IconText(Icons.Default.Add, "Add") } }) {
            DataTable(services, listOf(C("Service", 2f) { it.name + if (!it.active) " (off)" else "" }, C("Department", 1f) { it.department }, C("Rate", 0.8f, true) { plain(it.price) }),
                Modifier.heightIn(max = 380.dp), onClick = { edit = it })
        }
        Column(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Section("Common complaints (quick pick at check-in)") {
                Row(verticalAlignment = Alignment.CenterVertically) { Field("New complaint", newC, { newC = it }, Modifier.weight(1f)); TextButton({ App.act { Services.addComplaint(newC); newC = "" } }) { Text("Add") } }
                complaints.forEach { (id, t) -> Row(verticalAlignment = Alignment.CenterVertically) { Text(t, Modifier.weight(1f)); IconButton({ App.act { Services.deleteComplaint(id) } }) { Icon(Icons.Default.Close, "Remove") } } }
            }
            Section("Loyalty and credit") {
                Field("Points per Rs 100 spent (0 = off)", points, { points = it; Settings.set(Settings.POINTS_PER_100, it.filter(Char::isDigit)) }, Modifier.fillMaxWidth(), numeric = true)
                var pv by remember { mutableStateOf<Long?>(Settings.get(Settings.POINT_VALUE, "100").toLongOrNull()) }
                MoneyField("Value of one point when redeemed", pv, { pv = it; Settings.set(Settings.POINT_VALUE, (it ?: 0).toString()) })
                Check("Warn before giving credit above a customer's limit or terms", credit) { credit = it; Settings.setBool(Settings.CREDIT_WARN, it) }
            }
        }
    }
    edit?.let { s0 ->
        var s by remember(s0) { mutableStateOf(s0) }
        FormDialog(if (s0.id == 0L) "New labour package" else s0.name, { edit = null }, { if (App.act("Saved") { Services.save(s) }) edit = null }) {
            Field("Name", s.name, { s = s.copy(name = it) }, Modifier.fillMaxWidth()); MoneyField("Rate", s.price, { s = s.copy(price = it ?: 0) })
            Dropdown("Department", Defaults.departments, s.department.ifBlank { null }, { s = s.copy(department = it) })
            if (s0.id != 0L) Check("Active", s.active) { s = s.copy(active = it) }
        }
    }
}

@Composable
private fun AppearanceTab() {
    var lakh by remember { mutableStateOf(Settings.bool(Settings.LAKH)) }
    Section("Appearance", Modifier.width(560.dp)) {
        Text("Theme", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("system" to "Same as Windows", "light" to "Light (red on white)", "dark" to "Dark (red on black)").forEach { (k, l) ->
                FilterChip(App.theme == k, { App.theme = k; Settings.set(Settings.THEME, k) }, { Text(l) })
            }
        }
        Check("Group amounts in lakhs (12,50,000) instead of millions (1,250,000)", lakh) { lakh = it; Settings.setBool(Settings.LAKH, it); Money.lakhGrouping = it; App.refresh() }
        var tours by remember { mutableStateOf(Help.autoTours) }
        Check("Show a short guided tour the first time each screen is opened", tours) { tours = it; Help.autoTours = it }
        Text("Every tour and lesson is also on the Training page, and the ? next to each screen title explains that screen.", style = MaterialTheme.typography.bodySmall)
        Text("Shortcuts: F2 job cards · F3 counter sale · F4 customers · F5 inventory · F6 billing", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun UpdatesTab() {
    val u = pk.workshop.io.Updater
    val info = pk.workshop.io.AppInfo
    var auto by remember { mutableStateOf(u.auto) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun bg(block: () -> Unit) { busy = true; Thread { try { block() } finally { busy = false } }.start() }
    Section("Updates", Modifier.width(720.dp)) {
        Kv("This version", info.version + if (info.built.isNotBlank()) "  (built ${info.built})" else "")
        val reason = u.unavailableReason
        if (reason != null) { Text(reason); return@Section }
        Kv("Last checked", Settings.get(pk.workshop.io.Updater.LAST_CHECK).ifBlank { "Not yet" })
        when (val s = u.state) {
            is pk.workshop.io.Updater.Checking -> Text("Checking…")
            is pk.workshop.io.Updater.UpToDate -> Text("You have the latest version.")
            is pk.workshop.io.Updater.Available -> {
                Text("Version ${s.r.version} is available (${"%.1f".format(s.r.size / 1048576.0)} MB).", fontWeight = FontWeight.SemiBold)
                if (s.r.notes.isNotBlank()) Text(s.r.notes)
                Button({ bg { u.download(s.r) } }, enabled = !busy) { IconText(Icons.Default.Download, "Download and install") }
            }
            is pk.workshop.io.Updater.Downloading -> {
                Text("Downloading ${s.r.version}…")
                LinearProgressIndicator({ (s.done.toFloat() / s.r.size.coerceAtLeast(1)).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
            }
            is pk.workshop.io.Updater.Ready -> {
                Text("Version ${s.version} is downloaded and checked. A backup of your records was made. It installs the next time the app opens.", fontWeight = FontWeight.SemiBold)
                (s.notes.ifBlank { u.readyNotes() }).takeIf { it.isNotBlank() }?.let { Text(it) }
                Button({ restartForUpdateFromSettings() }) { IconText(Icons.Default.RestartAlt, "Restart now") }
            }
            is pk.workshop.io.Updater.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error)
            else -> {}
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ bg { u.check() } }, enabled = !busy && u.state !is pk.workshop.io.Updater.Downloading) { Text("Check now") }
        }
        Check("Download and install updates automatically", auto) { auto = it; u.auto = it }
        Text("New versions download in the background while you work. Before one is used, the app checks it is signed with your workshop's key and makes a backup of your records. " +
            "It installs when the app next opens. If a new version ever fails to start, the app goes back to the previous one by itself.", style = MaterialTheme.typography.bodySmall)
    }
}

private fun restartForUpdateFromSettings() = pk.workshop.restartForUpdate()

@Composable
private fun BackupTab() {
    var dir by remember { mutableStateOf(Backup.defaultDir().path) }
    var restore by remember { mutableStateOf<java.io.File?>(null) }
    Section("Backups", Modifier.width(720.dp)) {
        Text("A backup runs automatically once a day and when the app closes. The last 30 are kept. Backups are encrypted: they open only with the admin password or recovery code that were valid when they were made.")
        Kv("Last backup", Dates.show(Settings.get(Settings.LAST_BACKUP)).ifBlank { "Never" })
        Kv("Backup folder", dir)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ App.act { val f = Backup.run(); App.toast("Backup saved: ${f.name}") } }) { IconText(Icons.Default.Backup, "Back up now") }
            OutlinedButton({ FilePick.folder("Backup folder (choose a USB drive or second disk)")?.let { Settings.set(Settings.BACKUP_DIR, it.path); dir = it.path } }) { Text("Change folder") }
            OutlinedButton({ App.act { Printers.open(Backup.defaultDir()) } }) { Text("Open folder") }
        }
        Text("Copy a backup to Google Drive regularly (or point the folder at a Google Drive sync folder) so a fire or theft does not take your records.", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("Restore", style = MaterialTheme.typography.titleSmall)
        OutlinedButton({ restore = FilePick.open("Choose a backup .zip", listOf(".zip")) }) { BtnText("Restore from a backup…") }
        Text("Data folder: ${Vault.dataDir.path}", style = MaterialTheme.typography.bodySmall)
    }
    restore?.let { f ->
        Confirm("Restore this backup?", "Everything since ${f.name} will be replaced, and the app will log out. Log in with the password from that time.", "Restore", {
            App.act { Backup.restore(f); App.loggedIn = false; App.screen = Screen.Dashboard }
        }, { restore = null })
    }
}

@Composable
private fun PasswordTab() {
    var cur by remember { mutableStateOf("") }; var p1 by remember { mutableStateOf("") }; var p2 by remember { mutableStateOf("") }
    var cur2 by remember { mutableStateOf("") }; var code by remember { mutableStateOf<String?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Section("Change password", Modifier.width(420.dp)) {
            Field("Current password", cur, { cur = it }, Modifier.fillMaxWidth(), password = true)
            Field("New password", p1, { p1 = it }, Modifier.fillMaxWidth(), password = true)
            Field("Repeat new password", p2, { p2 = it }, Modifier.fillMaxWidth(), password = true)
            Button({ App.act("Password changed") { if (p1 != p2) pk.workshop.db.fail("The new passwords do not match."); Vault.changePassword(cur, p1); cur = ""; p1 = ""; p2 = "" } }) { Text("Change password") }
        }
        Section("New recovery code", Modifier.width(460.dp)) {
            Text("Lost the recovery code? Make a new one; the old one stops working.")
            Field("Current password", cur2, { cur2 = it }, Modifier.fillMaxWidth(), password = true)
            Button({ App.act { code = Vault.newRecovery(cur2); cur2 = "" } }) { Text("Make new recovery code") }
            code?.let { c -> Surface(color = MaterialTheme.colorScheme.primaryContainer) { SelectionContainer { Text(c, fontFamily = FontFamily.Monospace, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(12.dp)) } }
                Text("Write it down and keep it outside the shop.") }
        }
    }
}
