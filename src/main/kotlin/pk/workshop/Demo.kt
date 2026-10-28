package pk.workshop

import pk.workshop.core.Dates
import pk.workshop.data.*
import pk.workshop.db.Db
import pk.workshop.security.Vault
import pk.workshop.ui.App
import pk.workshop.ui.Screen
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Sample data for trying every part of the app. Only runs when started with -Dworkshop.demo=1 on an empty data folder
 * (log in as admin / demo123). Normal installs never call this.
 *
 * It replays about four months of workshop life through the same functions the screens use, so every number in the
 * books, reports and dashboards is produced exactly as it would be in real use. Generation is deterministic.
 */
object Demo {
    fun maybeStart(): Boolean {
        if (System.getProperty("workshop.demo") != "1") return false
        if (!Vault.isSetUp()) {
            Vault.setup("admin", "demo123")
            println("Creating sample data (takes a few seconds)…")
            val t0 = System.currentTimeMillis()
            try { Db.tx { seed() } } finally { Dates.clock = null; Settings.clearCache() }
            println("Sample data ready in ${(System.currentTimeMillis() - t0) / 1000.0}s")
        } else Vault.login("admin", "demo123")
        afterLogin()
        App.screen = when (System.getProperty("workshop.screen")) {
            "jobs" -> Screen.Jobs; "job" -> Screen.Job(System.getProperty("workshop.id")?.toLongOrNull() ?: 1); "billing" -> Screen.Billing; "counter" -> Screen.CounterSale()
            "customers" -> Screen.Customers; "customer" -> Screen.Customer(System.getProperty("workshop.id")?.toLongOrNull() ?: 2); "inventory" -> Screen.Inventory; "purchasing" -> Screen.Purchasing
            "books" -> Screen.Books; "staff" -> Screen.Staff; "reports" -> Screen.Reports; "settings" -> Screen.Settings; "bookings" -> Screen.Bookings
            else -> Screen.Dashboard
        }
        System.getProperty("workshop.theme")?.let { App.theme = it }
        return true
    }

    // ------------------------------------------------------------------ helpers

    private val rnd = java.util.Random(2627)
    private fun rs(r: Int) = r * 100L
    private fun q(n: Double) = Math.round(n * 1000)
    private fun <T> pick(l: List<T>): T = l[rnd.nextInt(l.size)]
    private fun chance(p: Double) = rnd.nextDouble() < p
    private fun between(a: Int, b: Int) = a + rnd.nextInt(b - a + 1)
    private fun round(v: Long, to: Long) = (v + to / 2) / to * to
    private fun at(date: String, h: Int, m: Int = 0) { Dates.clock = LocalDate.parse(date).atTime(h, m) }
    private fun ean(base12: String): String {
        val sum = base12.mapIndexed { i, c -> (c - '0') * if (i % 2 == 0) 1 else 3 }.sum()
        return base12 + ((10 - sum % 10) % 10)
    }

    private class PartDef(val key: String, val name: String, val partNo: String, val brand: String, val category: String, val grade: String, val unit: String,
                          val location: String, val cost: Int, val price: Int, val reorder: Double, val target: Double, val ean: String? = null,
                          val fits: String = "", val restock: Boolean = true, val sells: Boolean = true)

    private val partDefs = listOf(
        PartDef("OF_T", "Oil filter Corolla / Yaris", "90915-YZZE1", "Toyota", "Filters", "Genuine", "pc", "A-1", 950, 1500, 5.0, 24.0, fits = "Corolla, Yaris, Prius"),
        PartDef("OF_H", "Oil filter City / Civic", "15400-RTA-003", "Honda", "Filters", "Genuine", "pc", "A-1", 1100, 1700, 5.0, 20.0, fits = "City, Civic, BR-V"),
        PartDef("OF_S", "Oil filter Suzuki", "16510-81420", "Suzuki", "Filters", "Genuine", "pc", "A-1", 650, 1000, 6.0, 24.0, fits = "Alto, Cultus, Wagon R, Mehran, Swift"),
        PartDef("AF_T", "Air filter Corolla", "17801-0T030", "Toyota", "Filters", "Genuine", "pc", "A-2", 1800, 2800, 3.0, 10.0),
        PartDef("AF_H", "Air filter City", "17220-RB0-000", "Honda", "Filters", "Genuine", "pc", "A-2", 1700, 2600, 3.0, 10.0),
        PartDef("AF_S", "Air filter Alto / Cultus", "13780M", "Suzuki", "Filters", "Genuine", "pc", "A-2", 550, 900, 4.0, 12.0),
        PartDef("OIL530", "Engine oil 5W-30", "HX8-5W30", "Shell", "Lubricants", "Genuine", "litre", "Drum 1", 1850, 2500, 20.0, 80.0),
        PartDef("OIL1040", "Engine oil 10W-40", "X5-10W40", "Zic", "Lubricants", "Genuine", "litre", "Drum 2", 1500, 2100, 20.0, 80.0),
        PartDef("OIL2050", "Engine oil 20W-50 (4 L pack)", "HAV-2050-4", "Havoline", "Lubricants", "Genuine", "pc", "L-1", 4800, 6200, 4.0, 14.0, ean = ean("896400012345")),
        PartDef("ATF", "ATF Dexron III", "TOT-ATF3", "Total", "Lubricants", "Genuine", "litre", "L-2", 1400, 2000, 6.0, 20.0),
        PartDef("BFLUID", "Brake fluid DOT4 (500 ml)", "BOS-DOT4", "Bosch", "Brakes", "Genuine", "pc", "B-1", 700, 1100, 4.0, 14.0, ean = ean("400152112345")),
        PartDef("PAD_T", "Brake pads front Corolla", "04465-02220", "Toyota", "Brakes", "Genuine", "set", "B-2", 6200, 8500, 2.0, 7.0),
        PartDef("PAD_H", "Brake pads front City", "45022-T9A-T01", "Honda", "Brakes", "Genuine", "set", "B-3", 6200, 8500, 2.0, 7.0),
        PartDef("PAD_S", "Brake pads front Alto / Cultus", "55810-M79", "Suzuki", "Brakes", "OEM-equivalent", "set", "B-3", 1800, 3000, 3.0, 10.0),
        PartDef("SHOE_T", "Brake shoes rear Corolla", "BS-COR", "Local", "Brakes", "Local", "set", "B-4", 1900, 3000, 2.0, 6.0),
        PartDef("PLUG_IR", "Spark plug Iridium", "ILKAR7B11", "NGK", "Ignition", "Genuine", "pc", "C-1", 750, 1200, 8.0, 40.0, ean = ean("087295123456")),
        PartDef("PLUG_STD", "Spark plug standard", "K16R-U", "Denso", "Ignition", "OEM-equivalent", "pc", "C-1", 300, 500, 8.0, 40.0),
        PartDef("BAT55", "Battery 55Ah", "NS60", "AGS", "Electrical", "Genuine", "pc", "D-1", 21500, 26500, 2.0, 6.0, ean = ean("899100055001")),
        PartDef("BAT70", "Battery 70Ah", "N70", "Osaka", "Electrical", "Genuine", "pc", "D-1", 27000, 33000, 1.0, 4.0, ean = ean("899100070001")),
        PartDef("WIPER", "Wiper blade 22\"", "AERO-22", "Bosch", "Accessories", "Genuine", "pc", "E-1", 900, 1500, 4.0, 16.0),
        PartDef("COOLANT", "Coolant 1 L (green)", "PRE-1L", "Prestone", "Lubricants", "Genuine", "pc", "L-3", 900, 1400, 6.0, 20.0),
        PartDef("ACGAS", "AC gas R134a (can)", "R134A", "Local", "AC", "Local", "pc", "C-4", 2100, 3000, 3.0, 10.0),
        PartDef("CABIN", "Cabin / AC filter", "DCF-COR", "Denso", "AC", "OEM-equivalent", "pc", "C-4", 1200, 2000, 3.0, 10.0),
        PartDef("TBELT", "Timing belt Cultus", "12761-80C00", "Suzuki", "Engine", "Genuine", "pc", "F-1", 3200, 4800, 1.0, 1.0, restock = false),
        PartDef("FBELT", "Fan belt (V-belt)", "GATES-4PK", "Gates", "Engine", "OEM-equivalent", "pc", "F-1", 1500, 2400, 2.0, 6.0),
        PartDef("SHOCK", "Shock absorbers front Corolla (pair)", "KYB-334", "KYB", "Suspension", "OEM-equivalent", "pair", "G-1", 16000, 21000, 1.0, 3.0),
        PartDef("LINK", "Stabilizer link", "555-SL", "555", "Suspension", "OEM-equivalent", "pc", "G-2", 1800, 2800, 2.0, 10.0, fits = "Corolla, City, Yaris"),
        PartDef("BUSH", "Lower arm bush", "LAB-01", "Local", "Suspension", "Local", "pc", "G-2", 800, 1500, 2.0, 10.0),
        PartDef("H4", "Headlamp bulb H4", "PH-H4", "Philips", "Electrical", "Genuine", "pc", "E-2", 650, 1100, 4.0, 16.0, ean = ean("871829112345")),
        PartDef("CLUTCH", "Clutch plate Mehran / Alto", "EXD-SZ", "Exedy", "Transmission", "OEM-equivalent", "pc", "F-2", 5200, 7500, 1.0, 3.0),
        PartDef("TYRE", "Tyre 185/65 R15", "YOK-18565", "Yokohama", "Tyres", "Genuine", "pc", "T-1", 15500, 19000, 4.0, 12.0),
        PartDef("RACK", "Steering rack Mehran (reconditioned)", "SR-MEH", "Local", "Steering", "Used / reconditioned", "pc", "H-1", 9000, 14000, 0.0, 2.0, restock = false, sells = false),
        PartDef("FOG", "Fog lamp kit (universal)", "FOG-UNI", "Local", "Accessories", "Local", "set", "E-3", 2800, 4500, 0.0, 4.0, restock = false, sells = false),
    )

    /** Story events pinned to dates; run inside the day loop so numbering and stock follow the calendar. */
    private val agenda = HashMap<String, MutableList<() -> Unit>>()
    private fun on(date: String, f: () -> Unit) {
        val d = if (LocalDate.parse(date).dayOfWeek == DayOfWeek.SUNDAY) Dates.plusDays(date, -1) else date
        agenda.getOrPut(d) { ArrayList() } += f
    }

    private var startDate = ""
    private var realToday = ""     // the real date; Dates.today() follows the simulated clock while seeding
    private val part = HashMap<String, Long>()
    private fun stock(k: String) = Inventory.get(part.getValue(k)).qty
    private fun price(k: String) = Inventory.get(part.getValue(k)).price

    private class Car(val id: Long, val customerId: Long, val make: String, val model: String, val fuel: String, val type: String, var lastVisit: String = "")
    private val cars = ArrayList<Car>()
    private val tech = HashMap<String, Long>()
    private val sup = HashMap<String, Long>()
    private val cust = HashMap<String, Long>()
    private fun car(reg: String) = cars.first { Vehicles.get(it.id).regNo == reg }

    // ------------------------------------------------------------------ the story

    private fun seed() {
        val today = Dates.today()
        realToday = today
        val start = LocalDate.parse(today).withDayOfMonth(1).minusMonths(3).toString()   // e.g. 1 July when today is in October
        startDate = start
        at(start, 8)

        Defaults.ensure()
        Settings.set(Settings.BUSINESS, "Al-Noor Auto Workshop")
        Settings.set(Settings.ADDRESS, "Plot 12, Ferozepur Road, Lahore")
        Settings.set(Settings.PHONE, "0321-4455667")
        Settings.set(Settings.NTN, "1234567-8"); Settings.set(Settings.STRN, "3277876123456"); Settings.set(Settings.PROV_REG, "PRA-S-0012345")
        Settings.setBool(Settings.TAX_REGISTERED, true)
        Settings.setBool("fbr.enabled", true)

        // People and catalogue
        listOf(Triple("Usman", "Mechanical", 1000), Triple("Bilal", "Mechanical", 1000), Triple("Shahid", "Electrical", 1200),
            Triple("Naveed", "AC", 1200), Triple("Kashif", "Denting & painting", 800)).forEach { (n, d, c) ->
            tech[n] = Technicians.save(Technician(name = n, department = d, commissionBp = c, phone = "03${between(0, 4)}${between(0, 9)}-${between(1000000, 9999999)}"))
        }
        listOf("Battery fitting and terminal cleaning" to 500 to "Electrical", "Suspension work" to 3500 to "Mechanical",
            "Cooling system check and flush" to 1500 to "Mechanical", "Clutch replacement" to 4500 to "Mechanical",
            "Timing belt replacement" to 3500 to "Mechanical", "Wiring repair" to 1500 to "Electrical",
            "Bumper removal and fitting" to 1500 to "Denting & painting", "Gear oil / ATF change" to 800 to "Mechanical")
            .forEach { (np, d) -> Services.save(ServiceItem(name = np.first, price = rs(np.second), department = d)) }
        Services.addComplaint("Timing belt noise"); Services.addComplaint("Clutch slipping"); Services.addComplaint("Accident repair (insurance claim)")

        sup["MAP"] = Suppliers.save(Supplier(name = "Madina Auto Parts", phone = "0300-1112223", address = "McLeod Road, Lahore", ntn = "4455667-1"))
        sup["LH"] = Suppliers.save(Supplier(name = "Lubricants House", phone = "0301-5556667", address = "Badami Bagh, Lahore"))
        sup["KBC"] = Suppliers.save(Supplier(name = "Karachi Battery Centre", phone = "0322-4433221", address = "Hall Road, Lahore"))
        sup["CAP"] = Suppliers.save(Supplier(name = "Cool Air AC Parts", phone = "0333-7788665", address = "Shah Alam Market, Lahore"))
        sup["HDW"] = Suppliers.save(Supplier(name = "Hafeez Denting Works", phone = "0345-9988776", address = "Ichhra, Lahore", notes = "Sublet denting & painting"))

        for (d in partDefs) part[d.key] = Inventory.save(Part(name = d.name, partNo = d.partNo, brand = d.brand, category = d.category, grade = d.grade,
            unit = d.unit, location = d.location, price = rs(d.price), reorderLevel = q(d.reorder), fits = d.fits, barcodes = listOfNotNull(d.ean)))
        Inventory.save(Part(name = "Distributor cap Charade (discontinued)", brand = "Daihatsu", category = "Ignition", location = "X-9", price = rs(900), active = false))

        // Customers and their cars
        fun person(key: String, name: String, phone: String, type: String = "Regular", limit: Int = 0, terms: Int = 0, ntn: String = "", address: String = "", cnic: String = "") {
            cust[key] = Customers.save(Customer(name = name, phone = phone, type = type, creditLimit = rs(limit), termsDays = terms, ntn = ntn,
                address = address, city = "Lahore", cnic = cnic))
        }
        fun vehicle(owner: String, reg: String, make: String, model: String, year: Int, color: String, odo: Int, fuel: String = "Petrol", trans: String = "Manual") {
            val v = Vehicles.save(Vehicle(customerId = cust.getValue(owner), regNo = reg, make = make, model = model, year = "$year", color = color, odometer = odo.toLong(),
                fuel = fuel, transmission = trans, chassisNo = "${make.take(2).uppercase()}${model.take(3).uppercase()}-${between(100000, 999999)}",
                engineNo = "E${between(1000000, 9999999)}"))
            cars += Car(v, cust.getValue(owner), make, model, fuel, Customers.get(cust.getValue(owner)).type)
        }
        person("ali", "Ali Khan", "0300-1234567", address = "Model Town, Lahore", cnic = "35202-1234567-1")
        person("sana", "Sana Malik", "0321-9876543", address = "Johar Town, Lahore")
        person("imran", "Imran Qureshi", "0345-1122334", address = "Garden Town, Lahore")
        person("ayesha", "Ayesha Siddiqui", "0333-2223344")
        person("hamza", "Hamza Butt", "0301-4445566", address = "DHA Phase 4, Lahore")
        person("usmant", "Usman Tariq", "0302-6667788")
        person("nadia", "Nadia Hussain", "0311-8889900", address = "Cantt, Lahore")
        person("zubair", "Zubair Ahmed", "0322-1010101")
        person("farooq", "Farooq Sheikh", "0335-5556677", address = "Gulberg III, Lahore")
        person("mariam", "Mariam Javed", "0346-7778899")
        person("bilalc", "Bilal Chaudhry", "0300-9998877")
        person("tariq", "Tariq Mehmood", "0313-4567123")
        person("rubina", "Rubina Akhtar", "0336-1239876")
        person("rehman", "Rehman Transport", "0333-4567890", "Company / fleet", 400000, 30, "7788990-2", "Bund Road, Lahore")
        person("faisal", "Faisal Rent a Car", "0302-7788990", "Company / fleet", 250000, 15, "5566778-3", "MM Alam Road, Gulberg, Lahore")
        person("crescent", "Crescent Insurance Co.", "042-35761234", "Insurance company", 1500000, 45, "1122334-5", "Davis Road, Lahore")

        vehicle("ali", "LEA-1234", "Toyota", "Corolla", 2015, "White", 98000)
        vehicle("sana", "LEB-778", "Honda", "City", 2016, "Silver", 87000)
        vehicle("imran", "ICT-202", "Suzuki", "Alto", 2019, "Red", 52000)
        vehicle("imran", "ICT-5590", "Suzuki", "Cultus", 2018, "Grey", 76000)
        vehicle("ayesha", "LED-2201", "Toyota", "Yaris", 2021, "Black", 41000, trans = "Automatic")
        vehicle("hamza", "LEF-90", "Honda", "Civic", 2019, "White", 64000, trans = "Automatic")
        vehicle("usmant", "RIM-776", "Suzuki", "Wagon R", 2017, "Blue", 91000)
        vehicle("nadia", "LEA-6612", "KIA", "Sportage", 2022, "White", 38000, trans = "Automatic")
        vehicle("zubair", "LHR-3344", "Suzuki", "Mehran", 2012, "White", 143000)
        vehicle("farooq", "LEC-4321", "Toyota", "Fortuner", 2020, "Black", 72000, fuel = "Diesel", trans = "Automatic")
        vehicle("mariam", "LEE-1990", "Honda", "City", 2022, "Red", 29000, trans = "Automatic")
        vehicle("bilalc", "FDA-221", "Suzuki", "Swift", 2023, "Silver", 18000)
        vehicle("tariq", "LEB-1010", "Toyota", "Corolla", 2010, "Beige", 210000)
        vehicle("rubina", "LEA-5050", "Daihatsu", "Mira", 2016, "Pink", 66000, trans = "Automatic")
        vehicle("rehman", "LES-5521", "Suzuki", "Bolan", 2017, "White", 121000)
        vehicle("rehman", "LES-5522", "Suzuki", "Ravi", 2018, "White", 108000)
        vehicle("rehman", "LES-7810", "Toyota", "Hiace", 2016, "White", 233000, fuel = "Diesel")
        vehicle("faisal", "LEC-909", "Toyota", "Yaris", 2019, "White", 84000)
        vehicle("faisal", "LEC-910", "Toyota", "Corolla", 2020, "Grey", 112000, trans = "Automatic")
        vehicle("faisal", "LEC-911", "Honda", "City", 2021, "White", 69000)

        // The wider customer base: people who come in now and then
        val first = listOf("Ahmed", "Asad", "Fahad", "Hassan", "Junaid", "Kamran", "Nabeel", "Omar", "Qasim", "Rizwan", "Saad", "Shoaib", "Talha", "Waqas",
            "Yasir", "Zeeshan", "Adnan", "Faraz", "Haris", "Moiz", "Sadia", "Hina", "Amna", "Fatima", "Saima", "Rabia", "Mehwish", "Iqra", "Noor", "Zara")
        val last = listOf("Khan", "Malik", "Butt", "Chaudhry", "Sheikh", "Qureshi", "Raza", "Iqbal", "Hussain", "Javed", "Aslam", "Mirza", "Siddiqui",
            "Abbasi", "Rana", "Gill", "Bhatti", "Awan", "Hashmi", "Akram")
        val models = listOf("Suzuki" to "Alto", "Suzuki" to "Alto", "Suzuki" to "Cultus", "Suzuki" to "Wagon R", "Suzuki" to "Mehran", "Suzuki" to "Swift",
            "Toyota" to "Corolla", "Toyota" to "Corolla", "Toyota" to "Yaris", "Honda" to "City", "Honda" to "City", "Honda" to "Civic",
            "KIA" to "Picanto", "KIA" to "Sportage", "Hyundai" to "Tucson", "Hyundai" to "Elantra", "Changan" to "Alsvin", "MG" to "HS")
        val areas = listOf("LEA", "LEB", "LEC", "LED", "LEE", "LEF", "LEH", "LEJ", "LEK", "LEL", "LEM", "LEN", "LEP", "LER", "LET", "LEZ", "RIA", "ICT", "GAA")
        val usedPhones = HashSet<String>(); val usedRegs = HashSet<String>()
        repeat(110) { i ->
            var ph: String; do { ph = "03${between(0, 4)}${between(0, 9)}-${between(1000000, 9999999)}" } while (!usedPhones.add(ph))
            var reg: String; do { reg = "${pick(areas)}-${between(10, 9999)}" } while (!usedRegs.add(reg) || Vehicles.byReg(reg) != null)
            val key = "c$i"
            person(key, "${pick(first)} ${pick(last)}", ph, if (chance(0.7)) "Regular" else "Walk-in")
            val (make, model) = pick(models)
            vehicle(key, reg, make, model, between(2008, 2025), pick(listOf("White", "Silver", "Black", "Grey", "Red", "Blue")), between(8000, 180000),
                trans = if (make in setOf("Toyota", "Honda", "KIA", "Hyundai", "MG") && chance(0.6)) "Automatic" else "Manual")
        }

        // Staff (salaries in rupees)
        val staff = listOf(
            StaffBook.save(Staff(name = "Usman Ali", role = "Mechanic", salary = rs(45000), technicianId = tech["Usman"], machineId = "101", joined = "2021-03-01", phone = "0300-5551001")),
            StaffBook.save(Staff(name = "Bilal Hussain", role = "Mechanic", salary = rs(42000), technicianId = tech["Bilal"], machineId = "102", joined = "2022-06-15", phone = "0300-5551002")),
            StaffBook.save(Staff(name = "Shahid Iqbal", role = "Electrician", salary = rs(50000), technicianId = tech["Shahid"], machineId = "103", joined = "2020-01-10", phone = "0300-5551003")),
            StaffBook.save(Staff(name = "Naveed Akhtar", role = "AC technician", salary = rs(45000), technicianId = tech["Naveed"], machineId = "104", joined = "2023-02-01", phone = "0300-5551004")),
            StaffBook.save(Staff(name = "Kashif Raza", role = "Denter / tyres", salary = rs(32000), technicianId = tech["Kashif"], machineId = "105", joined = "2024-08-01", phone = "0300-5551005")),
            StaffBook.save(Staff(name = "Tahir Mahmood", role = "Cashier / accounts", salary = rs(55000), machineId = "106", joined = "2019-11-01", phone = "0300-5551006")),
            StaffBook.save(Staff(name = "Javed Masih", role = "Helper", salary = rs(25000), machineId = "107", joined = "2025-01-05", phone = "0300-5551007")),
        )

        // Opening position on the first day
        Books.openingBalance(Accounts.sys("CASH"), rs(150000), start)
        Books.openingBalance(Accounts.sys("BANK"), rs(2500000), start)
        Customers.openingBalance(cust.getValue("rehman"), rs(45000), start)
        Suppliers.openingBalance(sup.getValue("MAP"), rs(85000), start)
        for (d in partDefs) Inventory.openingStock(part.getValue(d.key), q(d.target), rs(d.cost), start)
        Assets.add(FixedAsset(name = "Car lift (2-post)", category = "Equipment", bought = Dates.plusDays(start, -400), cost = rs(450000), lifeMonths = 96), null)
        Assets.add(FixedAsset(name = "Engine scanner (OBD diagnostic)", category = "Equipment", bought = Dates.plusDays(start, -200), cost = rs(185000), lifeMonths = 48), null)
        Assets.add(FixedAsset(name = "Air compressor 200 L", category = "Equipment", bought = Dates.plusDays(start, -700), cost = rs(95000), lifeMonths = 72, salvage = rs(10000)), null)

        Customers.addPoints(cust.getValue("ali"), 450, null, "Points carried over from the old card system")
        story(today)

        // ---------------------------------------------------------------- day by day
        val todayDate = LocalDate.parse(today)
        var day = LocalDate.parse(start)
        val carryOver = ArrayList<Long>()                      // jobs finished late, billed next morning
        var dayIndex = 0
        while (day.isBefore(todayDate)) {
            val d = day.toString()
            if (day.dayOfWeek == DayOfWeek.SUNDAY) { day = day.plusDays(1); continue }
            dayIndex++
            monthly(d, day, start, staff)
            weekly(d, day)
            special(d, dayIndex)

            // Yesterday's late jobs get billed and collected this morning
            at(d, 10)
            carryOver.toList().forEach { settle(it, d, 10) }; carryOver.clear()

            val n = between(2, 4) + if (day.dayOfWeek == DayOfWeek.SATURDAY) 1 else 0
            repeat(n) { i ->
                val c = chooseCar(d) ?: return@repeat
                val job = openJob(c, d, 9 + i, between(0, 50))
                if (chance(0.82)) settle(job, d, 15 + i % 3) else carryOver += job
            }
            repeat(between(0, 3)) { counterSale(d, between(11, 18)) }
            agenda.remove(d)?.forEach { it() }
            if (day.dayOfWeek == DayOfWeek.SATURDAY) depositCash(d)
            day = day.plusDays(1)
        }

        // ---------------------------------------------------------------- the last few days and today
        val recent = (1..9).map { Dates.plusDays(today, -it.toLong()) }.filter { LocalDate.parse(it).dayOfWeek != DayOfWeek.SUNDAY }.take(6).reversed()
        recent.forEachIndexed { i, d ->
            at(d, 19, 30)
            val cd = Books.cashDay(d)
            if (i == 2) Books.closeCash(d, cd.expected - rs(150), "Short Rs 150 — change given wrongly")
            else Books.closeCash(d, cd.expected, "")
        }
        todayState(today, carryOver)

        // Bank statement ticked up to four days ago
        at(today, 9)
        val bank = Accounts.sys("BANK")
        Books.bankLines(bank, start, Dates.plusDays(today, -4)).forEach { Books.setCleared(it.long("id"), true, Dates.plusDays(it.str("date"), 1)) }

        // FBR numbers received for everything older than two days
        Fbr.pending().filter { it.str("date") < Dates.plusDays(today, -2) }.forEach {
            Fbr.setNumber(it.long("id"), "1234567DI" + it.str("date").replace("-", "") + it.long("id").toString().padStart(6, '0'))
        }

        // Service reminders: a few due this week
        listOf("LEA-1234" to 0L, "LEB-778" to 2L, "LES-5521" to 4L, "ICT-202" to -3L).forEach { (reg, days) ->
            val v = Vehicles.get(car(reg).id)
            Vehicles.save(v.copy(nextServiceDate = Dates.plusDays(today, days), nextServiceKm = v.odometer + 400))
        }
        // Faisal Rent a Car has run past its credit limit, so new work shows the warning
        val fr = Customers.get(cust.getValue("faisal"))
        Customers.save(fr.copy(creditLimit = maxOf(rs(1000), round(Customers.balance(fr.id) * 7 / 10, rs(1000)))))   // always below what they owe

        Ledger.lock(start.take(7))
        at(today, 9)
        Audit.log("demo", "system", null, "Sample data created")
    }

    // ------------------------------------------------------------------ calendar events

    private fun monthly(d: String, day: LocalDate, start: String, staff: List<Long>) {
        val dom = day.dayOfMonth
        at(d, 9)
        if (dom == 1 || (dom == 2 && day.minusDays(1).dayOfWeek == DayOfWeek.SUNDAY))
            Books.expense(d, Accounts.sys("RENT"), rs(80000), "Bank transfer", "Shop rent ${day.month.name.lowercase().replaceFirstChar { it.uppercase() }}", null)
        // Salaries for the month are paid on its last working day, so each month carries its own wages
        val next = day.plusDays(if (day.plusDays(1).dayOfWeek == DayOfWeek.SUNDAY) 2 else 1)
        if (next.monthValue != day.monthValue) {
            val month = d.take(7)
            at(d, 18)
            for (s in staff) {
                val st = StaffBook.get(s)
                StaffBook.paySalary(s, month, st.salary, StaffBook.commission(s, month), StaffBook.advanceBalance(s), 0,
                    if (st.role == "Helper") "Cash" else "Bank transfer", d)
            }
            Assets.runDepreciation(month)
        }
        // Monthly sales tax returns: last month's FBR sales tax and provincial services tax paid around the 15th
        if (dom == 15 || (dom == 16 && day.minusDays(1).dayOfWeek == DayOfWeek.SUNDAY)) {
            val prevEnd = day.withDayOfMonth(1).minusDays(1).toString()
            val mon = day.minusMonths(1).month.name.lowercase().replaceFirstChar { it.uppercase() }
            at(d, 14)
            for ((key, label) in listOf("GST" to "FBR sales tax return", "PST" to "PRA services tax return")) {
                val due = Ledger.balance(Accounts.sys(key), to = prevEnd)
                if (due > 0) Books.manual("BPV", d, "$label for $mon (CPR ${between(1000000, 9999999)})", null, listOf(LedgerLine(Accounts.sys(key), debit = due)), null)
            }
        }
        if (dom == 12) Books.expense(d, Accounts.sys("ELECTRICITY"), rs(between(18, 26) * 1000), "Cash", "LESCO electricity bill", null)
        if (dom == 15) Books.expense(d, Accounts.sys("UTILITIES"), rs(3500), "Bank transfer", "PTCL internet", null)
        if (dom == 18) Books.expense(d, Accounts.sys("UTILITIES"), rs(between(2200, 3400)), "Cash", "SNGPL gas bill", null)
        if (dom == 20) Books.expense(d, Accounts.sys("MAINT"), rs(6000), "Cash", "Generator service and oil", null)
    }

    private fun weekly(d: String, day: LocalDate) {
        at(d, 8, 30)
        if (day.dayOfWeek == DayOfWeek.MONDAY) { restock(d); Books.expense(d, Accounts.sys("FOOD"), rs(between(30, 45) * 100), "Cash", "Staff lunch and tea", null) }
        if (day.dayOfWeek == DayOfWeek.WEDNESDAY) Books.expense(d, Accounts.sys("FUEL"), rs(between(20, 35) * 100), "Cash", "Fuel for pick and drop", null)
        if (day.dayOfWeek == DayOfWeek.THURSDAY && day.dayOfMonth in 8..14 || day.dayOfWeek == DayOfWeek.THURSDAY && day.dayOfMonth in 22..28) paySuppliers(d)
    }

    /** One-off events that make every screen have something to show. */
    private fun special(d: String, i: Int) {
        val day = LocalDate.parse(d)
        when (i) {
            4 -> { at(d, 12); Books.expense(d, Accounts.sys("TOOLS"), rs(8500), "Cash", "Torque wrench", null) }
            9 -> { at(d, 11) // a job the customer took back without the work
                val c = car("LEA-5050")
                val j = Jobs.create(JobCard(vehicleId = c.id, odometer = 66500, complaint = "Gear shifting hard", department = "Mechanical", promisedAt = d))
                Jobs.addLine(JobLine(jobId = j, kind = "PART", partId = part["ATF"], qty = q(3.0), rate = price("ATF")))
                at(d, 13); Jobs.setStatus(j, "Cancelled")
                Db.exec("UPDATE job_cards SET notes='Customer decided to get it done at the dealer.' WHERE id=?", j)
            }
            12 -> { at(d, 15); Assets.add(FixedAsset(name = "Wheel alignment machine", category = "Equipment", bought = d, cost = rs(650000), lifeMonths = 96, salvage = rs(50000)), "Bank transfer") }
            14 -> { at(d, 16); Purchasing.postReturn(sup.getValue("MAP"), d, listOf(DocLine(part.getValue("PAD_S"), q(2.0), rs(1800))), "Wrong spec pads returned (rear instead of front)") }
            18 -> { at(d, 10); StaffBook.giveAdvance(StaffBook.list().first { it.name.startsWith("Bilal") }.id, rs(10000), "Cash", d) }
            20 -> { at(d, 12); Purchasing.createClaim(part.getValue("BAT55"), sup.getValue("KBC"), q(1.0), "Battery dead after 3 weeks (customer LHR-3344), under 12-month warranty", d) }
            22 -> { at(d, 17); Inventory.adjust(part.getValue("BFLUID"), q(-1.0), "Bottle broken in store", d) }
            27 -> { at(d, 18); Books.manual("CPV", d, "Owner's personal withdrawal", null, listOf(LedgerLine(Accounts.sys("DRAWINGS"), debit = rs(50000))), null) }
            30 -> { at(d, 10) // planned purchase order, received three days later
                val po = Purchasing.createOrder(sup.getValue("MAP"), d, listOf(DocLine(part.getValue("PAD_T"), q(4.0), rs(6300)), DocLine(part.getValue("LINK"), q(6.0), rs(1800))), "Monthly brake and suspension stock")
                poToReceive = po to Dates.plusDays(d, 3)
            }
            31 -> { at(d, 9); Inventory.bulkPrice("Shell", null, 600, rs(10)); Inventory.bulkPrice(null, "Electrical", 500, rs(50)) }
            33 -> { at(d, 15); Purchasing.resolveClaim(Purchasing.claims().first().long("id"), "Replaced") }
            40 -> { at(d, 11); Books.manual("CRV", d, "Sale of scrap batteries and used oil", null, listOf(LedgerLine(Accounts.sys("OTHER_INCOME"), credit = rs(6500))), null) }
            45 -> { at(d, 12); Books.expense(d, Accounts.sys("TOOLS"), rs(6200), "Cash", "Socket set", null) }
            52 -> { at(d, 18) // quarter-end count of the B racks with one variance
                val c = Inventory.startCount("Quarter-end count — B racks (brakes)", "B")
                Inventory.countLines(c).firstOrNull { it.partId == part["PAD_H"] && it.system > 0 }?.let { Inventory.setCounted(it.id, it.system - 1000) }
                Inventory.approveCount(c)
            }
            56 -> { at(d, 18); Books.manual("BPV", d, "Owner's personal withdrawal", null, listOf(LedgerLine(Accounts.sys("DRAWINGS"), debit = rs(75000))), null) }
            60 -> { at(d, 10); val po = Purchasing.createOrder(sup.getValue("CAP"), d, listOf(DocLine(part.getValue("ACGAS"), q(10.0), rs(2100))), "AC gas for summer"); at(d, 16); Purchasing.cancelOrder(po) }
            62 -> { at(d, 11)
                Books.manual("JV", d, "Reclassify torque wrench booked to misc", null,
                    listOf(LedgerLine(Accounts.sys("TOOLS"), debit = rs(2500)), LedgerLine(Accounts.sys("MISC"), credit = rs(2500))), null)
                Books.expense(d, Accounts.sys("MISC"), rs(2500), "Cash", "Tool purchase", null)
            }
            65 -> { at(d, 12); val v = Books.manual("CPV", d, "Fuel for pick and drop (entered twice by mistake)", null, listOf(LedgerLine(Accounts.sys("FUEL"), debit = rs(12000))), null)
                at(d, 17); Ledger.reverse(v, d, "Duplicate entry") }
        }
        poToReceive?.let { (po, on) -> if (d >= on) { at(d, 14); Purchasing.receiveOrder(po, "MAP-PO-${po}", d, rs(400)); poToReceive = null } }
        if (day.dayOfMonth == 8 && day.monthValue == LocalDate.parse(realToday).monthValue) {
            at(d, 10); StaffBook.giveAdvance(StaffBook.list().first { it.name.startsWith("Javed") }.id, rs(5000), "Cash", d)
        }
        fleetPayments(d, day, i)
    }
    private var poToReceive: Pair<Long, String>? = null

    private var rehmanCheques = 0
    private fun fleetPayments(d: String, day: LocalDate, i: Int) {
        val today = LocalDate.parse(realToday)
        // Rehman Transport pays monthly by cheque with 4% income tax withheld
        if (day.dayOfMonth in 10..11 && day.monthValue != today.minusMonths(3).monthValue && cust.containsKey("rehman") && !paidThisMonth.contains("R" + d.take(7))) {
            paidThisMonth += "R" + d.take(7)
            val id = cust.getValue("rehman")
            val bal = Customers.balance(id)
            if (bal > 0) {
                at(d, 12)
                val wht = round(bal * 4 / 100, 100)
                rehmanCheques++
                val chequeDate = if (day.monthValue == today.monthValue) Dates.plusDays(realToday, 4) else d
                val pid = Billing.receive(id, bal - wht, "Cheque", "MCB cheque 00${4400 + rehmanCheques}", d, null, wht, chequeDate, "Monthly settlement")
                when {
                    day.monthValue == today.monthValue -> {} // post-dated, still in hand
                    rehmanCheques == 2 -> pending += Triple(Dates.plusDays(d, 4), "bounce", pid)
                    else -> pending += Triple(Dates.plusDays(d, 3), "clear", pid)
                }
            }
        }
        // Faisal Rent a Car pays part of its balance by bank transfer every second Tuesday
        if (day.dayOfWeek == DayOfWeek.TUESDAY && (i / 6) % 2 == 1) {
            val id = cust.getValue("faisal"); val bal = Customers.balance(id)
            if (bal > rs(20000)) { at(d, 13); Billing.receive(id, round(bal * 6 / 10, rs(1000)), "Bank transfer", "IBFT ${between(100000, 999999)}", d, note = "Part payment") }
        }
        // Due cheque events
        pending.filter { it.first <= d && (it.second == "clear" || it.second == "bounce") }.toList().forEach { (_, what, pid) ->
            at(d, 11)
            if (what == "clear") Billing.chequeCleared(pid, d)
            else {
                Billing.chequeBounced(pid, d)
                // Re-paid by bank transfer a week later
                val p = Db.one("SELECT party_id, amount FROM payments WHERE id=?", pid)!!
                pending += Triple(Dates.plusDays(d, 7), "repay:${p.long("party_id")}:${p.long("amount")}", 0L)
            }
            pending.removeIf { it.third == pid && it.second == what }
        }
        pending.filter { it.first <= d && it.second.startsWith("repay:") }.toList().forEach { e ->
            val (_, cid, amt) = e.second.split(":")
            at(d, 12); Billing.receive(cid.toLong(), amt.toLong(), "Bank transfer", "IBFT ${between(100000, 999999)}", d, note = "Replacing bounced cheque")
            pending.remove(e)
        }
    }
    private val pending = ArrayList<Triple<String, String, Long>>()
    private val paidThisMonth = HashSet<String>()

    // ------------------------------------------------------------------ jobs

    private fun chooseCar(d: String): Car? {
        val late = d > Dates.plusDays(startDate, 12)
        val pool = cars.filter { it.lastVisit.isEmpty() || Dates.daysBetween(it.lastVisit, d) > if (it.type == "Company / fleet") 20 else 35 }
            .filter { !(late && Vehicles.get(it.id).regNo in setOf("LEB-1010", "LEA-5050")) }   // two customers who stopped coming
            .filter { !(d > Dates.plusDays(realToday, -45) && Vehicles.get(it.id).regNo in reserved) }
        if (pool.isEmpty()) return null
        val fleet = pool.filter { it.type == "Company / fleet" }
        return if (fleet.isNotEmpty() && chance(0.25)) pick(fleet) else pick(pool)
    }

    private val reserved = setOf("LEB-778", "LEA-1234", "LEF-90", "LEA-6612")
    private fun fam(c: Car) = when (c.make) { "Honda" -> "H"; "Suzuki", "Daihatsu" -> "S"; else -> "T" }

    private fun techFor(dept: String) = when (dept) {
        "Electrical" -> tech.getValue("Shahid"); "AC" -> tech.getValue("Naveed"); "Denting & painting", "Tyres" -> tech.getValue("Kashif")
        else -> if (chance(0.5)) tech.getValue("Usman") else tech.getValue("Bilal")
    }

    private data class Work(val complaint: String, val dept: String, val lines: List<JobLine>)

    private fun partLine(k: String, qty: Double): JobLine? =
        if (stock(k) >= q(qty)) JobLine(kind = "PART", partId = part.getValue(k), qty = q(qty), rate = price(k)) else null
    private fun labour(desc: String, rate: Int, dept: String) = JobLine(kind = "LABOUR", description = desc, qty = 1000, rate = rs(rate), technicianId = techFor(dept))

    private fun work(c: Car): Work {
        val f = fam(c)
        val big = c.model in setOf("Fortuner", "Hiace", "Sportage")
        return when (rnd.nextInt(100)) {
            in 0..29 -> Work("Periodic service", "Mechanical", listOfNotNull(
                partLine("OF_$f", 1.0),
                if (c.fuel == "Diesel") partLine("OIL2050", 2.0) else partLine(if (f == "S") "OIL1040" else "OIL530", if (f == "S") 3.0 else 3.5),
                if (chance(0.4)) partLine("AF_$f", 1.0) else null,
                labour("Engine oil change (labour)", 500, "Mechanical"),
                if (chance(0.3)) labour("Scanning / diagnostics", 1500, "Electrical") else null))
            in 30..44 -> Work("Brakes squeaking", "Mechanical", listOfNotNull(
                partLine("PAD_$f", 1.0), partLine("BFLUID", 1.0), if (f == "T" && chance(0.3)) partLine("SHOE_T", 1.0) else null,
                labour("Brake service", 2000, "Mechanical")))
            in 45..56 -> Work("AC not cooling", "AC", listOfNotNull(
                partLine("ACGAS", if (chance(0.3)) 2.0 else 1.0), if (chance(0.5)) partLine("CABIN", 1.0) else null, labour("AC service", 3000, "AC")))
            in 57..64 -> Work("Battery weak", "Electrical", listOfNotNull(
                partLine(if (big) "BAT70" else "BAT55", 1.0), labour("Battery fitting and terminal cleaning", 500, "Electrical"),
                if (chance(0.5)) labour("Scanning / diagnostics", 1500, "Electrical") else null))
            in 65..72 -> Work("Noise from suspension", "Mechanical", listOfNotNull(
                if (f == "S") partLine("BUSH", 2.0) else partLine("LINK", 2.0), if (f == "T" && chance(0.3)) partLine("SHOCK", 1.0) else null,
                labour("Suspension work", 3500, "Mechanical"), labour("Wheel alignment", 1500, "Tyres")))
            in 73..81 -> Work("Tuning", "Mechanical", listOfNotNull(
                partLine(if (f == "S") "PLUG_STD" else "PLUG_IR", 4.0), partLine("AF_$f", 1.0), labour("Tuning", 2500, "Mechanical")))
            in 82..85 -> Work("Overheating", "Mechanical", listOfNotNull(
                partLine("COOLANT", 2.0), if (chance(0.5)) partLine("FBELT", 1.0) else null, labour("Cooling system check and flush", 1500, "Mechanical")))
            in 86..88 -> if (f == "S") Work("Clutch slipping", "Mechanical", listOfNotNull(partLine("CLUTCH", 1.0), labour("Clutch replacement", 4500, "Mechanical")))
                         else work(c)
            in 89..94 -> Work("Pulling to one side", "Tyres", listOfNotNull(
                labour("Wheel alignment", 1500, "Tyres"), labour("Wheel balancing (4)", 1200, "Tyres"), if (chance(0.25)) partLine("TYRE", 2.0) else null))
            in 95..97 -> Work("Headlight and wiring fault", "Electrical", listOfNotNull(
                partLine("H4", 2.0), if (chance(0.5)) partLine("WIPER", 2.0) else null, labour("Wiring repair", 1500, "Electrical")))
            else -> {
                val cost = between(9, 14) * 1000
                Work("Rear bumper dent and scratches", "Denting & painting", listOf(
                    JobLine(kind = "SUBLET", description = "Bumper denting and paint", qty = 1000, rate = round(rs(cost) * 14 / 10, rs(500)), cost = rs(cost), vendor = "Hafeez Denting Works"),
                    labour("Bumper removal and fitting", 1500, "Denting & painting")))
            }
        }
    }

    private fun openJob(c: Car, d: String, hour: Int, minute: Int, override: Work? = null): Long {
        at(d, hour, minute)
        val w = override ?: work(c)
        val v = Vehicles.get(c.id)
        val odo = v.odometer + (if (c.lastVisit.isEmpty()) between(300, 1500) else Dates.daysBetween(c.lastVisit, d) * between(25, 60)).toLong()
        val j = Jobs.create(JobCard(vehicleId = c.id, odometer = odo, complaint = w.complaint, department = w.dept, promisedAt = d,
            fuel = pick(listOf("Quarter", "Half", "Half", "Three quarters", "Full")), priority = if (chance(0.1)) "Urgent" else "Normal",
            broughtBy = if (c.type == "Company / fleet") pick(listOf("Driver Akram", "Driver Saleem", "Driver Nasir")) else "",
            itemsInCar = if (chance(0.2)) "Spare wheel, jack, floor mats" else ""))
        c.lastVisit = d
        w.lines.forEach { Jobs.addLine(it.copy(jobId = j)) }
        if (chance(0.3)) { at(d, hour, minute + 5); Jobs.startTime(j, w.lines.firstOrNull { it.technicianId != null }?.technicianId); at(d, hour + 2, minute); Jobs.stopTime(j) }
        return j
    }

    private val payMix = listOf("Cash", "Cash", "Cash", "Cash", "Cash", "Card", "Card", "Bank transfer", "JazzCash", "JazzCash", "Easypaisa", "Raast")
    private fun reference(method: String) = when (method) {
        "Card" -> "Visa ****${between(1000, 9999)}"; "Bank transfer" -> "IBFT ${between(100000, 999999)}"; "JazzCash" -> "TID ${between(10000000, 99999999)}"
        "Easypaisa" -> "EP ${between(10000000, 99999999)}"; "Raast" -> "RAAST ${between(100000, 999999)}"; else -> ""
    }

    /** Bills the job, takes payment (fleet customers run on account) and hands the car back. */
    private fun settle(jobId: Long, d: String, hour: Int) {
        val j = Jobs.get(jobId)
        if (Jobs.lines(jobId).isEmpty()) { at(d, hour); Jobs.setStatus(jobId, "Cancelled"); return }
        at(d, hour, between(0, 40))
        val c = Customers.get(j.customerId)
        val discount = if (c.type == "Regular" && chance(0.12)) rs(pick(listOf(300, 500, 1000))) else 0L
        val inv = Billing.invoiceJob(jobId, discount, if (discount > 0) "Discount for regular customer" else "")
        val total = Billing.get(inv).total
        if (c.type != "Company / fleet") {
            if (c.points >= 300 && c.id == cust["ali"] && !redeemed) {
                redeemed = true
                Billing.receive(c.id, rs(300), "Loyalty points", "", d, inv)
                Billing.receive(c.id, total - rs(300), "Cash", "", d, inv)
            } else if (chance(0.05)) {           // split between cash and card
                val cash = round(total / 2, rs(500))
                Billing.receive(c.id, cash, "Cash", "", d, inv); Billing.receive(c.id, total - cash, "Card", reference("Card"), d, inv)
            } else {
                val m = pick(payMix); Billing.receive(c.id, total, m, reference(m), d, inv)
            }
        }
        at(d, hour + 1, between(0, 50))
        Jobs.deliver(jobId, allowCredit = c.type == "Company / fleet")
    }
    private var redeemed = false

    private fun counterSale(d: String, hour: Int) {
        at(d, hour, between(0, 59))
        val menu = listOf("OIL2050" to 1.0, "WIPER" to 2.0, "H4" to 2.0, "COOLANT" to 1.0, "PLUG_STD" to 4.0, "PLUG_IR" to 4.0, "BFLUID" to 1.0,
            "OF_S" to 1.0, "OF_T" to 1.0, "ATF" to 2.0, "TYRE" to 4.0, "TYRE" to 2.0, "BAT55" to 1.0)
        val picks = (1..between(1, 2)).map { pick(menu) }.distinctBy { it.first }.filter { stock(it.first) >= q(it.second) + q(partDefs.first { p -> p.key == it.first }.reorder) }
        if (picks.isEmpty()) return
        val buyer = if (chance(0.8)) Customers.walkIn() else cust.getValue(pick(listOf("ali", "sana", "hamza", "zubair", "mariam")))
        val lines = picks.map { (k, n) -> InvLine("PART", part.getValue(k), Inventory.get(part.getValue(k)).name, q(n), price(k)) }
        val inv = Billing.post("Counter", d, buyer, null, null, lines, 0, "")
        val total = Billing.get(inv).total
        val m = pick(listOf("Cash", "Cash", "Cash", "Card", "JazzCash"))
        Billing.receive(buyer, total, m, reference(m), d, inv)
    }

    private fun depositCash(d: String) {
        at(d, 18, 45)
        val cash = Ledger.balance(Accounts.sys("CASH"), to = d)
        val amount = round(cash - rs(60000), rs(10000))
        if (amount > rs(20000)) Books.manual("JV", d, "Cash deposited in bank", null,
            listOf(LedgerLine(Accounts.sys("BANK"), debit = amount), LedgerLine(Accounts.sys("CASH"), credit = amount)), null)
        val wallet = Ledger.balance(Accounts.sys("WALLET"), to = d)
        if (wallet > rs(5000)) Books.manual("JV", d, "JazzCash / Easypaisa balance moved to bank", null,
            listOf(LedgerLine(Accounts.sys("BANK"), debit = wallet), LedgerLine(Accounts.sys("WALLET"), credit = wallet)), null)
    }

    // ------------------------------------------------------------------ buying

    private fun supplierFor(d: PartDef) = when (d.category) {
        "Lubricants" -> "LH"; "Electrical" -> if (d.key.startsWith("BAT")) "KBC" else "MAP"; "AC" -> "CAP"; else -> "MAP"
    }

    private fun restock(d: String) {
        val month = LocalDate.parse(d).monthValue
        val startMonth = LocalDate.parse(realToday).minusMonths(3).monthValue
        val inflation = if (month != startMonth && month != (startMonth % 12) + 1) 104 else 100   // costs went up 4% in the third month
        partDefs.filter { it.restock && stock(it.key) < q(it.reorder * 2) }.groupBy { supplierFor(it) }.forEach { (s, defs) ->
            val lines = defs.map { p ->
                val need = Math.ceil((q(p.target) - stock(p.key)) / 1000.0).toLong().coerceAtLeast(1)
                val cost = if (p.category in setOf("Lubricants", "Electrical")) rs(p.cost) * inflation / 100 else rs(p.cost)
                DocLine(part.getValue(p.key), need * 1000, round(cost, 100))
            }
            val billNo = "$s-${between(1000, 9999)}"
            val landed = if (lines.size >= 3) rs(between(3, 8) * 100) else 0L
            val total = lines.sumOf { pk.workshop.core.Money.times(it.cost, it.qty) } + landed
            if (s == "LH") Purchasing.postBill(sup.getValue(s), billNo, d, lines, landed, "Weekly restock", payNow = total, payMethod = "Bank transfer")
            else Purchasing.postBill(sup.getValue(s), billNo, d, lines, landed, "Weekly restock")
        }
    }

    private fun paySuppliers(d: String) {
        at(d, 16)
        for (key in listOf("MAP", "KBC", "CAP")) {
            val bal = Suppliers.balance(sup.getValue(key))
            if (bal > rs(10000)) Purchasing.paySupplier(sup.getValue(key), round(bal * 7 / 10, rs(1000)), "Bank transfer", "IBFT ${between(100000, 999999)}", d)
        }
    }

    // ------------------------------------------------------------------ insurance, comeback, credit note and today's floor

    private fun todayState(today: String, carry: List<Long>) {
        val t = today
        carry.forEach { at(t, 9, 30); settle(it, t, 10) }

        // Bookings: some already arrived, one cancelled, and the coming days
        val b1 = Bookings.save(Booking(customerId = cust["mariam"], vehicleId = car("LEE-1990").id, name = "Mariam Javed", phone = "0346-7778899", regNo = "LEE-1990",
            whenAt = t + "T09:30", service = "AC service"))
        val b2 = Bookings.save(Booking(name = "Kamran Ahmed", phone = "0311-2223334", regNo = "LED-4410", whenAt = t + "T14:00", service = "AC service",
            pickup = true, pickupAddress = "House 22, Street 5, DHA Phase 5", driver = "Asif", notes = "New customer — found us on Facebook"))
        Bookings.save(Booking(customerId = cust["ali"], vehicleId = car("LEA-1234").id, name = "Ali Khan", phone = "0300-1234567", regNo = "LEA-1234",
            whenAt = t + "T16:30", service = "Periodic service"))
        Bookings.save(Booking(customerId = cust["zubair"], vehicleId = car("LHR-3344").id, name = "Zubair Ahmed", phone = "0322-1010101", regNo = "LHR-3344",
            whenAt = Dates.plusDays(t, 1) + "T10:00", service = "AC service"))
        Bookings.save(Booking(customerId = cust["faisal"], vehicleId = car("LEC-911").id, name = "Faisal Rent a Car", phone = "0302-7788990", regNo = "LEC-911",
            whenAt = Dates.plusDays(t, 1) + "T11:30", service = "Periodic service", pickup = true, pickupAddress = "MM Alam Road office", driver = "Asif",
            returnAt = Dates.plusDays(t, 1) + "T18:00"))
        Bookings.save(Booking(customerId = cust["hamza"], vehicleId = car("LEF-90").id, name = "Hamza Butt", phone = "0301-4445566", regNo = "LEF-90",
            whenAt = Dates.plusDays(t, 3) + "T12:00", service = "Wheel alignment"))
        val cancelled = Bookings.save(Booking(name = "Saad Rafiq", phone = "0300-7654321", regNo = "LEZ-77", whenAt = Dates.plusDays(t, -2) + "T11:00", service = "Tuning"))
        Bookings.setStatus(cancelled, "Cancelled")

        // ---- today's floor ----
        fun job(reg: String, h: Int, m: Int, w: Work, status: String? = null): Long {
            val c = car(reg); val j = openJob(c, t, h, m, w)
            Db.exec("DELETE FROM job_time WHERE job_id=?", j)   // keep today's timers deliberate
            if (status != null) { at(t, h + 1, m); Jobs.setStatus(j, status) }
            return j
        }
        // Delivered and paid this morning
        settle(job("LHR-3344", 9, 5, Work("Clutch slipping", "Mechanical", listOfNotNull(partLine("CLUTCH", 1.0), labour("Clutch replacement", 4500, "Mechanical")))), t, 12)
        settle(job("ICT-202", 9, 15, Work("Periodic service", "Mechanical", listOfNotNull(partLine("OF_S", 1.0), partLine("OIL1040", 3.0),
            labour("Engine oil change (labour)", 500, "Mechanical")))), t, 11)
        counterSale(t, 10); counterSale(t, 12)

        // 1. Just arrived
        at(t, 9, 40)
        Jobs.create(JobCard(vehicleId = car("LED-2201").id, odometer = 41800, complaint = "Check engine light on", department = "Electrical", promisedAt = t,
            broughtBy = "Driver Rashid", broughtPhone = "0300-1212121", itemsInCar = "Child seat, umbrella", fuel = "Half"))
        // 2. Being inspected (came from a booking)
        at(t, 9, 35)
        val mj = Jobs.create(JobCard(vehicleId = car("LEE-1990").id, odometer = 30200, complaint = "AC not cooling", department = "AC", promisedAt = t, fuel = "Three quarters"))
        Bookings.setStatus(b1, "Arrived", mj)
        at(t, 10); Jobs.setStatus(mj, "Inspecting")
        Db.exec("UPDATE job_cards SET diagnosis=? WHERE id=?", "Gas pressure low; cabin filter choked. Leak test on condenser pipe.", mj)
        // 3. Waiting for the customer to approve the estimate
        val fj = job("LEC-4321", 10, 10, Work("Noise from suspension", "Mechanical", listOfNotNull(partLine("SHOCK", 1.0), partLine("LINK", 2.0),
            labour("Suspension work", 3500, "Mechanical"), labour("Wheel alignment", 1500, "Tyres"))), "Awaiting approval")
        at(t, 11, 20); Jobs.saveEstimate(fj)
        Db.exec("UPDATE job_cards SET diagnosis=? WHERE id=?", "Front shocks leaking, both stabilizer links worn.", fj)
        // 4. Waiting for parts
        job("ICT-5590", 10, 30, Work("Timing belt noise", "Mechanical", listOf(labour("Timing belt replacement", 3500, "Mechanical"))), "Awaiting parts")
            .also { Db.exec("UPDATE job_cards SET notes='Timing belt out of stock — on order from Madina Auto Parts (see purchase orders).' WHERE id=?", it) }
        // 5. In progress with the timer running
        val hj = job("LES-7810", 9, 50, Work("Periodic service", "Mechanical", listOfNotNull(partLine("OF_T", 1.0), partLine("OIL2050", 2.0),
            partLine("AF_T", 1.0), labour("Engine oil change (labour)", 500, "Mechanical"))))
        at(t, 10, 5); Jobs.approve(hj, "Rehman (fleet manager)", "Phone call")
        at(t, 10, 15); Jobs.startTime(hj, tech["Usman"])
        // 6. Quality check
        val sj = job("FDA-221", 9, 20, Work("Brakes squeaking", "Mechanical", listOfNotNull(partLine("PAD_S", 1.0), partLine("BFLUID", 1.0),
            labour("Brake service", 2000, "Mechanical"))))
        at(t, 9, 45); Jobs.startTime(sj, tech["Bilal"]); at(t, 12, 30); Jobs.stopTime(sj); Jobs.setStatus(sj, "Quality check")
        // 7. Ready and billed, customer pays on collection
        val wj = job("RIM-776", 9, 0, Work("Tuning", "Mechanical", listOfNotNull(partLine("PLUG_STD", 4.0), partLine("AF_S", 1.0),
            labour("Tuning", 2500, "Mechanical"))))
        at(t, 13); Billing.invoiceJob(wj, 0, "")
        // 8. Fleet car billed on account, driver collecting later
        val cj = job("LEC-910", 9, 10, Work("Periodic service", "Mechanical", listOfNotNull(partLine("OF_T", 1.0), partLine("OIL530", 4.0),
            labour("Engine oil change (labour)", 500, "Mechanical"))))
        at(t, 13, 30); Billing.invoiceJob(cj, 0, "")

        // An open spot-check count on the filter rack, one line differs
        at(t, 8, 45)
        val count = Inventory.startCount("Spot check — filter rack A", "A")
        Inventory.countLines(count).firstOrNull { it.partId == part["OF_H"] }?.let { Inventory.setCounted(it.id, it.system - 1000) }

        // Attendance for this month and last month; nobody has clocked out yet today
        val staff = StaffBook.list()
        var dd = LocalDate.parse(t).withDayOfMonth(1).minusMonths(1)
        while (!dd.isAfter(LocalDate.parse(t))) {
            if (dd.dayOfWeek != DayOfWeek.SUNDAY) for (s in staff) {
                if (chance(0.04) && dd.toString() != t) continue
                val inT = "%02d:%02d".format(8 + if (chance(0.7)) 1 else 0, if (chance(0.7)) between(0, 15) else between(15, 45))
                val outT = if (dd.toString() == t) null else "%02d:%02d".format(between(18, 19), between(0, 30))
                StaffBook.markAttendance(s.id, dd.toString(), if (inT < "08:50") "08:5${between(0, 9)}" else inT, outT)
            }
            dd = dd.plusDays(1)
        }
        at(t, 10); Bookings.get(b2)  // Kamran's pickup booking stays "Booked" for later today
    }

    /** Dated story events: insurance claims, a comeback, a returned part, a warranty claim and an open purchase order. */
    private fun story(t: String) {
        // Two customers who came early on and never returned (they show in "Customers not seen in 3 months")
        for ((reg, off) in listOf("LEB-1010" to 3L, "LEA-5050" to 6L)) {
            val d = Dates.plusDays(startDate, off)
            on(d) { val dd = if (LocalDate.parse(d).dayOfWeek == DayOfWeek.SUNDAY) Dates.plusDays(d, -1) else d
                settle(openJob(car(reg), dd, 11, 30), dd, 16) }
        }

        // Insurance claim settled earlier: customer pays the excess, insurer pays the rest by cheque
        insuranceJob("LEA-6612", Dates.plusDays(t, -40), paidByInsurer = true)
        // A recent claim still waiting for the insurer
        insuranceJob("LEF-90", Dates.plusDays(t, -12), paidByInsurer = false)

        // Comeback: same car back within a week for the same noise
        val sana = car("LEB-778")
        on(Dates.plusDays(t, -9)) { val d = Dates.plusDays(t, -9)
            val first = openJob(sana, d, 10, 0, Work("Noise from suspension", "Mechanical", listOfNotNull(partLine("LINK", 2.0), labour("Suspension work", 3500, "Mechanical"))))
            settle(first, d, 15) }
        on(Dates.plusDays(t, -4)) { val d = Dates.plusDays(t, -4)
            val again = openJob(sana, d, 11, 0, Work("Noise from suspension again", "Mechanical",
                listOfNotNull(partLine("BUSH", 1.0), labour("Re-tightening suspension (free, comeback)", 0, "Mechanical"))))
            settle(again, d, 14) }

        // Returned part: credit note two days after the bill; also shows a customer-supplied part
        val ali = car("LEA-1234")
        var aliJob = 0L
        on(Dates.plusDays(t, -15)) { val d = Dates.plusDays(t, -15)
            aliJob = openJob(ali, d, 10, 15, Work("Periodic service", "Mechanical", listOfNotNull(
                partLine("OF_T", 1.0), partLine("OIL530", 3.5), partLine("AF_T", 1.0), labour("Engine oil change (labour)", 500, "Mechanical"),
                JobLine(kind = "PART", description = "Customer's own wiper blades", qty = q(2.0), customerSupplied = true))))
            settle(aliJob, d, 13) }
        on(Dates.plusDays(t, -13)) { val d = Dates.plusDays(t, -13); at(d, 11)
            val inv = Jobs.get(aliJob).invoiceId!!
            val idx = Billing.lines(inv).indexOfFirst { it.partId == part["AF_T"] }
            Billing.creditNote(inv, mapOf(idx to q(1.0)), "Air filter not needed — returned unused", d) }

        // The last timing belt goes over the counter to a mechanic, so the next Cultus job has to wait for stock
        on(Dates.plusDays(t, -20)) { val d = Dates.plusDays(t, -20); at(d, 12, 20)
            val inv = Billing.post("Counter", d, Customers.walkIn(), null, null,
                listOf(InvLine("PART", part.getValue("TBELT"), "Timing belt Cultus", q(1.0), price("TBELT"))), 0, "Sold to Mistri Rafiq (outside mechanic)")
            Billing.receive(Customers.walkIn(), Billing.get(inv).total, "Cash", "", d, inv) }

        // Warranty claim on a leaking shock, still with the supplier
        on(Dates.plusDays(t, -6)) { val d = Dates.plusDays(t, -6); at(d, 12)
            Purchasing.createClaim(part.getValue("SHOCK"), sup.getValue("MAP"), q(1.0), "Shock absorber leaking after 2 months (LEC-910)", d) }

        // Purchase order waiting for delivery, including the out-of-stock timing belt
        on(Dates.plusDays(t, -3)) { val d = Dates.plusDays(t, -3); at(d, 10)
            Purchasing.createOrder(sup.getValue("MAP"), d, listOf(DocLine(part.getValue("TBELT"), q(3.0), rs(3300)),
                DocLine(part.getValue("OF_S"), q(10.0), rs(650))), "Timing belt for ICT-5590; Suzuki filters") }
    }

    private fun insuranceJob(reg: String, start: String, paidByInsurer: Boolean) {
        val c = car(reg)
        var j = 0L
        on(start) { val d = start
            j = insuranceOpen(c, reg, d) }
        val dd = Dates.plusDays(start, 4)
        on(dd) { insuranceBill(c, j, dd, paidByInsurer) }
    }

    private fun insuranceOpen(c: Car, reg: String, d: String): Long {
        val cost = rs(18000)
        val j = openJob(c, d, 10, 0, Work("Accident repair (insurance claim)", "Denting & painting", listOfNotNull(
            JobLine(kind = "SUBLET", description = "Front bumper repair and paint", qty = 1000, rate = rs(26000), cost = cost, vendor = "Hafeez Denting Works"),
            partLine("H4", 2.0), partLine("WIPER", 2.0),
            labour("Bumper removal and fitting", 1500, "Denting & painting"), labour("Wiring repair", 1500, "Electrical"))))
        at(d, 10, 30); Jobs.saveEstimate(j)
        at(d, 15); Jobs.approve(j, "Crescent Insurance surveyor", "Survey report")
        Db.exec("UPDATE job_cards SET notes=? WHERE id=?", "Claim no. CIC/MTR/${between(10000, 99999)}. Excess Rs 10,000 payable by the customer.", j)
        // Damage photo attached to the job card
        val img = java.awt.image.BufferedImage(640, 400, java.awt.image.BufferedImage.TYPE_INT_RGB)
        img.createGraphics().apply {
            color = java.awt.Color(235, 235, 235); fillRect(0, 0, 640, 400)
            color = java.awt.Color(200, 40, 40); fillRoundRect(80, 150, 480, 120, 40, 40)
            color = java.awt.Color.DARK_GRAY; font = java.awt.Font("SansSerif", java.awt.Font.BOLD, 26); drawString("Front bumper damage — $reg", 120, 120)
            drawLine(250, 160, 320, 260); drawLine(320, 160, 290, 255); dispose()
        }
        val f = java.io.File.createTempFile("damage", ".png").apply { deleteOnExit() }
        javax.imageio.ImageIO.write(img, "png", f)
        Jobs.attach(j, f, "Damage photo before repair")
        Jobs.setStatus(j, "In progress")
        return j
    }

    private fun insuranceBill(c: Car, j: Long, dd0: String, paidByInsurer: Boolean) {
        val dd = if (LocalDate.parse(dd0).dayOfWeek == DayOfWeek.SUNDAY) Dates.plusDays(dd0, -1) else dd0
        at(dd, 16)
        val total = Billing.compute(Billing.linesFromJob(j), 0, dd).total
        val excess = rs(10000)
        val inv = Billing.invoiceJob(j, 0, "Insurance claim — Crescent Insurance Co. pays all but the excess", cust["crescent"], total - excess)
        Billing.receive(c.customerId, excess, "Cash", "", dd, inv, note = "Insurance excess")
        at(dd, 17); Jobs.deliver(j, allowCredit = true)
        if (paidByInsurer) {
            val pd = Dates.plusDays(dd, 18)
            var pid = 0L
            on(pd) { at(pd, 12); pid = Billing.receive(cust.getValue("crescent"), total - excess, "Cheque", "HBL cheque 7712${between(10, 99)}", pd, inv, chequeDate = pd, note = "Claim settlement") }
            val cd = Dates.plusDays(pd, 2)
            on(cd) { at(cd, 11); Billing.chequeCleared(pid, cd) }
        }
    }
}
