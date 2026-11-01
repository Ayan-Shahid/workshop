package pk.workshop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import pk.workshop.data.Guide
import pk.workshop.data.Settings
import kotlin.math.roundToInt

/**
 * First-time help: a guided tour that points at the real menu items and buttons, a "How this works" panel on every
 * screen, and the Getting started checklist on the dashboard.
 */
object Help {
    /** Where tour targets are on screen, keyed by id. Filled by [tourAnchor]. */
    val anchors = mutableStateMapOf<String, Rect>()
    var tourOpen by mutableStateOf(false)
    var step by mutableStateOf(0)
    /** The tour being shown: the welcome tour, a screen tour or a training lesson. */
    var active by mutableStateOf<List<TourStep>>(emptyList())
    var activeId by mutableStateOf("main")

    /** One step: what to point at, what to say, and optionally where to go first (a screen, tab or job card). */
    class TourStep(val anchor: String?, val title: String, val text: String, val go: (() -> Unit)? = null)

    val tour = listOf(
        TourStep(null, "Welcome to Workshop Manager", "This short tour shows where everything is. It takes about a minute. You can replay it any time from Help & tour at the bottom of the menu."),
        TourStep("nav", "The menu", "Every part of the app is here. The keys on the right are shortcuts: F2 job cards, F3 counter sale, F4 customers, F5 inventory, F6 billing."),
        TourStep("dash.newjob", "A car arrives: New job card", "Start here for every car. Pick the car (or add a new customer and car), note the odometer, fuel and the customer's complaint."),
        TourStep("nav.Job cards", "Job cards", "Inside a job card: add parts (scan the barcode), labour and outside work. Then Make invoice → Receive payment → Deliver car, which prints the gate pass. Parts leave stock as soon as they're added."),
        TourStep("nav.Counter sale", "Counter sale", "For parts sold over the counter without a car in the workshop. Scan, take payment, and the receipt prints."),
        TourStep("nav.Billing", "Billing", "Every bill and payment. Open a bill to reprint it, send it on WhatsApp or make a credit note. Cheques and FBR invoice numbers are here too."),
        TourStep("nav.Customers", "Customers", "Search by name, phone, registration or chassis number. Each customer has their cars, full history, balance and a printable statement."),
        TourStep("nav.Inventory", "Inventory", "Parts, prices, barcode labels and stock counts. Running-low parts show on the dashboard."),
        TourStep("nav.Purchasing", "Purchasing", "Enter supplier bills here: stock goes up and the cost is averaged. Also supplier payments, orders, returns and warranty claims."),
        TourStep("nav.Bookkeeping", "Bookkeeping", "Bills, payments and purchases post to the books by themselves. You only add shop expenses, close the cash each evening and, once a month, tick off the bank statement."),
        TourStep("nav.Reports", "Reports", "Sales, profit, stock, money owed, technician work and tax. Every report saves as PDF or Excel."),
        TourStep("nav.Settings", "Settings", "Your business details, tax rates, printers, lists, theme, backups and password."),
        TourStep("nav.Training", "Training", "Step-by-step lessons for every task (a car from check-in to gate pass, counter sales, end of day, end of month) and a tour of each screen. Each screen also shows its own tour the first time you open it."),
        TourStep("dash.guide", "Getting started", "Work down this list to set the workshop up. Each step ticks itself off when it's done, and Go takes you straight there."),
        TourStep("help.button", "Help on every screen", "This button opens short instructions for the screen you're on. They also open by themselves the first time you visit a screen."),
    )

    fun start() { App.go(Screen.Dashboard); run("main", tour) }
    fun run(id: String, steps: List<TourStep>) { if (steps.isEmpty()) return; activeId = id; active = steps; step = 0; tourOpen = true }
    /** Reached the last step: the lesson counts as done. */
    fun finish() {
        tourOpen = false
        if (activeId == "main") Settings.setBool(Guide.TOUR_DONE, true) else Settings.setBool("guide.done.$activeId", true)
    }
    /** Skipped part-way: the welcome tour still won't reappear, but a lesson stays unticked. */
    fun skip() {
        tourOpen = false
        if (activeId == "main") Settings.setBool(Guide.TOUR_DONE, true)
    }
    fun isDone(id: String) = Settings.bool("guide.done.$id")
    fun next() { if (step < active.lastIndex) step++ else finish() }
    fun back() { if (step > 0) step-- }
    /** Screen tours start by themselves the first time a screen is opened, unless switched off. */
    var autoTours: Boolean
        get() = !Settings.bool("guide.autotours.off")
        set(v) { Settings.setBool("guide.autotours.off", !v) }

    /** "How this works" text per screen. */
    fun instructions(s: Screen): List<String>? = when (s) {
        Screen.Dashboard -> listOf(
            "Tiles show today's money; click one to open the details behind it.",
            "Service reminders: press the message icon to send the customer a WhatsApp reminder.",
            "Low stock lists parts at or below their reorder level. Order them from Purchasing.")
        Screen.Jobs -> listOf(
            "New job card for every car that comes in. A booked car: use Car arrived in Bookings instead.",
            "Find a job by registration, phone or job number, or scan the QR code on the printed job card.",
            "Tick or untick Open only to see finished jobs. The chips filter by status.")
        is Screen.Job -> listOf(
            "1. Add the work: Part (or scan its barcode in the box), Labour, or Sublet for outside work.",
            "2. If the customer must agree first: Estimate prints a quote, then Record approval.",
            "3. Start timer when a mechanic begins; change the status as the job moves along.",
            "4. Make invoice when the work is done, then Receive payment, then Deliver car (prints the gate pass).",
            "Parts come out of stock as soon as they're added; removing a line puts them back.")
        Screen.Bookings -> listOf(
            "New booking for an appointment, with pickup and driver if you collect the car.",
            "Car arrived turns the booking into a job card with the details filled in.",
            "The message icon sends the customer a reminder on WhatsApp.")
        is Screen.CounterSale -> listOf(
            "Scan a barcode or search for the part; change quantity, price or discount on the line.",
            "Pay takes cash, card or wallet; enter part of the amount to split between methods.",
            "Sell on credit (udhaar) puts the bill on a customer's account instead.",
            "The receipt prints by itself; Reprint last receipt is there if the printer jams.")
        Screen.Billing -> listOf(
            "Open any bill to print it, send it on WhatsApp, take payment or make a credit note for returns.",
            "Cheques: when the bank clears one, mark it Cleared; if it bounces, mark it Bounced and the amount goes back on the customer's account.",
            "FBR queue: enter the FBR invoice number for each bill once you have it.")
        Screen.Customers -> listOf(
            "Search by name, phone, CNIC, registration or chassis number.",
            "Open a customer for their cars, history, balance, statement and to take a payment.",
            "Set a credit limit for account customers; the app warns before going over it.")
        is Screen.Customer -> listOf(
            "Receive payment records money against this customer's oldest bills first.",
            "Statement makes a PDF of all bills and payments for a period.",
            "Add or edit cars here; open a car's history to see every past visit.")
        Screen.Inventory -> listOf(
            "Scan to find or add: scan a barcode to open that part, or add it if it's new.",
            "Use the label icon on a part to print barcode labels for the shelf or each unit.",
            "Remove (damage, loss) takes stock out with a reason; Stock counts fixes counts after a stock-take.",
            "To load many parts at once: Template → fill it in Excel → Import Excel.")
        Screen.Purchasing -> listOf(
            "Enter supplier bill when goods arrive: stock goes up and the average cost updates.",
            "Pay a supplier from the Suppliers tab; Statement shows what you owe and why.",
            "Purchase orders: when the goods come, Goods arrived turns the order into a bill.",
            "Faulty part under warranty: New claim, then Resolve when the supplier replaces or credits it.")
        Screen.Books -> listOf(
            "Bills, payments, purchases and salaries post themselves. Use Expenses for rent, bills, fuel and other costs.",
            "Every evening: Cash closing. Count the notes; any difference is recorded.",
            "Once a month: Bank reconciliation (tick what's on the bank statement), then lock the month in Month locks.",
            "Mistakes: open the voucher and choose Edit or Delete. Entries made by a bill, payment or salary are changed on that bill, payment or salary.")
        Screen.Staff -> listOf(
            "Staff holds everyone with their salary; Technicians are the people chosen on labour lines.",
            "Give advance records money given early; Pay salary deducts it and adds commission.",
            "Attendance: mark by hand, or import the log from your fingerprint machine.")
        Screen.Reports -> listOf(
            "Pick a report on the left and set the dates (or use Today, This month, This fiscal year).",
            "PDF, Print and Excel save or print exactly what's on screen.")
        Screen.Training -> listOf(
            "Lessons walk you through a whole job from start to finish, moving between screens for you.",
            "Screen tours point at every button on one screen.",
            "Practise on the Sample data app so nothing you try touches your real records.")
        Screen.Settings -> listOf(
            "Fill in Business first; it prints on every bill.",
            "Backup runs every day by itself; choose a USB or cloud-synced folder for it.",
            "Password: keep the recovery code safe. Without the password or the code, nobody can open the data.")
    }

    /** Opens on the first visit to each screen; after that, the ? button. */
    fun firstVisit(key: String): Boolean = !Settings.bool("guide.seen.$key")
    fun markSeen(key: String) = Settings.setBool("guide.seen.$key", true)
    fun keyOf(s: Screen) = s.title
}

/** Marks something the tour can point at. */
fun Modifier.tourAnchor(id: String): Modifier = composed {
    DisposableEffect(id) { onDispose { Help.anchors.remove(id) } }
    onGloballyPositioned { Help.anchors[id] = it.boundsInRoot() }
}

/** The tour: dims the screen, cuts a lit window around the current target, and shows a step card beside it. */
@Composable
fun TourOverlay() {
    if (!Help.tourOpen) return
    val steps = Help.active
    if (steps.isEmpty()) return
    val step = steps[Help.step.coerceIn(0, steps.lastIndex)]
    LaunchedEffect(Help.activeId, Help.step) { try { step.go?.invoke() } catch (e: Exception) { e.printStackTrace() } }
    val target = step.anchor?.let { Help.anchors[it] }
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Button targets mark the label inside the button, so widen the lit window to the whole button
    val padX = with(density) { (if (step.anchor?.startsWith("btn.") == true) 22.dp else 6.dp).toPx() }
    val padY = with(density) { (if (step.anchor?.startsWith("btn.") == true) 11.dp else 6.dp).toPx() }
    val primary = MaterialTheme.colorScheme.primary
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    fun next() = Help.next()
    fun back() = Help.back()
    Box(Modifier.fillMaxSize()
        .pointerInput(Unit) { detectTapGestures { } }            // the app underneath can't be clicked during the tour
        .onPreviewKeyEvent {
            if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                Key.Escape -> { Help.skip(); true }
                Key.Enter, Key.DirectionRight -> { next(); true }
                Key.DirectionLeft -> { back(); true }
                else -> false
            }
        }
        .focusRequester(focus).focusable()) {
        LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: Exception) {} }
        Canvas(Modifier.fillMaxSize()) {
            val scrim = Color.Black.copy(alpha = 0.6f)
            if (target == null) drawRect(scrim)
            else {
                val hole = RoundRect(target.left - padX, target.top - padY, target.right + padX, target.bottom + padY, CornerRadius(14f, 14f))
                val path = Path().apply { fillType = PathFillType.EvenOdd; addRect(Rect(Offset.Zero, size)); addRoundRect(hole) }
                drawPath(path, scrim)
                drawRoundRect(primary, Offset(hole.left, hole.top), Size(hole.width, hole.height), CornerRadius(14f, 14f), style = Stroke(width = 3f))
            }
        }
        // Step card placed beside the target (right, then below, then above, then left), kept on screen
        Layout(content = { StepCard(step, Help.step, steps.size, ::next, ::back) }, modifier = Modifier.fillMaxSize()) { ms, c ->
            val card = ms.first().measure(Constraints(maxWidth = minOf(c.maxWidth, 400.dp.roundToPx()), maxHeight = c.maxHeight))
            val gap = 16.dp.roundToPx()
            layout(c.maxWidth, c.maxHeight) {
                val (x, y) = if (target == null) ((c.maxWidth - card.width) / 2) to ((c.maxHeight - card.height) / 2) else {
                    val t = target
                    when {
                        t.right + gap + card.width < c.maxWidth -> (t.right + gap).roundToInt() to t.top.roundToInt()
                        t.bottom + gap + card.height < c.maxHeight -> t.left.roundToInt() to (t.bottom + gap).roundToInt()
                        t.top - gap - card.height > 0 -> t.left.roundToInt() to (t.top - gap - card.height).roundToInt()
                        else -> (t.left - gap - card.width).roundToInt() to t.top.roundToInt()
                    }
                }
                card.place(x.coerceIn(gap, (c.maxWidth - card.width - gap).coerceAtLeast(gap)), y.coerceIn(gap, (c.maxHeight - card.height - gap).coerceAtLeast(gap)))
            }
        }
    }
}

@Composable
private fun StepCard(step: Help.TourStep, index: Int, total: Int, next: () -> Unit, back: () -> Unit) {
    Surface(Modifier.width(380.dp).carbonCard(RoundedCornerShape(16.dp)), shape = RoundedCornerShape(16.dp),
        color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Step ${index + 1} of $total", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Text(step.title, style = MaterialTheme.typography.titleLarge)
            Text(step.text, style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator({ (index + 1f) / total }, Modifier.fillMaxWidth().padding(top = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ Help.skip() }) { Text("Skip tour") }
                Spacer(Modifier.weight(1f))
                if (index > 0) OutlinedButton(back) { Text("Back") }
                Button(next) { Text(if (index == total - 1) "Finish" else "Next") }
            }
        }
    }
}

/** "How this works" strip under a page title. */
@Composable
fun InstructionsPanel(lines: List<String>, onTour: (() -> Unit)? = null, onClose: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(bottom = 12.dp).carbonCard()
        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp), color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Icon(Icons.Default.Lightbulb, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp, end = 12.dp).size(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("How this works", fontWeight = FontWeight.SemiBold)
                lines.forEach { Text(if (it.firstOrNull()?.isDigit() == true) it else "•  $it", style = MaterialTheme.typography.bodyMedium) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    if (onTour != null) OutlinedButton(onTour) { IconText(Icons.Default.Explore, "Show me around this screen") }
                    TextButton({ App.go(Screen.Training) }) { Text("All lessons") }
                }
            }
            IconButton(onClose) { Icon(Icons.Default.Close, "Close") }
        }
    }
}

/** The Getting started checklist for the dashboard. Hidden once the owner dismisses it. */
@Composable
fun GettingStarted() {
    if (Settings.bool(Guide.HIDDEN)) return
    val steps = remember(App.version) { Guide.steps() }
    val done = steps.count { it.done }
    Section("Getting started · $done of ${steps.size} done", Modifier.fillMaxWidth().tourAnchor("dash.guide"), actions = {
        TextButton({ Help.start() }) { IconText(Icons.Default.Explore, "Take the tour") }
        TextButton({ Settings.setBool(Guide.HIDDEN, true); App.refresh() }) { Text(if (done == steps.size) "Done, hide this" else "Hide guide") }
    }) {
        LinearProgressIndicator({ done.toFloat() / steps.size }, Modifier.fillMaxWidth().padding(bottom = 8.dp))
        if (done == steps.size) Text("Everything is set up. You can hide this guide; the tour and screen help stay available.")
        val half = (steps.size + 1) / 2
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            listOf(steps.take(half), steps.drop(half)).forEach { col ->
                Column(Modifier.weight(1f)) {
                    col.forEach { s ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (s.done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null,
                                tint = if (s.done) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline, modifier = Modifier.size(22.dp))
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(s.title, fontWeight = FontWeight.SemiBold, color = if (s.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                                if (!s.done) Text(s.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (!s.done) {
                                if (s.skippable) TextButton({ Guide.skip(s.key); App.refresh() }) { Text("Skip") }
                                OutlinedButton({ App.openTab(s.target, s.tab) }) { Text("Go") }
                            }
                        }
                    }
                }
            }
        }
    }
}
