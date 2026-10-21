package pk.workshop.data

import pk.workshop.core.Dates
import pk.workshop.db.Db
import pk.workshop.db.Row
import pk.workshop.db.fail

data class Customer(
    val id: Long = 0, val name: String = "", val phone: String = "", val phone2: String = "", val cnic: String = "", val address: String = "",
    val city: String = "", val type: String = "Walk-in", val ntn: String = "", val creditLimit: Long = 0, val termsDays: Int = 0, val points: Long = 0,
    val notes: String = "", val active: Boolean = true,
)

data class Vehicle(
    val id: Long = 0, val customerId: Long = 0, val regNo: String = "", val make: String = "", val model: String = "", val variant: String = "",
    val year: String = "", val color: String = "", val engineNo: String = "", val chassisNo: String = "", val fuel: String = "Petrol",
    val transmission: String = "Manual", val odometer: Long = 0, val nextServiceKm: Long? = null, val nextServiceDate: String? = null, val notes: String = "",
    val ownerName: String = "", val ownerPhone: String = "",
) {
    val label get() = listOf(regNo, make, model, year).filter { it.isNotBlank() }.joinToString(" ")
}

data class Booking(
    val id: Long = 0, val customerId: Long? = null, val vehicleId: Long? = null, val name: String = "", val phone: String = "", val regNo: String = "",
    val whenAt: String = "", val service: String = "", val pickup: Boolean = false, val pickupAddress: String = "", val driver: String = "",
    val returnAt: String = "", val status: String = "Booked", val jobId: Long? = null, val notes: String = "",
)

object Customers {
    private fun map(r: Row) = Customer(r.long("id"), r.str("name"), r.str("phone"), r.str("phone2"), r.str("cnic"), r.str("address"), r.str("city"),
        r.str("ctype"), r.str("ntn"), r.long("credit_limit"), r.int("terms_days"), r.long("points"), r.str("notes"), r.bool("active"))

    fun list(search: String = "", type: String? = null): List<Customer> {
        val s = "%${search.trim()}%"
        return Db.query("""SELECT * FROM customers c WHERE (name LIKE ? OR phone LIKE ? OR phone2 LIKE ? OR cnic LIKE ?
            OR EXISTS(SELECT 1 FROM vehicles v WHERE v.customer_id=c.id AND (replace(v.reg_no,'-','') LIKE replace(?,'-','') OR v.chassis_no LIKE ?)))
            AND (? IS NULL OR ctype=?) ORDER BY active DESC, name LIMIT 2000""", s, s, s, s, s, s, type, type).map(::map)
    }

    fun get(id: Long) = Db.one("SELECT * FROM customers WHERE id=?", id)?.let(::map) ?: fail("Customer not found")

    fun walkIn(): Long = Db.scalarStr("SELECT id FROM customers WHERE name='Walk-in customer'")?.toLong()
        ?: save(Customer(name = "Walk-in customer", type = "Walk-in"))

    /** Mobile (0300-1234567) or landline with area code (042-35761234). */
    fun validatePhone(p: String): Boolean {
        val t = p.replace(" ", "")
        return t.isBlank() || Regex("""^(\+92|0092|0)?3\d{2}-?\d{7}$""").matches(t) || Regex("""^(\+92|0092|0)\d{2,4}-?\d{6,8}$""").matches(t)
    }
    fun validateCnic(c: String) = c.isBlank() || Regex("""^\d{5}-?\d{7}-?\d$""").matches(c.trim())

    fun save(c: Customer): Long {
        if (c.name.isBlank()) fail("Enter the customer's name.")
        if (!validatePhone(c.phone)) fail("Phone should look like 0300-1234567 (mobile) or 042-35761234 (landline).")
        if (!validateCnic(c.cnic)) fail("CNIC should look like 35202-1234567-1.")
        if (c.phone.isNotBlank()) {
            val dup = Db.one("SELECT name FROM customers WHERE replace(phone,'-','')=replace(?,'-','') AND id<>?", c.phone, c.id)
            if (dup != null) fail("This phone number already belongs to ${dup.str("name")}.")
        }
        return if (c.id == 0L) Db.insert("""INSERT INTO customers(name,phone,phone2,cnic,address,city,ctype,ntn,credit_limit,terms_days,notes,created_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""", c.name.trim(), c.phone.trim(), c.phone2.trim(), c.cnic.trim(), c.address, c.city, c.type, c.ntn,
            c.creditLimit, c.termsDays, c.notes, Dates.now()).also { Audit.log("create", "customer", it, c.name) }
        else {
            Db.exec("""UPDATE customers SET name=?,phone=?,phone2=?,cnic=?,address=?,city=?,ctype=?,ntn=?,credit_limit=?,terms_days=?,notes=?,active=? WHERE id=?""",
                c.name.trim(), c.phone.trim(), c.phone2.trim(), c.cnic.trim(), c.address, c.city, c.type, c.ntn, c.creditLimit, c.termsDays, c.notes, c.active, c.id)
            Audit.log("update", "customer", c.id, c.name); c.id
        }
    }

    fun balance(id: Long) = Ledger.customerBalance(id)

    fun openingBalance(id: Long, amount: Long, date: String = Dates.today()) {
        if (amount <= 0) fail("Enter the amount the customer owes.")
        Ledger.post("OPEN", date, "Opening balance: ${get(id).name}",
            listOf(LedgerLine(Accounts.sys("AR"), debit = amount, partyType = "C", partyId = id), LedgerLine(Accounts.sys("OPENING"), credit = amount)), "customer", id)
    }

    /** Credit check before giving more credit. Returns a warning text or null. */
    fun creditWarning(id: Long, extra: Long): String? {
        val c = get(id)
        val bal = balance(id)
        if (c.creditLimit > 0 && bal + extra > c.creditLimit)
            return "${c.name} would owe ${pk.workshop.core.Money.rs(bal + extra)}, above the credit limit of ${pk.workshop.core.Money.rs(c.creditLimit)}."
        if (c.termsDays > 0) {
            val oldest = Db.scalarStr("""SELECT min(date) FROM invoices i WHERE customer_id=? AND status='Posted' AND kind<>'Credit note'
                AND total - coalesce((SELECT sum(amount) FROM payment_allocations a WHERE a.invoice_id=i.id),0) > 0""", id)
            if (oldest != null && Dates.daysBetween(oldest, Dates.today()) > c.termsDays) return "${c.name} has bills older than ${c.termsDays} days still unpaid."
        }
        return null
    }

    // ---------- loyalty ----------

    fun addPoints(customerId: Long, points: Long, invoiceId: Long?, note: String) {
        if (points == 0L) return
        Db.insert("INSERT INTO loyalty_moves(customer_id,date,points,invoice_id,note) VALUES(?,?,?,?,?)", customerId, Dates.today(), points, invoiceId, note)
        Db.exec("UPDATE customers SET points=points+? WHERE id=?", points, customerId)
    }
}

object Vehicles {
    private fun map(r: Row) = Vehicle(r.long("id"), r.long("customer_id"), r.str("reg_no"), r.str("make"), r.str("model"), r.str("variant"), r.str("year"),
        r.str("color"), r.str("engine_no"), r.str("chassis_no"), r.str("fuel"), r.str("transmission"), r.long("odometer"), r.longOrNull("next_service_km"),
        r.strOrNull("next_service_date"), r.str("notes"), r.str("cname"), r.str("cphone"))

    private const val SELECT = "SELECT v.*, c.name cname, c.phone cphone FROM vehicles v JOIN customers c ON c.id=v.customer_id"

    fun forCustomer(customerId: Long) = Db.query("$SELECT WHERE v.customer_id=? ORDER BY v.reg_no", customerId).map(::map)
    fun get(id: Long) = Db.one("$SELECT WHERE v.id=?", id)?.let(::map) ?: fail("Vehicle not found")
    fun search(text: String): List<Vehicle> {
        val s = "%${text.replace("-", "").replace(" ", "")}%"
        return Db.query("$SELECT WHERE replace(replace(v.reg_no,'-',''),' ','') LIKE ? OR v.chassis_no LIKE ? OR c.name LIKE ? OR c.phone LIKE ? ORDER BY v.reg_no LIMIT 200",
            s, s, "%$text%", "%$text%").map(::map)
    }
    fun byReg(reg: String): Vehicle? = Db.one("$SELECT WHERE upper(replace(replace(v.reg_no,'-',''),' ',''))=upper(?)",
        reg.replace("-", "").replace(" ", ""))?.let(::map)

    fun save(v: Vehicle): Long {
        if (v.regNo.isBlank()) fail("Enter the registration number.")
        if (v.customerId == 0L) fail("Choose the owner.")
        val dup = byReg(v.regNo)
        if (dup != null && dup.id != v.id) fail("${v.regNo} is already registered to ${dup.ownerName}.")
        return if (v.id == 0L) Db.insert("""INSERT INTO vehicles(customer_id,reg_no,make,model,variant,year,color,engine_no,chassis_no,fuel,transmission,odometer,
            next_service_km,next_service_date,notes) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", v.customerId, v.regNo.trim().uppercase(), v.make, v.model, v.variant,
            v.year, v.color, v.engineNo, v.chassisNo, v.fuel, v.transmission, v.odometer, v.nextServiceKm, v.nextServiceDate, v.notes)
            .also { Audit.log("create", "vehicle", it, v.regNo) }
        else {
            val old = get(v.id)
            Db.exec("""UPDATE vehicles SET customer_id=?,reg_no=?,make=?,model=?,variant=?,year=?,color=?,engine_no=?,chassis_no=?,fuel=?,transmission=?,
                odometer=?,next_service_km=?,next_service_date=?,notes=? WHERE id=?""", v.customerId, v.regNo.trim().uppercase(), v.make, v.model, v.variant,
                v.year, v.color, v.engineNo, v.chassisNo, v.fuel, v.transmission, v.odometer, v.nextServiceKm, v.nextServiceDate, v.notes, v.id)
            if (old.customerId != v.customerId) Audit.log("transfer", "vehicle", v.id, "${v.regNo}: ${old.ownerName} → ${Customers.get(v.customerId).name}")
            v.id
        }
    }

    /** Service reminders due: by date within [days] days, or within 500 km of the next service. */
    fun dueForService(days: Long = 7): List<Vehicle> {
        val until = Dates.plusDays(Dates.today(), days)
        return Db.query("""$SELECT WHERE (v.next_service_date IS NOT NULL AND v.next_service_date<=?)
            OR (v.next_service_km IS NOT NULL AND v.next_service_km - v.odometer <= 500) ORDER BY v.next_service_date""", until).map(::map)
    }

    fun history(vehicleId: Long) = Db.query("""SELECT j.id, j.job_no, j.date_in, j.odometer, j.status, j.complaint,
        coalesce((SELECT total FROM invoices WHERE id=j.invoice_id),0) total,
        (SELECT group_concat(description, ', ') FROM job_lines WHERE job_id=j.id) work
        FROM job_cards j WHERE vehicle_id=? ORDER BY j.date_in DESC""", vehicleId)
}

object Bookings {
    private fun map(r: Row) = Booking(r.long("id"), r.longOrNull("customer_id"), r.longOrNull("vehicle_id"), r.str("name"), r.str("phone"), r.str("reg_no"),
        r.str("when_at"), r.str("service"), r.bool("pickup"), r.str("pickup_address"), r.str("driver"), r.str("return_at"), r.str("status"),
        r.longOrNull("job_id"), r.str("notes"))

    fun list(from: String, to: String): List<Booking> = Db.query("SELECT * FROM bookings WHERE substr(when_at,1,10) BETWEEN ? AND ? ORDER BY when_at", from, to).map(::map)
    fun get(id: Long) = Db.one("SELECT * FROM bookings WHERE id=?", id)?.let(::map) ?: fail("Booking not found")

    fun save(b: Booking): Long {
        if (b.name.isBlank() && b.customerId == null) fail("Enter the customer's name or choose a customer.")
        if (b.whenAt.isBlank()) fail("Enter the booking date and time.")
        if (b.pickup && b.pickupAddress.isBlank()) fail("Enter the pickup address.")
        return if (b.id == 0L) Db.insert("""INSERT INTO bookings(customer_id,vehicle_id,name,phone,reg_no,when_at,service,pickup,pickup_address,driver,return_at,status,notes)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)""", b.customerId, b.vehicleId, b.name, b.phone, b.regNo, b.whenAt, b.service, b.pickup, b.pickupAddress,
            b.driver, b.returnAt, b.status, b.notes)
        else { Db.exec("""UPDATE bookings SET customer_id=?,vehicle_id=?,name=?,phone=?,reg_no=?,when_at=?,service=?,pickup=?,pickup_address=?,driver=?,
            return_at=?,status=?,notes=? WHERE id=?""", b.customerId, b.vehicleId, b.name, b.phone, b.regNo, b.whenAt, b.service, b.pickup, b.pickupAddress,
            b.driver, b.returnAt, b.status, b.notes, b.id); b.id }
    }

    fun setStatus(id: Long, status: String, jobId: Long? = null) {
        Db.exec("UPDATE bookings SET status=?, job_id=coalesce(?, job_id) WHERE id=?", status, jobId, id)
    }
}
