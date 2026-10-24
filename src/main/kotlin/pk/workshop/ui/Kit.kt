package pk.workshop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import pk.workshop.core.Dates
import pk.workshop.core.Money
import pk.workshop.core.Qty
import pk.workshop.db.AppError
import java.io.File

/** App-wide UI state: navigation, messages, refresh. */
object App {
    var screen by mutableStateOf<Screen>(Screen.Dashboard)
    var message by mutableStateOf<String?>(null)
    var version by mutableStateOf(0)
    var theme by mutableStateOf("system")
    var loggedIn by mutableStateOf(false)
    val back = ArrayList<Screen>()

    fun go(s: Screen) { if (s != screen) { back.add(screen); if (back.size > 30) back.removeAt(0) }; screen = s }
    fun goBack() { if (back.isNotEmpty()) screen = back.removeAt(back.lastIndex) }
    fun refresh() { version++ }
    fun toast(m: String) { message = m }

    /** Runs a user action; rule violations show as a message instead of crashing. Returns true on success. */
    fun act(success: String? = null, block: () -> Unit): Boolean = try {
        val before = message; block(); refresh()
        if (success != null) message = if (message != before && message != null) "$success. $message" else success; true
    } catch (e: AppError) { message = e.message; false
    } catch (e: Exception) { e.printStackTrace(); message = "Something went wrong: ${e.message ?: e.javaClass.simpleName}"; false }
}

sealed class Screen(val title: String) {
    data object Dashboard : Screen("Dashboard")
    data object Jobs : Screen("Job cards")
    data class Job(val id: Long) : Screen("Job card")
    data object Bookings : Screen("Bookings")
    data object Billing : Screen("Billing")
    data class CounterSale(val customerId: Long? = null) : Screen("Counter sale")
    data object Customers : Screen("Customers")
    data class Customer(val id: Long) : Screen("Customer")
    data object Inventory : Screen("Inventory")
    data object Purchasing : Screen("Purchasing")
    data object Books : Screen("Bookkeeping")
    data object Staff : Screen("Staff")
    data object Reports : Screen("Reports")
    data object Settings : Screen("Settings")
}

// ---------------- inputs ----------------

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, singleLine: Boolean = true, minLines: Int = 1,
          enabled: Boolean = true, password: Boolean = false, numeric: Boolean = false, error: String? = null, trailing: (@Composable () -> Unit)? = null,
          placeholder: String? = null, onEnter: (() -> Unit)? = null, focus: FocusRequester? = null) {
    LabeledBox(label, value, onChange, modifier, singleLine = singleLine, minLines = minLines, enabled = enabled, error = error,
        placeholder = placeholder, trailing = trailing,
        visual = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else if (password) KeyboardType.Password else KeyboardType.Text, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onEnter?.invoke() }),
        fieldModifier = Modifier.let { m -> focus?.let { m.focusRequester(it) } ?: m }.let { m ->
            if (onEnter != null) m.onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) { onEnter(); true } else false } else m
        })
}

/** Money input that keeps the user's text and reports paisa (null when invalid). */
@Composable
fun MoneyField(label: String, paisa: Long?, onChange: (Long?) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    var text by remember(label) { mutableStateOf(if (paisa == null || paisa == 0L) "" else Money.plain(paisa).replace(",", "")) }
    LaunchedEffect(paisa) { val cur = Money.parse(text); if (cur != paisa && paisa != null) text = if (paisa == 0L) "" else Money.plain(paisa).replace(",", "") }
    Field(label, text, { text = it; onChange(Money.parse(it)) }, modifier, numeric = true, enabled = enabled,
        error = if (Money.parse(text) == null) "Not a valid amount" else null, placeholder = "0")
}

@Composable
fun QtyField(label: String, milli: Long?, onChange: (Long?) -> Unit, modifier: Modifier = Modifier) {
    var text by remember(label) { mutableStateOf(milli?.let { Qty.fmt(it) } ?: "") }
    LaunchedEffect(milli) { if (Qty.parse(text) != milli && milli != null) text = Qty.fmt(milli) }
    Field(label, text, { text = it; onChange(Qty.parse(it)) }, modifier, numeric = true,
        error = if (text.isNotBlank() && Qty.parse(text) == null) "Not a number" else null)
}

/** Date input in dd-MM-yyyy, reporting ISO. */
@Composable
fun DateField(label: String, iso: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var text by remember(label) { mutableStateOf(Dates.show(iso)) }
    LaunchedEffect(iso) { if (Dates.parse(text) != iso) text = Dates.show(iso) }
    Field(label, text, { text = it; Dates.parse(it)?.let(onChange) }, modifier.width(160.dp),
        error = if (text.isNotBlank() && Dates.parse(text) == null) "dd-mm-yyyy" else null)
}

@Composable
fun <T> Dropdown(label: String, options: List<T>, selected: T?, onSelect: (T) -> Unit, modifier: Modifier = Modifier, text: (T) -> String = { it.toString() }) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        LabeledBox(label, selected?.let(text) ?: "", {}, Modifier.fillMaxWidth(), readOnly = true, trailing = { Icon(Icons.Default.ArrowDropDown, null) })
        Box(Modifier.matchParentSize().clip(RoundedCornerShape(8.dp)).clickable { open = true })
        DropdownMenu(open, { open = false }, shape = RoundedCornerShape(10.dp), containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp, shadowElevation = 0.dp, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            options.forEach { o -> DropdownMenuItem(text = { Text(text(o)) }, onClick = { onSelect(o); open = false }) }
        }
    }
}

@Composable
fun Check(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onChange(!checked) }.padding(end = 8.dp)) {
        Checkbox(checked, onChange); Text(label)
    }
}

@Composable
fun SearchBox(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, hint: String = "Search", onEnter: (() -> Unit)? = null, focus: FocusRequester? = null) {
    Field(hint, value, onChange, modifier.widthIn(min = 240.dp), trailing = { Icon(Icons.Default.Search, null) }, onEnter = onEnter, focus = focus, placeholder = "Type to search")
}

/** A text box for USB barcode scanners (they type the code and press Enter). */
@Composable
fun ScanBox(onScan: (String) -> Unit, modifier: Modifier = Modifier, hint: String = "Scan or type a barcode, then Enter") {
    var t by remember { mutableStateOf("") }
    val f = remember { FocusRequester() }
    Field(hint, t, { t = it }, modifier.widthIn(min = 280.dp), trailing = { Icon(Icons.Default.QrCodeScanner, null) }, placeholder = "Scan, or type and press Enter",
        onEnter = { val c = t.trim(); t = ""; if (c.isNotEmpty()) onScan(c) }, focus = f)
    LaunchedEffect(Unit) { try { f.requestFocus() } catch (_: Exception) {} }
}

// ---------------- layout ----------------

@Composable
fun Page(title: String, actions: @Composable RowScope.() -> Unit = {}, back: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            if (back) IconButton({ App.goBack() }) { Icon(Icons.Default.ArrowBack, "Back") }
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
        content()
    }
}

@Composable
fun Section(title: String? = null, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.carbonCard(), shape = RoundedCornerShape(12.dp), color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
            }
            content()
        }
    }
}

@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier, accent: Boolean = false, icon: ImageVector? = null, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(12.dp)
    Surface(modifier.then(if (accent) Modifier.meshFill(shape) else Modifier.carbonCard(shape))
        .let { if (onClick != null) it.clip(shape).clickable(onClick = onClick) else it }, shape = shape,
        color = Color.Transparent, contentColor = if (accent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) Icon(icon, null, Modifier.size(18.dp).padding(end = 4.dp), tint = if (accent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary)
                Text(label, style = MaterialTheme.typography.labelMedium, color = if (accent) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            // Shrinks the figure to fit narrow cards instead of cutting it off
            androidx.compose.foundation.text.BasicText(value,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold,
                    color = if (accent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface),
                maxLines = 1, softWrap = false,
                autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 13.sp, maxFontSize = MaterialTheme.typography.headlineSmall.fontSize))
        }
    }
}

@Composable
fun Chip(text: String, color: Color = statusColor(text)) {
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.14f)) {
        Text(text, color = color, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp), maxLines = 1)
    }
}

@Composable
fun Tabs(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    SecondaryScrollableTabRow(selectedTabIndex = selected, edgePadding = 0.dp, containerColor = Color.Transparent, modifier = Modifier.padding(bottom = 12.dp)) {
        tabs.forEachIndexed { i, t -> Tab(selected = i == selected, onClick = { onSelect(i) }, text = { Text(t) }) }
    }
}

@Composable
fun Kv(label: String, value: String, modifier: Modifier = Modifier, bold: Boolean = false) {
    Row(modifier.padding(vertical = 2.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(180.dp).padding(end = 12.dp))
        Text(value, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
fun Empty(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

// ---------------- table ----------------

class C<T>(val title: String, val weight: Float = 1f, val end: Boolean = false, val content: (@Composable (T) -> Unit)? = null, val text: (T) -> String = { "" })

@Composable
fun <T> DataTable(rows: List<T>, cols: List<C<T>>, modifier: Modifier = Modifier, onClick: ((T) -> Unit)? = null, empty: String = "Nothing here yet",
                  footer: List<String>? = null) {
    Surface(modifier.carbonCard(), shape = RoundedCornerShape(12.dp), color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface) {
        Column {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f)).padding(horizontal = 12.dp, vertical = 10.dp)) {
                cols.forEach { c -> Text(c.title, Modifier.weight(c.weight), style = MaterialTheme.typography.labelLarge, textAlign = if (c.end) TextAlign.End else TextAlign.Start, maxLines = 1) }
            }
            if (rows.isEmpty()) Empty(empty)
            LazyColumn(Modifier.weight(1f, fill = false)) {
                itemsIndexed(rows) { i, r ->
                    val src = remember { MutableInteractionSource() }
                    val hovered by src.collectIsHoveredAsState()
                    Row(Modifier.fillMaxWidth()
                        .background(if (hovered && onClick != null) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else if (i % 2 == 1) MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.55f) else Color.Transparent)
                        .hoverable(src).let { m -> if (onClick != null) m.clickable { onClick(r) } else m }
                        .padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        cols.forEach { c ->
                            Box(Modifier.weight(c.weight), contentAlignment = if (c.end) Alignment.CenterEnd else Alignment.CenterStart) {
                                if (c.content != null) c.content.invoke(r) else Text(c.text(r), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = if (c.end) TextAlign.End else TextAlign.Start)
                            }
                        }
                    }
                }
            }
            if (footer != null) Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f)).padding(horizontal = 12.dp, vertical = 10.dp)) {
                // A footer with only its first entry is a summary line across the whole table
                if (footer.drop(1).all { it.isEmpty() }) Text(footer.firstOrNull() ?: "", fontWeight = FontWeight.SemiBold, maxLines = 1)
                else cols.forEachIndexed { i, c -> Text(footer.getOrElse(i) { "" }, Modifier.weight(c.weight), fontWeight = FontWeight.SemiBold, textAlign = if (c.end) TextAlign.End else TextAlign.Start, maxLines = 1) }
            }
        }
    }
}

// ---------------- dialogs ----------------

@Composable
fun FormDialog(title: String, onDismiss: () -> Unit, onSave: (() -> Unit)?, saveText: String = "Save", width: Dp = 560.dp,
               extraButtons: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(16.dp), color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(width).heightIn(max = 760.dp).carbonCard(RoundedCornerShape(16.dp))) {
            Column(Modifier.padding(24.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 16.dp))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    extraButtons()
                    TextButton(onDismiss) { Text(if (onSave == null) "Close" else "Cancel") }
                    if (onSave != null) Button(onSave) { Text(saveText) }
                }
            }
        }
    }
}

@Composable
fun Confirm(title: String, text: String, yes: String = "Yes", onYes: () -> Unit, onDismiss: () -> Unit) {
    FormDialog(title, onDismiss, { onYes(); onDismiss() }, saveText = yes, width = 460.dp) { Text(text) }
}

/** Searchable picker for parts, customers, vehicles and so on. */
@Composable
fun <T> Picker(title: String, search: (String) -> List<T>, label: (T) -> String, sub: (T) -> String = { "" }, onPick: (T) -> Unit, onDismiss: () -> Unit,
               createLabel: String? = null, onCreate: ((String) -> Unit)? = null) {
    var q by remember { mutableStateOf("") }
    val items = remember(q, App.version) { try { search(q) } catch (e: Exception) { emptyList() } }
    val f = remember { FocusRequester() }
    FormDialog(title, onDismiss, null, width = 620.dp, extraButtons = {
        if (onCreate != null && createLabel != null) TextButton({ onCreate(q) }) { Icon(Icons.Default.Add, null); Text(createLabel) }
    }) {
        SearchBox(q, { q = it }, Modifier.fillMaxWidth(), focus = f, onEnter = { items.firstOrNull()?.let(onPick) })
        LaunchedEffect(Unit) { try { f.requestFocus() } catch (_: Exception) {} }
        if (items.isEmpty()) Empty("No matches")
        items.take(60).forEach { t ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onPick(t) }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(label(t), fontWeight = FontWeight.Medium)
                val s = sub(t); if (s.isNotBlank()) Text(s, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---------------- files ----------------

object FilePick {
    fun open(title: String, exts: List<String> = emptyList()): File? {
        val d = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD)
        if (exts.isNotEmpty()) d.setFilenameFilter { _, n -> exts.any { n.lowercase().endsWith(it) } }
        d.isVisible = true
        return d.file?.let { File(d.directory, it) }
    }
    fun folder(title: String): File? {
        val ch = javax.swing.JFileChooser().apply { fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY; dialogTitle = title }
        return if (ch.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) ch.selectedFile else null
    }
}

@Composable
fun Toolbar(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom, content = content)
}

@Composable
fun IconText(icon: ImageVector, text: String) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(text) }

fun rs(p: Long) = Money.rs(p)
fun plain(p: Long) = Money.plain(p)
