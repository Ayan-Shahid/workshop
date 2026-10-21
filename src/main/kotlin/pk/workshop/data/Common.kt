package pk.workshop.data

import pk.workshop.core.Dates
import pk.workshop.db.Db

object Settings {
    private val cache = HashMap<String, String>()

    fun get(k: String, def: String = ""): String = cache.getOrPut(k) {
        Db.scalarStr("SELECT v FROM settings WHERE k=?", k) ?: def
    }.ifEmpty { def }

    fun set(k: String, v: String) {
        Db.exec("INSERT INTO settings(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v", k, v)
        cache[k] = v
    }

    fun bool(k: String, def: Boolean = false) = get(k, if (def) "1" else "0") == "1"
    fun setBool(k: String, v: Boolean) = set(k, if (v) "1" else "0")
    fun clearCache() = cache.clear()

    // Well-known keys
    const val BUSINESS = "business.name"
    const val ADDRESS = "business.address"
    const val PHONE = "business.phone"
    const val NTN = "business.ntn"
    const val STRN = "business.strn"
    const val PROV_REG = "business.provincial_reg"
    const val PROVINCE = "business.province"
    const val TERMS = "invoice.terms"
    const val FOOTER = "invoice.footer"
    const val TAX_REGISTERED = "tax.registered"
    const val THEME = "ui.theme"
    const val LAKH = "ui.lakh_grouping"
    const val RECEIPT_PRINTER = "printer.receipt"
    const val A4_PRINTER = "printer.a4"
    const val LABEL_PRINTER = "printer.label"
    const val LABEL_W = "label.width_mm"
    const val LABEL_H = "label.height_mm"
    const val DRAWER = "printer.drawer"
    const val BACKUP_DIR = "backup.dir"
    const val LAST_BACKUP = "backup.last"
    const val POINTS_PER_100 = "loyalty.points_per_100"
    const val POINT_VALUE = "loyalty.point_value_paisa"
    const val CREDIT_WARN = "credit.warn"
    const val DISPLAY = "display.enabled"
}

object Audit {
    fun log(action: String, entity: String, id: Long?, detail: String = "") {
        Db.exec("INSERT INTO audit_log(ts,action,entity,entity_id,detail) VALUES(?,?,?,?,?)", Dates.now(), action, entity, id, detail)
    }
}

/** Gapless document numbers per fiscal year, e.g. INV-2627-00001. */
object Numbers {
    fun next(prefix: String, date: String = Dates.today()): String = Db.tx {
        val name = prefix + "-" + Dates.fiscalCode(date)
        val n = Db.scalarStr("SELECT next FROM counters WHERE name=?", name)?.toLong() ?: 1L
        Db.exec("INSERT INTO counters(name,next) VALUES(?,?) ON CONFLICT(name) DO UPDATE SET next=excluded.next", name, n + 1)
        "$name-" + n.toString().padStart(5, '0')
    }
}

/** Effective-dated tax rates. kind = GOODS (FBR) or SERVICES (provincial). */
object Tax {
    data class Rate(val id: Long, val kind: String, val authority: String, val rateBp: Int, val from: String)

    fun all(): List<Rate> = Db.query("SELECT * FROM tax_rates ORDER BY kind, from_date DESC").map {
        Rate(it.long("id"), it.str("kind"), it.str("authority"), it.int("rate_bp"), it.str("from_date"))
    }

    fun rateFor(kind: String, date: String): Rate? {
        if (!Settings.bool(Settings.TAX_REGISTERED)) return null
        return Db.one("SELECT * FROM tax_rates WHERE kind=? AND from_date<=? ORDER BY from_date DESC LIMIT 1", kind, date.take(10))
            ?.let { Rate(it.long("id"), it.str("kind"), it.str("authority"), it.int("rate_bp"), it.str("from_date")) }
    }

    fun add(kind: String, authority: String, rateBp: Int, from: String) {
        Db.insert("INSERT INTO tax_rates(kind,authority,rate_bp,from_date) VALUES(?,?,?,?)", kind, authority, rateBp, from)
        Audit.log("add", "tax_rate", null, "$kind $authority $rateBp from $from")
    }

    fun delete(id: Long) { Db.exec("DELETE FROM tax_rates WHERE id=?", id) }

    fun seedDefaults() {
        if (Db.scalarLong("SELECT count(*) FROM tax_rates") > 0) return
        add("GOODS", "FBR", 1800, "2025-07-01")
        add("SERVICES", "PRA (Punjab)", 1600, "2025-07-01")
    }
}

object Defaults {
    /** First-run data that makes the app usable immediately. Safe to call repeatedly. */
    fun ensure() {
        Tax.seedDefaults()
        if (Settings.get(Settings.BUSINESS).isEmpty()) {
            Settings.set(Settings.BUSINESS, "My Auto Workshop")
            Settings.set(Settings.TERMS, "Parts carry the manufacturer's warranty. Labour warranty 7 days. Customer-supplied parts have no warranty.")
            Settings.set(Settings.FOOTER, "Thank you for your business!")
            Settings.set(Settings.LABEL_W, "50")
            Settings.set(Settings.LABEL_H, "25")
            Settings.set(Settings.POINTS_PER_100, "1")
            Settings.set(Settings.POINT_VALUE, "100")
            Settings.setBool(Settings.CREDIT_WARN, true)
            Settings.set(Settings.PROVINCE, "Punjab")
        }
        if (Db.scalarLong("SELECT count(*) FROM complaints") == 0L) {
            listOf("Engine not starting", "Noise from suspension", "AC not cooling", "Brakes squeaking", "Oil leakage",
                "Overheating", "Battery weak", "Check engine light on", "Pulling to one side", "Periodic service")
                .forEach { Db.insert("INSERT INTO complaints(text) VALUES(?)", it) }
        }
        if (Db.scalarLong("SELECT count(*) FROM services") == 0L) {
            listOf(Triple("Engine oil change (labour)", 50000L, "Mechanical"), Triple("Tuning", 250000L, "Mechanical"),
                Triple("AC service", 300000L, "AC"), Triple("Wheel alignment", 150000L, "Tyres"),
                Triple("Wheel balancing (4)", 120000L, "Tyres"), Triple("Brake service", 200000L, "Mechanical"),
                Triple("Scanning / diagnostics", 150000L, "Electrical"))
                .forEach { Db.insert("INSERT INTO services(name,price,department) VALUES(?,?,?)", it.first, it.second, it.third) }
        }
    }

    val departments = listOf("Mechanical", "Electrical", "AC", "Denting & painting", "Tyres", "Other")
    val partGrades = listOf("Genuine", "OEM-equivalent", "Local", "Used / reconditioned")
    val units = listOf("pc", "set", "litre", "metre", "kg", "pair")
    val customerTypes = listOf("Walk-in", "Regular", "Company / fleet", "Insurance company")
    val fuels = listOf("Petrol", "Diesel", "Hybrid", "CNG", "Electric")
    val makes = listOf("Suzuki", "Toyota", "Honda", "KIA", "Hyundai", "Changan", "MG", "Haval", "Proton", "BYD", "Nissan", "Daihatsu", "Mitsubishi", "Other")
    val jobStatuses = listOf("Received", "Inspecting", "Awaiting approval", "Awaiting parts", "In progress", "Quality check", "Ready", "Delivered", "Cancelled")
    val payMethods = listOf("Cash", "Card", "Bank transfer", "Raast", "JazzCash", "Easypaisa", "Cheque")
}
