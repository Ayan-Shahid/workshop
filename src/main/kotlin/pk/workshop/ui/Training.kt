package pk.workshop.ui

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
import pk.workshop.data.Customers
import pk.workshop.data.Jobs
import pk.workshop.ui.Help.TourStep

/**
 * Training: a guided tour of every screen (pointing at its real buttons and fields) and step-by-step lessons
 * for each everyday task that move between screens on their own.
 */
object Training {
    private fun S(anchor: String?, title: String, text: String, go: (() -> Unit)? = null) = TourStep(anchor, title, text, go)
    private fun to(s: Screen): () -> Unit = { if (App.screen != s) App.go(s) }
    private fun tab(screen: String, i: Int): () -> Unit = { App.openTab(screen, i) }

    /** Opens a real job card from the data so the lesson can point at its buttons. */
    private fun job(open: Boolean, billed: Boolean? = null): () -> Unit = {
        val all = Jobs.list(openOnly = open)
        val j = all.firstOrNull { billed == null || (it.invoiceId != null) == billed } ?: all.firstOrNull() ?: Jobs.list().firstOrNull()
        if (j != null) App.go(Screen.Job(j.id)) else App.go(Screen.Jobs)
    }
    private val customer: () -> Unit = {
        val c = Customers.list().firstOrNull { Customers.balance(it.id) > 0 } ?: Customers.list().firstOrNull()
        if (c != null) App.go(Screen.Customer(c.id)) else App.go(Screen.Customers)
    }

    // ---------------------------------------------------------------- screen tours

    private val jobsTour = listOf(
        S("btn.New job card", "Start a job card", "Press New job card for every car that comes in. In the box that opens: choose the car (or add a new customer and car), type the odometer, set the fuel level, department and promised date, and write the customer's complaint in their own words. The quick-pick complaints save typing."),
        S("search", "Find a job", "Type a registration number, the customer's phone or a job number."),
        S("scan", "Scan the job card", "The printed job card has a QR code. Scan it here to jump straight to that job, for example when the customer comes to collect."),
        S("check.Open only", "Open or all jobs", "Normally you see only cars still in the workshop. Untick Open only to see delivered and cancelled jobs as well. The status chips filter the list."),
        S("table", "Open a job", "Click any row to open its job card, where all the work is done."),
    )

    private val jobTour = listOf(
        S("sec.Customer and vehicle", "Who and which car", "The customer, the car and when it came in. Open customer history lists every earlier visit of this car with its odometer reading."),
        S("sec.Check-in", "Check-in details", "Odometer, fuel, department, bay, promised date, the complaint and your diagnosis. Change anything and press Save."),
        S("field.Status", "Move the job along", "Set the stage as work goes on: Inspecting, Awaiting approval, Awaiting parts, In progress, Quality check. The dashboard and the job list follow it."),
        S("sec.Work and parts", "The work on this car", "Everything here goes on the bill: parts, labour and outside work."),
        S("scan", "Add a part by scanning", "Scan the part's barcode and it is added to the job and taken out of stock straight away. Removing a line puts it back in stock."),
        S("btn.Part", "Or pick a part", "Part opens the parts list to search by name, part number or brand. You can also record a part the customer brought in themselves (no charge, no stock)."),
        S("btn.Labour", "Labour", "Pick a job from your labour list (or type your own), set the price and choose the mechanic who did it. That's how each mechanic's commission is worked out."),
        S("btn.Sublet", "Outside work", "Sublet is work done by another shop, such as denting or machining. Enter what they charge you and what you charge the customer; the difference is your profit."),
        S("btn.Estimate", "Quote for the customer", "Estimate prints a quote with today's prices. Send it on WhatsApp or hand it over."),
        S("btn.Record approval", "Customer's go-ahead", "When the customer agrees, record who approved and how (phone, in person, WhatsApp). The job moves to In progress."),
        S("btn.Start timer", "Time on the job", "Start the timer when a mechanic begins and stop it when they finish. Time per job shows in the technician report."),
        S("btn.Make invoice", "Make the bill", "When the work is done, press Make invoice. Sales tax on parts and services tax on labour are added for you. For an insurance claim, choose the insurer and the amount they pay here."),
        S("btn.Deliver car", "Hand the car back", "When the customer collects: Deliver car. If the bill isn't paid it asks for payment first (or delivery on credit for account customers), then prints the gate pass for the guard."),
        S("btn.Car ready", "Tell the customer", "Car ready opens WhatsApp with a message that the car is ready and the amount to pay."),
        S("sec.Photos and scanner reports", "Photos and papers", "Attach photos of damage at check-in, scanner printouts or the customer's papers. They stay with the job."),
    )

    private val bookingsTour = listOf(
        S("btn.New booking", "Take a booking", "Enter the customer, car, date and time, and the work wanted. Tick pickup to note the address and the driver who will collect the car."),
        S("table", "The booking list", "Today's and upcoming bookings in time order. Change the dates at the top to look further ahead."),
        S("btn.Car arrived", "When the car arrives", "Car arrived turns the booking into a job card with the details already filled in."),
    )

    private val counterTour = listOf(
        S("scan", "Scan to sell", "Scan the part's barcode; scanning it again adds one more."),
        S("btn.Find part", "Or search", "Find part searches by name, part number or brand."),
        S("btn.Labour / service", "Add a service", "For small work done at the counter, such as fitting a bulb."),
        S("table", "Change a line", "Change the quantity, rate or discount directly in the line. The bin icon removes it. A warning shows if you sell more than is in stock."),
        S("btn.Customer", "Who is buying", "Leave it as Walk-in customer, or choose a customer to give them loyalty points or sell on credit."),
        S("field.Discount on whole bill", "Discount", "A discount on the whole bill is spread across the lines so the tax stays correct."),
        S("btn.Take payment", "Take payment", "Saves the sale and asks how they're paying: cash (shows the change to give), card, JazzCash, Easypaisa, bank transfer or cheque. Enter part of the amount to split between two methods. The receipt prints by itself."),
        S("btn.Sell on credit (udhaar)", "Sell on credit", "For account customers: the bill goes on their account and is collected later from Customers or Billing."),
    )

    private val billingTour = listOf(
        S("tab.Invoices", "Every bill", "All bills for the dates shown. Click a bill to reprint it, send it on WhatsApp, take payment or make a credit note."),
        S("check.Unpaid only", "Who still owes", "Tick Unpaid only to see bills with money still due."),
        S("tab.Payments received", "Money in", "Every payment taken, with method and reference. Cash, card, wallets and bank are totalled separately at the bottom.", tab("Billing", 1)),
        S("tab.Cheques", "Cheques", "Cheques waiting to be cashed. When the bank clears one, press Cleared; if it bounces, press Bounced and the amount goes back on the customer's account.", tab("Billing", 2)),
        S("tab.FBR queue", "FBR invoice numbers", "Bills waiting for their FBR number. Enter it when your integrator gives it; it then prints on the bill with its QR code.", tab("Billing", 3)),
    )

    private val customersTour = listOf(
        S("search", "Find a customer", "Search by name, phone, CNIC, registration number or chassis number."),
        S("btn.New customer", "Add a customer", "Name and mobile number are enough to start. For companies add the NTN, a credit limit and payment terms."),
        S("table", "Open a customer", "Click a row for their cars, bills, balance and statement."),
    )

    private val customerTour = listOf(
        S("sec.Details", "Their details and balance", "Phone, address, type, credit limit and how much they owe (or are owed)."),
        S("btn.Receive payment", "Take a payment", "Records money against their oldest bills first. Companies can deduct income tax; enter it and it's recorded too."),
        S("btn.Statement", "Statement", "A PDF of all bills and payments for any period, ready to send."),
        S("btn.Payment reminder", "Remind them", "Opens WhatsApp with a polite reminder of the amount due."),
        S("sec.Vehicles", "Their cars", "Add or edit cars here. Each car keeps its own service history and next-service reminder."),
        S("btn.New job card", "Straight to work", "New job card and Sell parts start a job or counter sale for this customer."),
    )

    private val inventoryTour = listOf(
        S("search", "Find a part", "Search by name, part number or brand."),
        S("scan", "Scan to find or add", "Scan a barcode to open that part, or to add it if it's new."),
        S("btn.New part", "Add a part", "Name, part number, brand, grade, shelf, sale price and reorder level. Enter the opening stock with its cost. Scan the maker's barcode, or leave it blank and the app makes one."),
        S("btn.Import Excel", "Many parts at once", "Press Template, fill it in Excel, then Import Excel."),
        S("table", "The stock list", "The icons on each row: adjust stock (damage, loss or found), print barcode labels, and archive a part you no longer sell."),
        S("check.Low stock only", "What to order", "Shows only parts at or below their reorder level."),
        S("tab.Stock counts", "Counting stock", "Start a count, scan or type what's on the shelf, then Approve count. Differences are corrected and recorded.", tab("Inventory", 1)),
        S("tab.Price update", "Price changes", "Raise or lower sale prices for a brand or category by a percentage in one go.", tab("Inventory", 2)),
        S("tab.Stock movements", "Where stock went", "Every movement of every part: bought, sold, used on jobs, returned, adjusted.", tab("Inventory", 3)),
    )

    private val purchasingTour = listOf(
        S("tab.Suppliers", "Suppliers", "Your suppliers and what you owe each. Pay records a payment; Statement shows the bills and payments behind the balance.", tab("Purchasing", 0)),
        S("btn.Enter supplier bill", "Goods arrived with a bill", "Enter the supplier's bill: parts, quantities and costs. Freight or loading charges are spread over the parts' cost. Stock goes up and the average cost updates. Pay now, or leave it on account.", tab("Purchasing", 1)),
        S("tab.Purchase orders", "Ordering", "Make an order to send to the supplier. When the goods come, Goods arrived turns it into a bill.", tab("Purchasing", 2)),
        S("tab.Returns", "Returning parts", "Wrong or faulty parts sent back: stock goes down and the supplier owes you.", tab("Purchasing", 3)),
        S("tab.Warranty claims", "Warranty", "A part failed under warranty: New claim, then Resolve when the supplier replaces it or gives credit.", tab("Purchasing", 4)),
    )

    private val booksTour = listOf(
        S("tab.Vouchers", "Everything in the books", "Bills, payments, purchases and salaries are entered by the app. Each entry is a voucher you can open. To fix a mistake, open the voucher and choose Edit or Delete.", tab("Books", 0)),
        S("btn.New voucher", "Your own entries", "Cash payment (CPV), cash receipt (CRV), bank payment (BPV), bank receipt (BRV) or journal (JV), for anything the app doesn't enter itself, such as the owner taking money out.", tab("Books", 0)),
        S("sec.Add an expense", "Shop expenses", "Rent, electricity, fuel, tea, tools: choose the type, amount and how it was paid.", tab("Books", 1)),
        S("sec.Close the day", "Every evening", "Count the cash in the drawer and enter it. The app shows what should be there; any short or extra amount is recorded.", tab("Books", 2)),
        S("tab.Ledger", "One account in detail", "Choose any account, such as cash in hand or a customer, to see every entry with its running balance.", tab("Books", 3)),
        S("tab.Profit & loss", "Did we make money?", "Income minus costs for any period. Trial balance and Balance sheet are next to it for your accountant.", tab("Books", 5)),
        S("tab.Bank reconciliation", "Monthly bank check", "Tick each entry that appears on the bank statement. What's left unticked is not yet in the bank.", tab("Books", 8)),
        S("tab.Fixed assets", "Equipment", "Lifts, compressors, scanners. Depreciation is charged monthly.", tab("Books", 10)),
        S("tab.Opening balances", "Starting figures", "On your first day: cash, bank and what customers and suppliers owe. Enter these once.", tab("Books", 11)),
        S("sec.Lock a month", "Lock checked months", "Once a month is checked, lock it so nothing can be changed by accident. You can unlock it if needed.", tab("Books", 12)),
    )

    private val staffTour = listOf(
        S("btn.Add staff", "Your staff", "Name, role, phone, salary and the attendance machine number.", tab("Staff", 0)),
        S("btn.Give advance", "Advances", "Money given before payday. It's taken off the salary automatically.", tab("Staff", 0)),
        S("btn.Add technician", "Mechanics", "The people chosen on labour lines, with their commission percentage.", tab("Staff", 1)),
        S("btn.Pay salary", "Paying salaries", "Shows basic pay plus commission earned minus advances. Choose cash or bank and pay.", tab("Staff", 2)),
        S("btn.Import machine log", "Attendance", "Mark attendance by hand, or import the file from your fingerprint machine.", tab("Staff", 3)),
    )

    private val reportsTour = listOf(
        S("reports.list", "Choose a report", "Sales, profit, stock, money owed, suppliers, mechanics, customers and tax."),
        S("field.From", "Choose the dates", "Set From and To, or use Today, This month or This fiscal year."),
        S("btn.PDF", "Save or print", "PDF and Print give a clean printout; Excel opens it as a spreadsheet for your accountant."),
    )

    private val settingsTour = listOf(
        S("tab.Business", "Your business", "Name, address, phone, NTN and STRN print on every bill. Also loyalty points and credit warnings.", tab("Settings", 0)),
        S("tab.Tax and FBR", "Tax rates", "FBR sales tax on parts and your province's services tax on labour. When rates change in a budget, add the new rate from its start date; old bills keep the old rate.", tab("Settings", 1)),
        S("tab.Printers and machines", "Printers and machines", "Choose the receipt, A4 and label printers and test each one. Turn on the cash drawer and the customer display here.", tab("Settings", 2)),
        S("tab.Lists", "Your lists", "Labour jobs and prices, and the quick-pick complaints used at check-in.", tab("Settings", 3)),
        S("btn.Back up now", "Backups", "A backup is made every day by itself. Choose a USB or Google Drive folder so a broken PC doesn't take your records. Restore brings a backup back.", tab("Settings", 5)),
        S("tab.Password", "Password", "Change the password, or make a new recovery code. Keep the code safe: without the password or the code, nobody can open the data.", tab("Settings", 6)),
    )

    fun screenTour(s: Screen): List<TourStep>? = when (s) {
        Screen.Jobs -> jobsTour
        is Screen.Job -> jobTour
        Screen.Bookings -> bookingsTour
        is Screen.CounterSale -> counterTour
        Screen.Billing -> billingTour
        Screen.Customers -> customersTour
        is Screen.Customer -> customerTour
        Screen.Inventory -> inventoryTour
        Screen.Purchasing -> purchasingTour
        Screen.Books -> booksTour
        Screen.Staff -> staffTour
        Screen.Reports -> reportsTour
        Screen.Settings -> settingsTour
        else -> null
    }

    // ---------------------------------------------------------------- lessons (tasks across screens)

    class Lesson(val id: String, val title: String, val what: String, val steps: List<TourStep>)

    val lessons = listOf(
        Lesson("car", "A car comes in: job card to gate pass", "The everyday job: check the car in, add the work, bill it, take payment and hand it back.", listOf(
            S(null, "The whole job, start to finish", "This lesson follows one car through the workshop. It opens the screens for you; press Next to move on.", to(Screen.Dashboard)),
            S("btn.New job card", "1. The car arrives", "Press New job card (or F2). Choose the car, or add the customer and car if they're new. Note the odometer, fuel level and the complaint.", to(Screen.Jobs)),
            S("sec.Check-in", "2. The job card", "This is the job card. Check-in details are at the left; write your diagnosis once the car is inspected.", job(open = true, billed = false)),
            S("btn.Job card", "3. Print the job card", "Print the job card for the mechanic, and the key tag for the key board."),
            S("scan", "4. Add parts", "Scan each part's barcode (or press Part). Parts come out of stock at once."),
            S("btn.Labour", "5. Add labour", "Add each labour item with the mechanic who did it."),
            S("btn.Estimate", "6. Quote and approval", "If the customer must agree first: Estimate prints a quote, then Record approval notes who agreed."),
            S("field.Status", "7. Keep the status current", "In progress, Awaiting parts, Quality check. Everyone can see where each car is."),
            S("btn.Make invoice", "8. Make the bill", "When the work is done, press Make invoice. Taxes are worked out for you."),
            S("btn.Deliver car", "9. Payment and gate pass", "This job is already billed. When the customer comes: Deliver car, take payment (cash shows the change), and the gate pass prints.", job(open = true, billed = true)),
            S("btn.Car ready", "Tip: tell them it's ready", "Car ready sends the customer a WhatsApp message with the amount, so they come prepared."),
        )),
        Lesson("counter", "Selling parts over the counter", "Quick sales without a car in the workshop, including split payments and credit.", listOf(
            S("scan", "Scan the parts", "Open Counter sale (F3) and scan each part. Scanning again adds one more.", to(Screen.CounterSale())),
            S("table", "Check the lines", "Change the quantity, price or discount in the line if needed."),
            S("btn.Customer", "Who's buying", "Walk-in by default. Choose a regular customer to give them points or sell on credit."),
            S("btn.Take payment", "Take payment", "Choose the method. For a split, enter the cash part first, then pay the rest by card or wallet. The receipt prints."),
            S("btn.Sell on credit (udhaar)", "Or on account", "For account customers the bill goes on their account instead."),
        )),
        Lesson("paid", "Getting paid and chasing what's owed", "Payments, cheques, statements and reminders.", listOf(
            S("check.Unpaid only", "Who owes money", "In Billing, tick Unpaid only to see bills not yet fully paid.", to(Screen.Billing)),
            S("btn.Receive payment", "Take a payment", "On the customer's page, Receive payment records money against their oldest bills first.", customer),
            S("btn.Statement", "Send a statement", "A PDF of their bills and payments for any period."),
            S("btn.Payment reminder", "Remind them", "Opens WhatsApp with a polite reminder."),
            S("tab.Cheques", "Cheques", "When a cheque is paid in and clears, mark it Cleared. If it bounces, mark it Bounced; the amount goes back on their account.", tab("Billing", 2)),
            S("reports.list", "Who owes what, by age", "In Reports, choose Receivables ageing to see what's owed for 30, 60 or 90+ days.", to(Screen.Reports)),
        )),
        Lesson("bookings", "Bookings and pickups", "Taking appointments and turning them into job cards.", listOf(
            S("btn.New booking", "Take the booking", "Customer, car, date, time and the work wanted. Tick pickup to note the address and driver.", to(Screen.Bookings)),
            S("table", "The day's bookings", "Check this each morning, and send reminders with the message icon."),
            S("btn.Car arrived", "When the car arrives", "Car arrived makes the job card for you."),
        )),
        Lesson("buy", "Buying stock from suppliers", "Supplier bills, payments, orders, returns and warranty.", listOf(
            S("check.Low stock only", "What needs ordering", "In Inventory, tick Low stock only.", to(Screen.Inventory)),
            S("tab.Purchase orders", "Order it", "Make a purchase order for the supplier.", tab("Purchasing", 2)),
            S("btn.Enter supplier bill", "Goods arrive", "Enter the supplier's bill (or press Goods arrived on the order). Stock goes up and costs update.", tab("Purchasing", 1)),
            S("tab.Suppliers", "Pay the supplier", "Pay records the payment. Statement shows what's owed.", tab("Purchasing", 0)),
            S("tab.Returns", "Wrong or faulty parts", "Return parts to supplier takes them out of stock and reduces what you owe.", tab("Purchasing", 3)),
        )),
        Lesson("stock", "Looking after stock", "Adding parts, labels, counting and price changes.", listOf(
            S("btn.New part", "Add parts", "One at a time with New part, or many at once with Template and Import Excel.", to(Screen.Inventory)),
            S("table", "Labels and corrections", "On each row: print barcode labels, or adjust stock for damage, loss or found items (a reason is required)."),
            S("tab.Stock counts", "Count the shelves", "Start a count, scan what's on the shelf, then Approve count to fix the differences.", tab("Inventory", 1)),
            S("tab.Price update", "Change prices", "When a brand's prices go up, change them all by a percentage.", tab("Inventory", 2)),
        )),
        Lesson("day", "End of the day", "Ten minutes before closing.", listOf(
            S("sec.Add an expense", "1. Enter the day's expenses", "Fuel, tea, small purchases paid from the drawer.", tab("Books", 1)),
            S("sec.Close the day", "2. Count the cash", "Count the notes and enter the total. Any difference is recorded so the books match the drawer.", tab("Books", 2)),
            S("tab.Cheques", "3. Cheques", "Mark any cheques that cleared at the bank today.", tab("Billing", 2)),
            S("btn.Back up now", "4. Backup to USB (optional)", "A backup runs by itself every day; with a USB folder chosen it's also safe if the PC fails.", tab("Settings", 5)),
        )),
        Lesson("month", "End of the month", "Salaries, bank check, tax and locking the month.", listOf(
            S("btn.Pay salary", "1. Salaries", "Pay each person: basic + commission − advances.", tab("Staff", 2)),
            S("tab.Bank reconciliation", "2. Bank check", "Tick each entry that's on the bank statement.", tab("Books", 8)),
            S("tab.FBR queue", "3. FBR numbers", "Make sure every bill has its FBR number.", tab("Billing", 3)),
            S("reports.list", "4. Tax for the return", "Run the Tax summary report for the month: sales tax for FBR and services tax for the province.", to(Screen.Reports)),
            S("tab.Profit & loss", "5. How did we do?", "Profit & loss for the month.", tab("Books", 5)),
            S("sec.Lock a month", "6. Lock the month", "Once checked, lock it so nothing changes by accident.", tab("Books", 12)),
        )),
        Lesson("staff", "Paying staff", "Advances, commission, salaries and attendance.", listOf(
            S("btn.Give advance", "Advances", "Record any money given before payday.", tab("Staff", 0)),
            S("btn.Add technician", "Commission", "Each mechanic's commission percentage on the labour they do.", tab("Staff", 1)),
            S("btn.Pay salary", "Payday", "Basic + commission − advances, paid in cash or by bank.", tab("Staff", 2)),
            S("btn.Import machine log", "Attendance", "Import the fingerprint machine file, or mark by hand.", tab("Staff", 3)),
        )),
        Lesson("setup", "Setting up a new workshop", "Everything to do on day one.", listOf(
            S("tab.Business", "1. Business details", "These print on every bill.", tab("Settings", 0)),
            S("tab.Tax and FBR", "2. Tax rates", "Check FBR and your province's rates.", tab("Settings", 1)),
            S("tab.Printers and machines", "3. Printers", "Choose and test the printers.", tab("Settings", 2)),
            S("btn.Back up now", "4. Backup folder", "Pick a USB or cloud-synced folder.", tab("Settings", 5)),
            S("btn.Add technician", "5. Mechanics", "Add each mechanic with their commission.", tab("Staff", 1)),
            S("btn.Import Excel", "6. Parts", "Fill the Excel template with your parts and opening stock, then import it.", to(Screen.Inventory)),
            S("tab.Opening balances", "7. Opening balances", "Cash, bank, and what customers and suppliers owe on day one.", tab("Books", 11)),
        )),
    )
}

/** The Training page: all lessons and screen tours in one place. */
@Composable
fun TrainingScreen() {
    Page("Training") {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Section("Practise without risk") {
                Text("Each lesson walks through one task, opening the screens for you and pointing at the buttons to press. " +
                    "To try things for real without touching your records, open the \"Workshop Manager - Sample data\" app (admin / demo123).")
            }
            Section("Lessons · ${Training.lessons.count { Help.isDone("lesson.${it.id}") }} of ${Training.lessons.size} done") {
                Training.lessons.forEach { l ->
                    val done = Help.isDone("lesson.${l.id}")
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (done) Icons.Default.CheckCircle else Icons.Default.School, null,
                            tint = if (done) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(l.title, fontWeight = FontWeight.SemiBold)
                            Text("${l.what} · ${l.steps.size} steps", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (done) OutlinedButton({ Help.run("lesson.${l.id}", l.steps) }) { Text("Again") }
                        else Button({ Help.run("lesson.${l.id}", l.steps) }) { Text("Start") }
                    }
                }
            }
            Section("Tour of each screen") {
                val screens = listOf(Screen.Jobs to "Job cards", Screen.Bookings to "Bookings", Screen.CounterSale() to "Counter sale", Screen.Billing to "Billing",
                    Screen.Customers to "Customers", Screen.Inventory to "Inventory", Screen.Purchasing to "Purchasing", Screen.Books to "Bookkeeping",
                    Screen.Staff to "Staff", Screen.Reports to "Reports", Screen.Settings to "Settings")
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ Help.start() }) { IconText(Icons.Default.Explore, "The whole app (welcome tour)") }
                    screens.forEach { (s, name) ->
                        OutlinedButton({ App.go(s); Training.screenTour(s)?.let { Help.run("screen.${s.title}", it) } }) { Text(name) }
                    }
                }
                Text("Job card and customer tours start from inside a job card or a customer's page (press ? then Show me around).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
            Section("Daily, weekly and monthly routine") {
                listOf(
                    "Every day" to "Job cards for every car · counter sales · Car ready messages · end of day: expenses, cash closing, cheques that cleared.",
                    "Every week" to "Low stock and supplier orders · pay suppliers · chase unpaid bills (Unpaid only, statements, reminders).",
                    "Every month" to "Salaries · bank reconciliation · FBR numbers on all bills · tax summary for the returns · profit & loss · lock the month.",
                    "Keyboard" to "F2 job cards · F3 counter sale · F4 customers · F5 inventory · F6 billing · Enter moves to the next step in a tour.",
                ).forEach { (k, v) -> Row(Modifier.padding(vertical = 4.dp)) { Text(k, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(120.dp)); Text(v, Modifier.weight(1f)) } }
            }
        }
    }
}
