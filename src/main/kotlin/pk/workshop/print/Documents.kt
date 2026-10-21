package pk.workshop.print

import org.apache.pdfbox.pdmodel.common.PDRectangle
import pk.workshop.core.Dates
import pk.workshop.core.Money
import pk.workshop.core.Pct
import pk.workshop.core.Qty
import pk.workshop.data.*
import pk.workshop.db.Db
import java.awt.Color
import java.io.File

/** Builds every printable document as a PDF in Documents/Workshop Manager. */
object Documents {
    private val red = Color(0xC6, 0x28, 0x28)

    val outDir: File get() = File(System.getProperty("user.home"), "Documents/Workshop Manager").apply { mkdirs() }
    private fun out(sub: String, name: String) = File(outDir, "$sub/${name.replace(Regex("[^A-Za-z0-9._-]"), "_")}.pdf")

    private fun businessHeader(k: PdfKit, title: String, rightLines: List<String>) {
        k.y -= 18
        k.text(Settings.get(Settings.BUSINESS), 0f, k.y, 17f, k.bold, color = red)
        k.text(title, k.width, k.y, 15f, k.bold, Align.RIGHT)
        val info = listOf(Settings.get(Settings.ADDRESS), Settings.get(Settings.PHONE).let { if (it.isNotBlank()) "Phone: $it" else "" },
            listOfNotNull(Settings.get(Settings.NTN).takeIf { it.isNotBlank() }?.let { "NTN: $it" },
                Settings.get(Settings.STRN).takeIf { it.isNotBlank() }?.let { "STRN: $it" },
                Settings.get(Settings.PROV_REG).takeIf { it.isNotBlank() }?.let { "Provincial reg: $it" }).joinToString("   ")).filter { it.isNotBlank() }
        var yy = k.y
        for (l in info) { yy -= 12; k.text(l, 0f, yy, 9f) }
        var ry = k.y
        for (l in rightLines) { ry -= 12; k.text(l, k.width, ry, 9f, align = Align.RIGHT) }
        k.y = minOf(yy, ry) - 10
        k.line(0f, k.y, k.width, k.y, 1.2f, red)
        k.y -= 6
    }

    // ---------- invoice ----------

    fun invoice(invoiceId: Long): File {
        val inv = Billing.get(invoiceId)
        val lines = Billing.lines(invoiceId)
        val credit = inv.kind == "Credit note"
        val registered = Settings.bool(Settings.TAX_REGISTERED)
        val title = when { credit -> "CREDIT NOTE"; registered -> "SALES TAX INVOICE"; else -> "INVOICE" }
        val k = PdfKit()
        val right = listOfNotNull("No: ${inv.no}", "Date: ${Dates.show(inv.date)}", inv.jobNo.takeIf { it.isNotBlank() }?.let { "Job card: $it" },
            inv.againstId?.let { "Against: ${Billing.get(it).no}" })
        businessHeader(k, title, right)

        // Bill to / vehicle
        k.y -= 12
        k.text("BILL TO", 0f, k.y, 8f, k.bold, color = Color.GRAY)
        if (inv.vehicle.isNotBlank()) k.text("VEHICLE", k.width / 2, k.y, 8f, k.bold, color = Color.GRAY)
        val cust = inv.customerId?.let { Customers.get(it) }
        val leftLines = listOfNotNull(inv.customer, inv.customerPhone.takeIf { it.isNotBlank() },
            cust?.ntn?.takeIf { it.isNotBlank() }?.let { "NTN: $it" }, cust?.address?.takeIf { it.isNotBlank() })
        val rightLines = if (inv.vehicle.isBlank()) emptyList() else listOfNotNull(inv.vehicle, inv.odometer.takeIf { it > 0 }?.let { "Odometer: ${"%,d".format(it)} km" })
        var a = k.y; var b = k.y
        leftLines.forEachIndexed { i, s -> a -= 12; k.text(s, 0f, a, 10f, if (i == 0) k.bold else k.regular) }
        rightLines.forEachIndexed { i, s -> b -= 12; k.text(s, k.width / 2, b, 10f, if (i == 0) k.bold else k.regular) }
        k.y = minOf(a, b) - 14

        fun section(name: String, ls: List<InvLine>) {
            if (ls.isEmpty()) return
            k.ensure(60f)
            k.text(name, 0f, k.y, 10f, k.bold, color = red); k.y -= 4
            val cols = listOf(Col("#", 18f), Col("Description", 200f), Col("Qty", 40f, Align.RIGHT), Col("Rate", 62f, Align.RIGHT),
                Col("Discount", 55f, Align.RIGHT), Col("Tax %", 38f, Align.RIGHT), Col("Tax", 55f, Align.RIGHT), Col("Amount", 70f, Align.RIGHT))
            k.table(cols, ls.mapIndexed { i, l ->
                listOf("${i + 1}", l.description, Qty.fmt(kotlin.math.abs(l.qty)), Money.plain(l.rate), if (l.discount != 0L) Money.plain(kotlin.math.abs(l.discount)) else "",
                    if (l.taxBp > 0) Pct.fmt(l.taxBp) else "", if (l.tax != 0L) Money.plain(kotlin.math.abs(l.tax)) else "", Money.plain(kotlin.math.abs(l.net + l.tax)))
            })
            k.y -= 10
        }
        section("PARTS", lines.filter { it.kind == "PART" })
        section("LABOUR AND SERVICES", lines.filter { it.kind != "PART" })

        // Totals box
        val gRate = Tax.rateFor("GOODS", inv.date); val sRate = Tax.rateFor("SERVICES", inv.date)
        val rows = ArrayList<Pair<String, Long>>()
        if (inv.goodsNet != 0L) rows += "Parts" to inv.goodsNet
        if (inv.goodsTax != 0L) rows += "Sales tax ${gRate?.authority ?: "FBR"} ${gRate?.let { Pct.fmt(it.rateBp) } ?: ""}" to inv.goodsTax
        if (inv.servicesNet != 0L) rows += "Labour and services" to inv.servicesNet
        if (inv.servicesTax != 0L) rows += "Services tax ${sRate?.authority ?: ""} ${sRate?.let { Pct.fmt(it.rateBp) } ?: ""}" to inv.servicesTax
        if (inv.rounding != 0L) rows += "Rounding" to inv.rounding
        k.ensure(140f)
        val bx = k.width - 220f
        var ty = k.y
        if (inv.discount != 0L) { k.text("Discount given (included above): ${Money.rs(kotlin.math.abs(inv.discount))}", 0f, ty, 9f, color = Color.DARK_GRAY) }
        for ((label, v) in rows) { k.text(label, bx, ty, 9.5f); k.text(Money.plain(kotlin.math.abs(v), true), k.width, ty, 9.5f, align = Align.RIGHT); ty -= 14 }
        k.line(bx, ty + 10, k.width, ty + 10, 0.8f)
        ty -= 4
        k.rect(bx - 6, ty - 6, 226f, 22f, fill = Color(0xFD, 0xEC, 0xEC), stroke = null)
        k.text(if (credit) "Total credit" else "Total", bx, ty + 1, 12f, k.bold)
        k.text(Money.rs(kotlin.math.abs(inv.total)), k.width, ty + 1, 12f, k.bold, Align.RIGHT)
        ty -= 22
        if (!credit) {
            k.text("Paid", bx, ty, 9.5f); k.text(Money.plain(inv.paid, true), k.width, ty, 9.5f, align = Align.RIGHT); ty -= 14
            k.text("Balance due", bx, ty, 10f, k.bold); k.text(Money.rs(inv.due), k.width, ty, 10f, k.bold, Align.RIGHT); ty -= 14
            if (inv.insurerShare > 0) { k.text("Insurer's share: ${Money.rs(inv.insurerShare)}", bx, ty, 9f); ty -= 14 }
        }
        k.y = ty - 6
        k.paragraph(Money.words(kotlin.math.abs(inv.total)), 9.5f, k.bold)

        // QR: FBR number when integrated, otherwise our own invoice number for quick look-up on returns
        k.ensure(110f)
        k.y -= 10
        val qrText = if (inv.fbrNo.isNotBlank() && inv.fbrNo != "PENDING") inv.fbrNo else inv.no
        k.image(Codes.qr(qrText, 240), 0f, k.y - 80, 80f, 80f)
        if (inv.fbrNo.isNotBlank() && inv.fbrNo != "PENDING") k.text("FBR invoice no: ${inv.fbrNo}", 90f, k.y - 12, 9f, k.bold)
        val terms = Settings.get(Settings.TERMS)
        var py = k.y - (if (inv.fbrNo.isNotBlank()) 28 else 12)
        for (l in k.wrap("Terms: $terms", k.width - 100, 8.5f)) { k.text(l, 90f, py, 8.5f, color = Color.DARK_GRAY); py -= 11 }
        k.text(Settings.get(Settings.FOOTER), 90f, k.y - 76, 9f, k.bold)
        k.line(k.width - 150, k.y - 70, k.width, k.y - 70, 0.6f)
        k.text("Signature", k.width - 75, k.y - 82, 8f, align = Align.CENTER)
        k.y -= 90
        return k.save(out(if (credit) "Credit notes" else "Invoices", inv.no))
    }

    /** 80 mm receipt as a PDF (for receipt printers used through their Windows driver). */
    fun receiptPdf(invoiceId: Long): File {
        val text = Escpos.receiptLines(invoiceId, 42)
        val h = mm(20f) + text.size * 11f + mm(30f)
        val k = PdfKit(PDRectangle(mm(80f), h), margin = mm(4f))
        k.y -= 4
        for (l in text) {
            k.y -= 11
            val boldLine = l.startsWith("**")
            k.text(l.removePrefix("**"), 0f, k.y, 7.9f, if (boldLine) k.monoBold else k.mono)
        }
        k.y -= 8
        k.image(Codes.qr(Billing.get(invoiceId).let { if (it.fbrNo.isNotBlank() && it.fbrNo != "PENDING") it.fbrNo else it.no }, 200), (k.width - mm(24f)) / 2, k.y - mm(24f), mm(24f), mm(24f))
        return k.save(out("Receipts", Billing.get(invoiceId).no))
    }

    // ---------- job documents ----------

    fun jobCard(jobId: Long): File {
        val j = Jobs.get(jobId)
        val v = Vehicles.get(j.vehicleId)
        val k = PdfKit()
        businessHeader(k, "JOB CARD", listOf("No: ${j.jobNo}", "In: ${Dates.show(j.dateIn)}", "Promised: ${Dates.show(j.promisedAt)}", "Priority: ${j.priority}"))
        k.image(Codes.qr(j.jobNo, 200), k.width - 70f, k.y - 70f, 70f, 70f)
        val info = listOf("Customer" to "${j.customerName}  ${j.customerPhone}", "Vehicle" to v.label, "Chassis / engine" to "${v.chassisNo} / ${v.engineNo}",
            "Odometer" to "${"%,d".format(j.odometer)} km", "Fuel" to j.fuel, "Department" to j.department, "Brought by" to "${j.broughtBy} ${j.broughtPhone}")
        for ((a, b) in info) { k.y -= 14; k.text(a, 0f, k.y, 9f, k.bold); k.text(b, 110f, k.y, 9.5f) }
        k.y -= 18; k.text("Customer's complaint", 0f, k.y, 10f, k.bold, color = red); k.paragraph(j.complaint.ifBlank { "-" }, 9.5f)
        k.y -= 10; k.text("Inspection (condition at check-in)", 0f, k.y, 10f, k.bold, color = red); k.paragraph(j.inspection.ifBlank { "-" }, 9.5f)
        k.y -= 10; k.text("Items in the car", 0f, k.y, 10f, k.bold, color = red); k.paragraph(j.itemsInCar.ifBlank { "-" }, 9.5f)
        val ls = Jobs.lines(jobId)
        if (ls.isNotEmpty()) {
            k.y -= 14; k.text("Work and parts", 0f, k.y, 10f, k.bold, color = red); k.y -= 4
            k.table(listOf(Col("Type", 50f), Col("Description", 220f), Col("Qty", 40f, Align.RIGHT), Col("Technician", 90f), Col("Amount", 70f, Align.RIGHT)),
                ls.map { listOf(it.kind.lowercase(), it.description + if (it.customerSupplied) " (customer's part)" else "", Qty.fmt(it.qty), it.technician, Money.plain(it.net)) })
        }
        k.y -= 40
        k.line(0f, k.y, 160f, k.y); k.line(k.width - 160, k.y, k.width, k.y)
        k.text("Customer signature", 80f, k.y - 12, 8f, align = Align.CENTER); k.text("Service advisor", k.width - 80, k.y - 12, 8f, align = Align.CENTER)
        return k.save(out("Job cards", j.jobNo))
    }

    fun estimate(estimateId: Long): File {
        val e = Db.one("SELECT * FROM estimates WHERE id=?", estimateId)!!
        val j = e.longOrNull("job_id")?.let { Jobs.get(it) }
        val k = PdfKit()
        businessHeader(k, "ESTIMATE", listOf("No: ${e.str("est_no")}", "Date: ${Dates.show(e.str("date"))}", "Valid for 7 days"))
        k.y -= 14
        if (j != null) { k.text("${j.customerName}  ${j.customerPhone}", 0f, k.y, 10f, k.bold); k.y -= 13; k.text(j.vehicleLabel, 0f, k.y, 10f); k.y -= 16 }
        val rows = e.str("lines").split('\n').filter { it.isNotBlank() }.map { it.split('|') }
        k.table(listOf(Col("Type", 50f), Col("Description", 240f), Col("Qty", 45f, Align.RIGHT), Col("Rate", 70f, Align.RIGHT), Col("Amount", 80f, Align.RIGHT)),
            rows.map { r ->
                val qty = r.getOrNull(2)?.toLongOrNull() ?: 0; val rate = r.getOrNull(3)?.toLongOrNull() ?: 0; val disc = r.getOrNull(4)?.toLongOrNull() ?: 0
                listOf(r[0].lowercase(), r.getOrElse(1) { "" }, Qty.fmt(qty), Money.plain(rate), Money.plain(Money.times(rate, qty) - disc))
            } + listOf(listOf("", "Estimated total (before tax)", "", "", Money.plain(e.long("total")))), boldLast = true)
        k.y -= 10
        k.paragraph("Taxes are added on the final invoice. Extra work found during repair is only done after your approval.", 9f)
        k.y -= 40; k.line(0f, k.y, 180f, k.y); k.text("Approved by (name, signature, date)", 90f, k.y - 12, 8f, align = Align.CENTER)
        return k.save(out("Estimates", e.str("est_no")))
    }

    fun gatePass(jobId: Long): File {
        val j = Jobs.get(jobId)
        val k = PdfKit(PDRectangle(mm(148f), mm(105f)), margin = mm(8f))
        k.y -= 14
        k.text(Settings.get(Settings.BUSINESS), 0f, k.y, 13f, k.bold, color = red)
        k.text("GATE PASS", k.width, k.y, 13f, k.bold, Align.RIGHT)
        val inv = j.invoiceId?.let { Billing.get(it) }
        val rows = listOf("Job card" to j.jobNo, "Vehicle" to j.vehicleLabel, "Customer" to j.customerName, "Invoice" to (inv?.no ?: "-"),
            "Payment" to (inv?.let { if (it.due <= 0) "Paid in full" else "On credit: ${Money.rs(it.due)} due" } ?: "-"), "Out at" to Dates.show(Dates.now()))
        k.y -= 10
        for ((a, b) in rows) { k.y -= 14; k.text(a, 0f, k.y, 9.5f, k.bold); k.text(b, 80f, k.y, 10f) }
        k.y -= 34; k.line(0f, k.y, 140f, k.y); k.text("Authorised", 70f, k.y - 11, 8f, align = Align.CENTER)
        k.image(Codes.qr(j.jobNo, 160), k.width - 60f, k.y - 6, 56f, 56f)
        return k.save(out("Gate passes", j.jobNo))
    }

    // ---------- accounts ----------

    fun statement(partyType: String, partyId: Long, from: String, to: String): File {
        val acct = Accounts.sys(if (partyType == "C") "AR" else "AP")
        val name = Ledger.partyName(partyType, partyId)
        val (opening, rows) = Ledger.ledger(acct, from, to, partyType, partyId)
        val k = PdfKit()
        businessHeader(k, "STATEMENT OF ACCOUNT", listOf(name, "${Dates.show(from)} to ${Dates.show(to)}"))
        k.y -= 8
        val data = ArrayList<List<String>>()
        data += listOf(Dates.show(from), "", "Opening balance", "", "", Money.plain(opening))
        rows.forEach { data += listOf(Dates.show(it.date), it.no, it.narration, if (it.debit != 0L) Money.plain(it.debit) else "", if (it.credit != 0L) Money.plain(it.credit) else "", Money.plain(it.balance)) }
        val closing = rows.lastOrNull()?.balance ?: opening
        data += listOf("", "", "Closing balance", "", "", Money.plain(closing))
        k.table(listOf(Col("Date", 60f), Col("Ref", 85f), Col("Details", 210f), Col("Debit", 65f, Align.RIGHT), Col("Credit", 65f, Align.RIGHT), Col("Balance", 70f, Align.RIGHT)), data, boldLast = true)
        k.y -= 10
        k.paragraph(if (partyType == "C") (if (closing > 0) "Amount due from you: ${Money.rs(closing)}" else "No amount is due.") else "Amount we owe: ${Money.rs(closing)}", 10f, k.bold)
        return k.save(out("Statements", "$name-${to}"))
    }

    fun voucher(voucherId: Long): File {
        val v = Ledger.voucher(voucherId)
        val k = PdfKit(PDRectangle(PDRectangle.A4.width, PDRectangle.A4.height / 2), margin = 30f)
        businessHeader(k, (Ledger.typeNames[v.type] ?: v.type).uppercase() + " VOUCHER", listOf("No: ${v.no}", "Date: ${Dates.show(v.date)}"))
        k.y -= 6; k.paragraph(v.narration, 9.5f); k.y -= 6
        val ls = Ledger.lines(voucherId)
        k.table(listOf(Col("Account", 230f), Col("Party / memo", 150f), Col("Debit", 70f, Align.RIGHT), Col("Credit", 70f, Align.RIGHT)),
            ls.map { listOf(it.account, listOf(it.party, it.memo).filter { s -> s.isNotBlank() }.joinToString(" — "), if (it.debit > 0) Money.plain(it.debit) else "", if (it.credit > 0) Money.plain(it.credit) else "") } +
                listOf(listOf("Total", "", Money.plain(ls.sumOf { it.debit }), Money.plain(ls.sumOf { it.credit }))), boldLast = true)
        k.y -= 36
        listOf("Prepared by", "Checked by", "Received by").forEachIndexed { i, s -> val x = i * (k.width / 3); k.line(x, k.y, x + 140, k.y); k.text(s, x + 70, k.y - 11, 8f, align = Align.CENTER) }
        return k.save(out("Vouchers", v.no))
    }

    /** Any report table as an A4 PDF (landscape when wide). */
    fun table(t: Table, period: String): File {
        val landscape = t.columns.size > 6
        val k = PdfKit(if (landscape) PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width) else PDRectangle.A4)
        businessHeader(k, t.title.substringBefore(" —").uppercase().take(40), listOf(period, "Printed ${Dates.show(Dates.now())}"))
        if (t.title.contains(" —")) { k.paragraph(t.title.substringAfter("— "), 9f) }
        k.y -= 6
        val cols = t.columns.mapIndexed { i, c -> Col(c, if (i in t.money || i in t.qty) 70f else if (i == 0 || c.length > 10) 120f else 80f, if (i in t.money || i in t.qty) Align.RIGHT else Align.LEFT) }
        val rows = t.rows.map { r -> r.mapIndexed { i, v -> cell(t, i, v) } }.toMutableList()
        val withTotals = t.totals && t.money.isNotEmpty() && rows.isNotEmpty()
        if (withTotals) rows += t.columns.indices.map { i -> if (i == 0) "Total" else if (i in t.money) Money.plain(t.rows.sumOf { (it[i] as? Long) ?: 0L }) else "" }
        k.table(cols, rows, 8.5f, boldLast = withTotals)
        return k.save(out("Reports", t.title.substringBefore(" —") + "-" + Dates.today()))
    }

    fun cell(t: Table, i: Int, v: Any?): String = when {
        v == null -> ""
        i in t.money && v is Long -> Money.plain(Money.roundToRupee(v))   // reports read in whole rupees; Excel export keeps exact values
        i in t.qty && v is Long -> Qty.fmt(v)
        else -> v.toString()
    }

    // ---------- labels ----------

    data class Label(val title: String, val line2: String, val code: String, val isQr: Boolean = false, val big: String = "")

    /** One label per page at the configured label size, ready for a thermal label printer. */
    fun labels(items: List<Label>, name: String): File {
        val w = mm(Settings.get(Settings.LABEL_W, "50").toFloatOrNull() ?: 50f)
        val h = mm(Settings.get(Settings.LABEL_H, "25").toFloatOrNull() ?: 25f)
        val k = PdfKit(PDRectangle(w, h), margin = mm(1.5f))
        items.forEachIndexed { idx, l ->
            if (idx > 0) k.newPage()
            val tw = k.width
            k.y -= 7.5f
            k.text(k.fit(l.title, tw - (if (l.big.isNotBlank()) k.textWidth(l.big, 8f, k.bold) + 4 else 0f), 7f, k.bold), 0f, k.y, 7f, k.bold)
            if (l.big.isNotBlank()) k.text(l.big, tw, k.y, 8f, k.bold, Align.RIGHT)
            if (l.isQr) {
                val s = minOf(h - mm(5f), tw / 2.2f)
                k.image(Codes.qr(l.code, 200), 0f, mm(1.5f), s, s)
                k.text(k.fit(l.line2, tw - s - 3, 6.5f), s + 3, mm(1.5f) + s / 2, 6.5f)
                k.text(k.fit(l.code, tw - s - 3, 6f), s + 3, mm(1.5f) + s / 2 - 9, 6f)
            } else {
                k.text(k.fit(l.line2, tw, 6f), 0f, k.y - 8, 6f)
                val bh = h - mm(12f)
                k.image(Codes.barcode(l.code, 600, 120), 0f, mm(4f), tw, bh)
                k.text(l.code, tw / 2, mm(1.6f), 6f, align = Align.CENTER)
            }
        }
        return k.save(out("Labels", name))
    }

    fun partLabels(partId: Long, copies: Int): File {
        val p = Inventory.get(partId)
        val code = p.barcodes.firstOrNull() ?: Inventory.internalCode(p.id)
        val l = Label(p.name, listOf(p.partNo, p.brand, p.location).filter { it.isNotBlank() }.joinToString(" · "), code, big = "Rs " + Money.plain(p.price))
        return labels(List(copies.coerceIn(1, 500)) { l }, "part-${p.id}")
    }

    fun binLabel(location: String): File = labels(listOf(Label("Shelf $location", "Scan to count this shelf", "BIN:$location")), "bin-$location")

    fun keyTag(jobId: Long): File {
        val j = Jobs.get(jobId)
        return labels(listOf(Label(j.vehicleLabel, j.customerName, j.jobNo, isQr = true)), "key-${j.jobNo}")
    }

    fun serviceSticker(vehicleId: Long): File {
        val v = Vehicles.get(vehicleId)
        val next = listOfNotNull(v.nextServiceKm?.let { "%,d km".format(it) }, v.nextServiceDate?.let { Dates.show(it) }).joinToString(" or ")
        return labels(listOf(Label("Next service: ${next.ifBlank { "-" }}", Settings.get(Settings.PHONE).ifBlank { Settings.get(Settings.BUSINESS) }, v.regNo, isQr = true)), "sticker-${v.regNo}")
    }
}
