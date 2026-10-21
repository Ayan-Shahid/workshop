package pk.workshop.data

import pk.workshop.core.Dates
import pk.workshop.core.Money
import pk.workshop.core.Qty
import pk.workshop.db.Db
import pk.workshop.db.Row
import pk.workshop.db.fail

data class Technician(val id: Long = 0, val name: String = "", val phone: String = "", val commissionBp: Int = 0, val department: String = "", val active: Boolean = true)
data class ServiceItem(val id: Long = 0, val name: String = "", val price: Long = 0, val department: String = "", val active: Boolean = true)

data class JobCard(
    val id: Long = 0, val jobNo: String = "", val customerId: Long = 0, val vehicleId: Long = 0, val dateIn: String = "", val odometer: Long = 0,
    val fuel: String = "Half", val complaint: String = "", val inspection: String = "", val itemsInCar: String = "", val broughtBy: String = "",
    val broughtPhone: String = "", val priority: String = "Normal", val department: String = "Mechanical", val status: String = "Received",
    val promisedAt: String = "", val bay: String = "", val diagnosis: String = "", val approvedBy: String = "", val approvedAt: String = "",
    val qcNote: String = "", val deliveredAt: String = "", val invoiceId: Long? = null, val comebackOf: Long? = null, val notes: String = "",
    val customerName: String = "", val customerPhone: String = "", val vehicleLabel: String = "",
)

data class JobLine(
    val id: Long = 0, val jobId: Long = 0, val kind: String = "LABOUR", val partId: Long? = null, val description: String = "", val qty: Long = 1000,
    val rate: Long = 0, val discount: Long = 0, val technicianId: Long? = null, val cost: Long = 0, val vendor: String = "", val issued: Boolean = false,
    val customerSupplied: Boolean = false, val technician: String = "",
) {
    val gross get() = Money.times(rate, qty)
    val net get() = gross - discount
}

object Technicians {
    private fun map(r: Row) = Technician(r.long("id"), r.str("name"), r.str("phone"), r.int("commission_bp"), r.str("department"), r.bool("active"))
    fun list(all: Boolean = false) = Db.query("SELECT * FROM technicians WHERE ?=1 OR active=1 ORDER BY name", all).map(::map)
    fun save(t: Technician): Long {
        if (t.name.isBlank()) fail("Enter the technician's name.")
        return if (t.id == 0L) Db.insert("INSERT INTO technicians(name,phone,commission_bp,department) VALUES(?,?,?,?)", t.name, t.phone, t.commissionBp, t.department)
        else { Db.exec("UPDATE technicians SET name=?,phone=?,commission_bp=?,department=?,active=? WHERE id=?", t.name, t.phone, t.commissionBp, t.department, t.active, t.id); t.id }
    }
}

object Services {
    private fun map(r: Row) = ServiceItem(r.long("id"), r.str("name"), r.long("price"), r.str("department"), r.bool("active"))
    fun list(all: Boolean = false) = Db.query("SELECT * FROM services WHERE ?=1 OR active=1 ORDER BY name", all).map(::map)
    fun save(s: ServiceItem): Long {
        if (s.name.isBlank()) fail("Enter the service name.")
        return if (s.id == 0L) Db.insert("INSERT INTO services(name,price,department) VALUES(?,?,?)", s.name, s.price, s.department)
        else { Db.exec("UPDATE services SET name=?,price=?,department=?,active=? WHERE id=?", s.name, s.price, s.department, s.active, s.id); s.id }
    }
    fun complaints() = Db.query("SELECT * FROM complaints ORDER BY text").map { it.long("id") to it.str("text") }
    fun addComplaint(t: String) { if (t.isNotBlank()) Db.insert("INSERT INTO complaints(text) VALUES(?)", t.trim()) }
    fun deleteComplaint(id: Long) { Db.exec("DELETE FROM complaints WHERE id=?", id) }
}

object Jobs {
    private const val SELECT = """SELECT j.*, c.name cname, c.phone cphone, v.reg_no||' '||coalesce(v.make,'')||' '||coalesce(v.model,'') vlabel
        FROM job_cards j JOIN customers c ON c.id=j.customer_id JOIN vehicles v ON v.id=j.vehicle_id"""

    private fun map(r: Row) = JobCard(r.long("id"), r.str("job_no"), r.long("customer_id"), r.long("vehicle_id"), r.str("date_in"), r.long("odometer"),
        r.str("fuel"), r.str("complaint"), r.str("inspection"), r.str("items_in_car"), r.str("brought_by"), r.str("brought_phone"), r.str("priority"),
        r.str("department"), r.str("status"), r.str("promised_at"), r.str("bay"), r.str("diagnosis"), r.str("approved_by"), r.str("approved_at"),
        r.str("qc_note"), r.str("delivered_at"), r.longOrNull("invoice_id"), r.longOrNull("comeback_of"), r.str("notes"), r.str("cname"), r.str("cphone"),
        r.str("vlabel").trim())

    fun list(status: String? = null, search: String = "", openOnly: Boolean = false): List<JobCard> {
        val s = "%${search.trim()}%"
        return Db.query("""$SELECT WHERE (? IS NULL OR j.status=?) AND (?=0 OR j.status NOT IN ('Delivered','Cancelled'))
            AND (j.job_no LIKE ? OR c.name LIKE ? OR c.phone LIKE ? OR v.reg_no LIKE ?) ORDER BY j.id DESC LIMIT 1000""",
            status, status, openOnly, s, s, s, s).map(::map)
    }

    fun get(id: Long) = Db.one("$SELECT WHERE j.id=?", id)?.let(::map) ?: fail("Job card not found")
    fun byNo(no: String) = Db.one("$SELECT WHERE j.job_no=?", no.trim())?.let(::map)

    fun create(j: JobCard): Long = Db.tx {
        if (j.vehicleId == 0L) fail("Choose the vehicle.")
        val v = Vehicles.get(j.vehicleId)
        val date = j.dateIn.ifBlank { Dates.now() }
        val no = Numbers.next("JC", date)
        // A repeat visit within 15 days for the same vehicle is flagged as a comeback.
        val prev = Db.one("SELECT id, date_in FROM job_cards WHERE vehicle_id=? AND status='Delivered' ORDER BY id DESC LIMIT 1", j.vehicleId)
        val comeback = prev?.takeIf { Dates.daysBetween(it.str("date_in"), date) <= 15 }?.long("id")
        val id = Db.insert("""INSERT INTO job_cards(job_no,customer_id,vehicle_id,date_in,odometer,fuel,complaint,inspection,items_in_car,brought_by,brought_phone,
            priority,department,status,promised_at,bay,notes,comeback_of) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            no, v.customerId, j.vehicleId, date, j.odometer, j.fuel, j.complaint, j.inspection, j.itemsInCar, j.broughtBy, j.broughtPhone, j.priority,
            j.department, "Received", j.promisedAt, j.bay, j.notes, comeback)
        if (j.odometer > v.odometer) Db.exec("UPDATE vehicles SET odometer=? WHERE id=?", j.odometer, j.vehicleId)
        Audit.log("create", "job", id, "$no ${v.regNo}")
        id
    }

    fun update(j: JobCard) {
        val old = get(j.id)
        if (old.status == "Delivered") fail("A delivered job card cannot be changed.")
        Db.exec("""UPDATE job_cards SET odometer=?,fuel=?,complaint=?,inspection=?,items_in_car=?,brought_by=?,brought_phone=?,priority=?,department=?,
            promised_at=?,bay=?,diagnosis=?,qc_note=?,notes=? WHERE id=?""", j.odometer, j.fuel, j.complaint, j.inspection, j.itemsInCar, j.broughtBy,
            j.broughtPhone, j.priority, j.department, j.promisedAt, j.bay, j.diagnosis, j.qcNote, j.notes, j.id)
    }

    fun setStatus(id: Long, status: String) = Db.tx {
        val j = get(id)
        if (j.status == "Delivered") fail("This job is already delivered.")
        if (status == "Delivered") fail("Use Deliver to hand the car back.")
        if (status == "Ready" && lines(id).isEmpty()) fail("Add the work done before marking the job Ready.")
        if (status == "Cancelled") {
            if (j.invoiceId != null) fail("This job is already billed; issue a credit note instead.")
            lines(id).filter { it.issued && it.partId != null }.forEach { returnPart(it) }
        }
        Db.exec("UPDATE job_cards SET status=? WHERE id=?", status, id)
        Audit.log("status", "job", id, "${j.jobNo}: ${j.status} → $status")
    }

    fun approve(id: Long, by: String, how: String) {
        if (by.isBlank()) fail("Enter who approved the estimate.")
        Db.exec("UPDATE job_cards SET approved_by=?, approved_at=?, status=CASE WHEN status IN ('Received','Inspecting','Awaiting approval') THEN 'In progress' ELSE status END WHERE id=?",
            "$by ($how)", Dates.now(), id)
        Audit.log("approve", "job", id, "$by via $how")
    }

    // ---------- lines ----------

    fun lines(jobId: Long): List<JobLine> = Db.query("""SELECT l.*, t.name tname FROM job_lines l LEFT JOIN technicians t ON t.id=l.technician_id
        WHERE job_id=? ORDER BY l.kind DESC, l.id""", jobId).map {
        JobLine(it.long("id"), it.long("job_id"), it.str("kind"), it.longOrNull("part_id"), it.str("description"), it.long("qty"), it.long("rate"),
            it.long("discount"), it.longOrNull("technician_id"), it.long("cost"), it.str("vendor"), it.bool("issued"), it.bool("customer_supplied"), it.str("tname"))
    }

    /** Adds a line; a stocked part is issued from the store at once (stock drops immediately). */
    fun addLine(l: JobLine): Long = Db.tx {
        val j = get(l.jobId)
        if (j.invoiceId != null || j.status == "Delivered" || j.status == "Cancelled") fail("This job is closed; lines cannot be added.")
        if (l.qty <= 0) fail("Quantity must be above zero.")
        if (l.description.isBlank() && l.partId == null) fail("Enter a description.")
        var cost = l.cost
        var desc = l.description
        if (l.kind == "PART" && l.partId != null && !l.customerSupplied) {
            val p = Inventory.get(l.partId)
            if (p.qty < l.qty) fail("Only ${Qty.fmt(p.qty)} ${p.unit} of ${p.name} in stock.")
            cost = Money.times(p.avgCost, l.qty)
            if (desc.isBlank()) desc = p.name
        }
        val id = Db.insert("""INSERT INTO job_lines(job_id,kind,part_id,description,qty,rate,discount,technician_id,cost,vendor,issued,customer_supplied)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""", l.jobId, l.kind, l.partId, desc, l.qty, if (l.customerSupplied) 0 else l.rate, l.discount, l.technicianId,
            cost, l.vendor, l.kind == "PART" && l.partId != null && !l.customerSupplied, l.customerSupplied)
        if (l.kind == "PART" && l.partId != null && !l.customerSupplied) {
            Inventory.move(l.partId, Dates.today(), "ISSUE", -l.qty, Inventory.get(l.partId).avgCost, "JC", l.jobId, j.jobNo)
        }
        id
    }

    fun updateLine(l: JobLine) {
        val old = lines(l.jobId).firstOrNull { it.id == l.id } ?: fail("Line not found")
        if (get(l.jobId).invoiceId != null) fail("This job is billed; lines cannot change.")
        if (old.partId != null && old.issued && old.qty != l.qty) fail("To change a part's quantity, remove the line and add it again.")
        Db.exec("UPDATE job_lines SET description=?, rate=?, discount=?, technician_id=?, vendor=?, cost=CASE WHEN kind='SUBLET' THEN ? ELSE cost END WHERE id=?",
            l.description, l.rate, l.discount, l.technicianId, l.vendor, l.cost, l.id)
    }

    private fun returnPart(l: JobLine) {
        val p = Inventory.get(l.partId!!)
        // Back on the shelf at the cost it was issued at
        Inventory.receive(l.partId, Dates.today(), l.qty, if (l.cost > 0) Inventory.unitCost(l.cost, l.qty) else p.avgCost, "JC", l.jobId, "Returned from job", kind = "RETURN_IN")
        Db.exec("UPDATE job_lines SET issued=0 WHERE id=?", l.id)
    }

    /** Removes a line; unused parts go back to the store. */
    fun removeLine(lineId: Long) = Db.tx {
        val r = Db.one("SELECT job_id FROM job_lines WHERE id=?", lineId) ?: fail("Line not found")
        val l = lines(r.long("job_id")).first { it.id == lineId }
        if (get(l.jobId).invoiceId != null) fail("This job is billed; lines cannot change.")
        if (l.issued && l.partId != null) returnPart(l)
        Db.exec("DELETE FROM job_lines WHERE id=?", lineId)
    }

    // ---------- time ----------

    fun startTime(jobId: Long, techId: Long?) {
        Db.insert("INSERT INTO job_time(job_id,technician_id,started) VALUES(?,?,?)", jobId, techId, Dates.now())
        Db.exec("UPDATE job_cards SET status='In progress' WHERE id=? AND status IN ('Received','Inspecting','Awaiting parts')", jobId)
    }
    fun stopTime(jobId: Long) { Db.exec("UPDATE job_time SET stopped=? WHERE job_id=? AND stopped IS NULL", Dates.now(), jobId) }
    fun timeLog(jobId: Long) = Db.query("SELECT t.*, x.name tname FROM job_time t LEFT JOIN technicians x ON x.id=t.technician_id WHERE job_id=? ORDER BY t.id", jobId)
    fun runningTimer(jobId: Long) = Db.scalarLong("SELECT count(*) FROM job_time WHERE job_id=? AND stopped IS NULL", jobId) > 0

    // ---------- attachments (inspection photos, scanner reports) ----------

    fun attach(jobId: Long, source: java.io.File, label: String): Long {
        val dir = java.io.File(pk.workshop.security.Vault.dataDir, "attachments/job-$jobId").apply { mkdirs() }
        val dest = java.io.File(dir, System.currentTimeMillis().toString() + "-" + source.name)
        source.copyTo(dest)
        return Db.insert("INSERT INTO attachments(entity,entity_id,path,label,added) VALUES('job',?,?,?,?)", jobId, dest.path, label, Dates.now())
    }
    fun attachments(jobId: Long) = Db.query("SELECT * FROM attachments WHERE entity='job' AND entity_id=? ORDER BY id", jobId)

    // ---------- estimates ----------

    fun saveEstimate(jobId: Long): Long {
        val j = get(jobId)
        val ls = lines(jobId)
        if (ls.isEmpty()) fail("Add lines before making an estimate.")
        val text = ls.joinToString("\n") { listOf(it.kind, it.description.replace("|", "/").replace("\n", " "), it.qty, it.rate, it.discount).joinToString("|") }
        return Db.insert("INSERT INTO estimates(est_no,date,customer_id,vehicle_id,job_id,total,lines) VALUES(?,?,?,?,?,?,?)",
            Numbers.next("EST"), Dates.today(), j.customerId, j.vehicleId, jobId, ls.sumOf { it.net }, text)
    }

    fun estimates(jobId: Long) = Db.query("SELECT * FROM estimates WHERE job_id=? ORDER BY id DESC", jobId)

    /** Gate pass: allowed when the invoice is fully paid, or the customer is on credit terms. */
    fun deliver(jobId: Long, allowCredit: Boolean): String = Db.tx {
        val j = get(jobId)
        val invId = j.invoiceId ?: fail("Make the invoice before delivering the car.")
        val due = Billing.due(invId)
        if (due > 0 && !allowCredit) fail("${Money.rs(due)} is still unpaid. Take payment, or deliver on credit.")
        Db.exec("UPDATE job_cards SET status='Delivered', delivered_at=? WHERE id=?", Dates.now(), jobId)
        stopTime(jobId)
        Audit.log("deliver", "job", jobId, j.jobNo + if (due > 0) " on credit ${Money.rs(due)}" else "")
        j.jobNo
    }
}
