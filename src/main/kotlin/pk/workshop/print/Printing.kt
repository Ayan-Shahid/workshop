package pk.workshop.print

import org.apache.pdfbox.Loader
import org.apache.pdfbox.printing.PDFPageable
import pk.workshop.core.Dates
import pk.workshop.core.Money
import pk.workshop.core.Qty
import pk.workshop.data.*
import pk.workshop.db.fail
import java.awt.Desktop
import java.awt.print.PrinterJob
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.URLEncoder
import javax.print.DocFlavor
import javax.print.PrintService
import javax.print.PrintServiceLookup
import javax.print.SimpleDoc

/** ESC/POS receipts and cash-drawer commands for 80 mm thermal printers. */
object Escpos {
    private val ESC = 0x1B; private val GS = 0x1D

    /** Receipt text lines; a "**" prefix marks bold lines. [w] is characters per line (48 for 80 mm font A). */
    fun receiptLines(invoiceId: Long, w: Int = 48): List<String> {
        val inv = Billing.get(invoiceId)
        val ls = Billing.lines(invoiceId)
        val out = ArrayList<String>()
        fun center(s: String) = s.take(w).let { " ".repeat(((w - it.length) / 2).coerceAtLeast(0)) + it }
        fun lr(l: String, r: String) = l.take(w - r.length - 1).padEnd(w - r.length) + r
        val dash = "-".repeat(w)
        out += "**" + center(Settings.get(Settings.BUSINESS))
        Settings.get(Settings.ADDRESS).takeIf { it.isNotBlank() }?.let { a -> a.chunked(w).forEach { out += center(it) } }
        Settings.get(Settings.PHONE).takeIf { it.isNotBlank() }?.let { out += center("Ph: $it") }
        listOfNotNull(Settings.get(Settings.NTN).takeIf { it.isNotBlank() }?.let { "NTN $it" }, Settings.get(Settings.STRN).takeIf { it.isNotBlank() }?.let { "STRN $it" })
            .joinToString("  ").takeIf { it.isNotBlank() }?.let { out += center(it) }
        out += dash
        out += "**" + center(if (inv.kind == "Credit note") "CREDIT NOTE" else if (Settings.bool(Settings.TAX_REGISTERED)) "SALES TAX INVOICE" else "INVOICE")
        out += lr("No: ${inv.no}", Dates.show(inv.date))
        if (inv.customer.isNotBlank()) out += "Customer: ${inv.customer}".take(w)
        if (inv.vehicle.isNotBlank()) out += "Vehicle: ${inv.vehicle}".take(w)
        if (inv.jobNo.isNotBlank()) out += "Job: ${inv.jobNo}"
        out += dash
        for (l in ls) {
            out += l.description.take(w)
            out += lr("  ${Qty.fmt(kotlin.math.abs(l.qty))} x ${Money.plain(l.rate)}" + if (l.discount != 0L) " -${Money.plain(kotlin.math.abs(l.discount))}" else "", Money.plain(kotlin.math.abs(l.net)))
        }
        out += dash
        if (inv.goodsNet != 0L) out += lr("Parts", Money.plain(kotlin.math.abs(inv.goodsNet)))
        if (inv.goodsTax != 0L) out += lr("Sales tax (FBR)", Money.plain(kotlin.math.abs(inv.goodsTax)))
        if (inv.servicesNet != 0L) out += lr("Labour & services", Money.plain(kotlin.math.abs(inv.servicesNet)))
        if (inv.servicesTax != 0L) out += lr("Services tax", Money.plain(kotlin.math.abs(inv.servicesTax)))
        if (inv.rounding != 0L) out += lr("Rounding", Money.plain(inv.rounding, true))
        out += "**" + lr("TOTAL", Money.rs(kotlin.math.abs(inv.total)))
        if (inv.kind != "Credit note") {
            out += lr("Paid", Money.plain(inv.paid))
            out += "**" + lr("Balance", Money.plain(inv.due))
        }
        if (inv.fbrNo.isNotBlank() && inv.fbrNo != "PENDING") out += "FBR No: ${inv.fbrNo}".take(w)
        out += dash
        Settings.get(Settings.FOOTER).takeIf { it.isNotBlank() }?.let { out += center(it) }
        return out
    }

    fun receiptBytes(invoiceId: Long): ByteArray {
        val b = ByteArrayOutputStream()
        fun cmd(vararg x: Int) = x.forEach { b.write(it) }
        cmd(ESC, '@'.code)                // init
        cmd(ESC, 't'.code, 16)            // code page WPC1252
        for (l in receiptLines(invoiceId)) {
            val boldLine = l.startsWith("**")
            cmd(ESC, 'E'.code, if (boldLine) 1 else 0)
            b.write(l.removePrefix("**").toByteArray(Charsets.ISO_8859_1)); b.write('\n'.code)
        }
        cmd(ESC, 'E'.code, 0)
        // QR code with the invoice / FBR number (GS ( k)
        val inv = Billing.get(invoiceId)
        val data = (if (inv.fbrNo.isNotBlank() && inv.fbrNo != "PENDING") inv.fbrNo else inv.no).toByteArray()
        cmd(ESC, 'a'.code, 1)
        cmd(GS, '('.code, 'k'.code, 4, 0, 49, 65, 50, 0)       // model 2
        cmd(GS, '('.code, 'k'.code, 3, 0, 49, 67, 6)           // module size
        cmd(GS, '('.code, 'k'.code, 3, 0, 49, 69, 49)          // error correction M
        val len = data.size + 3
        cmd(GS, '('.code, 'k'.code, len % 256, len / 256, 49, 80, 48); b.write(data)
        cmd(GS, '('.code, 'k'.code, 3, 0, 49, 81, 48)          // print
        cmd(ESC, 'a'.code, 0)
        b.write("\n\n\n\n".toByteArray())
        cmd(GS, 'V'.code, 66, 0)                               // partial cut
        return b.toByteArray()
    }

    val drawerKick = byteArrayOf(0x1B, 'p'.code.toByte(), 0, 25, 250.toByte())
}

object Printers {
    fun names(): List<String> = PrintServiceLookup.lookupPrintServices(null, null).map { it.name }

    private fun service(name: String?): PrintService {
        if (name.isNullOrBlank()) return PrintServiceLookup.lookupDefaultPrintService() ?: fail("No printer is set up on this PC.")
        return PrintServiceLookup.lookupPrintServices(null, null).firstOrNull { it.name == name } ?: fail("Printer \"$name\" was not found.")
    }

    fun raw(printer: String?, bytes: ByteArray) {
        val job = service(printer).createPrintJob()
        job.print(SimpleDoc(bytes, DocFlavor.BYTE_ARRAY.AUTOSENSE, null), null)
    }

    fun pdf(file: File, printer: String?) {
        Loader.loadPDF(file).use { doc ->
            val job = PrinterJob.getPrinterJob()
            job.setPageable(PDFPageable(doc))
            job.printService = service(printer)
            job.jobName = file.nameWithoutExtension
            job.print()
        }
    }

    /** Opens a file in its default program. Never throws: if nothing can open it, says where it was saved. */
    fun open(file: File) {
        if (!tryOpen(file)) pk.workshop.ui.App.toast("Saved to ${file.path} (no program found to open it)")
    }

    fun openFolder(file: File) { tryOpen(if (file.isDirectory) file else file.parentFile) }

    private fun tryOpen(f: File): Boolean {
        runCatching { if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) { Desktop.getDesktop().open(f); return true } }
        val win = System.getProperty("os.name").lowercase().contains("win")
        val cmd = if (win) listOf("cmd", "/c", "start", "", f.absolutePath) else listOf("xdg-open", f.absolutePath)
        return runCatching { ProcessBuilder(cmd).start().let { it.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && it.exitValue() == 0 } }.getOrDefault(false)
    }

    /** Prints the thermal receipt directly (ESC/POS) or through the driver, per Settings. */
    fun receipt(invoiceId: Long) {
        val p = Settings.get(Settings.RECEIPT_PRINTER)
        if (Settings.get("printer.receipt_mode", "raw") == "raw") raw(p, Escpos.receiptBytes(invoiceId))
        else pdf(Documents.receiptPdf(invoiceId), p)
    }

    fun a4(file: File) = pdf(file, Settings.get(Settings.A4_PRINTER).ifBlank { null })
    fun label(file: File) = pdf(file, Settings.get(Settings.LABEL_PRINTER).ifBlank { null })

    fun openDrawer() = raw(Settings.get(Settings.RECEIPT_PRINTER), Escpos.drawerKick)

    init { CashDrawer.opener = { openDrawer() } }
}

/** WhatsApp click-to-chat: opens the customer's chat with the message typed; the PDF is saved for attaching. */
object WhatsApp {
    fun intlNumber(phone: String): String? {
        val d = phone.filter { it.isDigit() }
        return when {
            d.startsWith("92") && d.length == 12 -> d
            d.startsWith("0") && d.length == 11 -> "92" + d.drop(1)
            d.length == 10 && d.startsWith("3") -> "92$d"
            else -> null
        }
    }

    fun send(phone: String, text: String, refType: String? = null, refId: Long? = null) {
        val n = intlNumber(phone) ?: fail("\"$phone\" is not a Pakistani mobile number.")
        pk.workshop.db.Db.insert("INSERT INTO messages(date,channel,phone,text,ref_type,ref_id,status) VALUES(?,?,?,?,?,?,?)",
            Dates.now(), "WhatsApp", n, text, refType, refId, "Opened")
        Desktop.getDesktop().browse(URI("https://wa.me/$n?text=" + URLEncoder.encode(text, "UTF-8").replace("+", "%20")))
    }

    fun invoiceText(inv: Invoice) = buildString {
        append("Assalam-o-Alaikum ${inv.customer},\n")
        append("Your bill ${inv.no} from ${Settings.get(Settings.BUSINESS)}")
        if (inv.vehicle.isNotBlank()) append(" for ${inv.vehicle}")
        append(" is ${Money.rs(inv.total)}.")
        if (inv.due > 0) append(" Balance due: ${Money.rs(inv.due)}.")
        append(" The PDF is attached. Thank you!")
    }

    fun carReadyText(j: JobCard) = "Assalam-o-Alaikum ${j.customerName}, your car ${j.vehicleLabel} is ready for pickup at ${Settings.get(Settings.BUSINESS)}. Job ${j.jobNo}."
    fun reminderText(v: Vehicle) = "Assalam-o-Alaikum ${v.ownerName}, your ${v.label} is due for service" +
        (v.nextServiceDate?.let { " on ${Dates.show(it)}" } ?: "") + (v.nextServiceKm?.let { " at ${"%,d".format(it)} km" } ?: "") +
        ". Book a slot: ${Settings.get(Settings.PHONE)} — ${Settings.get(Settings.BUSINESS)}"
    fun paymentReminderText(name: String, due: Long) = "Assalam-o-Alaikum $name, a balance of ${Money.rs(due)} is due at ${Settings.get(Settings.BUSINESS)}. Kindly arrange payment. Thank you."
    fun bookingText(b: Booking) = "Assalam-o-Alaikum ${b.name}, your booking at ${Settings.get(Settings.BUSINESS)} is confirmed for ${Dates.show(b.whenAt)}" +
        (if (b.pickup) ". We will pick up the car from ${b.pickupAddress}" else "") + ". Booking #${b.id}."
}
