package pk.workshop.io

import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import pk.workshop.core.Dates
import pk.workshop.core.Money
import pk.workshop.core.Qty
import pk.workshop.data.*
import pk.workshop.db.Db
import pk.workshop.db.fail
import pk.workshop.print.Documents
import pk.workshop.security.Vault
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object Excel {
    /** Exports a report table to .xlsx with numbers as real numbers (rupees, not paisa). */
    fun export(t: Table, file: File): File {
        XSSFWorkbook().use { wb ->
            val sh = wb.createSheet(t.title.take(30).replace(Regex("[\\\\/?*\\[\\]:]"), " "))
            val boldFont = wb.createFont().apply { bold = true }
            val head = wb.createCellStyle().apply { setFont(boldFont) }
            val money = wb.createCellStyle().apply { dataFormat = wb.createDataFormat().getFormat("#,##0.00") }
            sh.createRow(0).let { r -> t.columns.forEachIndexed { i, c -> r.createCell(i).apply { setCellValue(c); cellStyle = head } } }
            t.rows.forEachIndexed { ri, row ->
                val r = sh.createRow(ri + 1)
                row.forEachIndexed { i, v ->
                    val c = r.createCell(i)
                    when {
                        v is Long && i in t.money -> { c.setCellValue(v / 100.0); c.cellStyle = money }
                        v is Long && i in t.qty -> c.setCellValue(v / 1000.0)
                        v is Number -> c.setCellValue(v.toDouble())
                        else -> c.setCellValue(v?.toString() ?: "")
                    }
                }
            }
            t.columns.indices.forEach { sh.autoSizeColumn(it) }
            file.parentFile?.mkdirs()
            file.outputStream().use { wb.write(it) }
        }
        return file
    }

    fun exportFile(t: Table) = export(t, File(Documents.outDir, "Exports/${t.title.substringBefore(" —").replace(Regex("[^A-Za-z0-9 _-]"), "")}-${Dates.today()}.xlsx"))

    private fun rows(file: File): List<Map<String, String>> = WorkbookFactory.create(file).use { wb ->
        val sh = wb.getSheetAt(0)
        val header = sh.getRow(sh.firstRowNum) ?: fail("The sheet is empty.")
        val names = (0 until header.lastCellNum).map { header.getCell(it)?.toString()?.trim()?.lowercase() ?: "" }
        (sh.firstRowNum + 1..sh.lastRowNum).mapNotNull { sh.getRow(it) }.map { r ->
            names.mapIndexed { i, n ->
                val c = r.getCell(i)
                n to when (c?.cellType) {
                    CellType.NUMERIC -> c.numericCellValue.let { d -> if (d == Math.floor(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString() }
                    null -> ""
                    else -> c.toString().trim()
                }
            }.toMap()
        }.filter { m -> m.values.any { it.isNotBlank() } }
    }

    /**
     * Imports parts. Columns (first row, any order): name, part no, brand, category, unit, location, price, cost, qty, reorder level, barcode.
     * Stock and cost become opening stock. Returns count imported.
     */
    fun importParts(file: File): Int = Db.tx {
        var n = 0
        for (r in rows(file)) {
            val name = r["name"].orEmpty(); if (name.isBlank()) continue
            val id = Inventory.save(Part(name = name, partNo = r["part no"].orEmpty(), brand = r["brand"].orEmpty(), category = r["category"].orEmpty(),
                unit = r["unit"].orEmpty().ifBlank { "pc" }, location = r["location"].orEmpty(), price = Money.parse(r["price"].orEmpty()) ?: 0,
                reorderLevel = Qty.parse(r["reorder level"].orEmpty()) ?: 0, barcodes = listOfNotNull(r["barcode"]?.takeIf { it.isNotBlank() })))
            val qty = Qty.parse(r["qty"].orEmpty()) ?: 0
            if (qty > 0) Inventory.openingStock(id, qty, Money.parse(r["cost"].orEmpty()) ?: 0)
            n++
        }
        n
    }

    /** Imports customers with optional vehicle and opening balance. Columns: name, phone, cnic, address, city, type, reg no, make, model, year, balance. */
    fun importCustomers(file: File): Int = Db.tx {
        var n = 0
        for (r in rows(file)) {
            val name = r["name"].orEmpty(); if (name.isBlank()) continue
            val id = Customers.save(Customer(name = name, phone = r["phone"].orEmpty(), cnic = r["cnic"].orEmpty(), address = r["address"].orEmpty(),
                city = r["city"].orEmpty(), type = r["type"].orEmpty().ifBlank { "Regular" }))
            val reg = r["reg no"].orEmpty()
            if (reg.isNotBlank()) Vehicles.save(Vehicle(customerId = id, regNo = reg, make = r["make"].orEmpty(), model = r["model"].orEmpty(), year = r["year"].orEmpty()))
            val bal = Money.parse(r["balance"].orEmpty()) ?: 0
            if (bal > 0) Customers.openingBalance(id, bal)
            n++
        }
        n
    }

    fun template(kind: String): File {
        val cols = if (kind == "parts") listOf("Name", "Part no", "Brand", "Category", "Unit", "Location", "Price", "Cost", "Qty", "Reorder level", "Barcode")
                   else listOf("Name", "Phone", "CNIC", "Address", "City", "Type", "Reg no", "Make", "Model", "Year", "Balance")
        return export(Table("Import $kind", cols, emptyList()), File(Documents.outDir, "Exports/import-$kind-template.xlsx"))
    }
}

/**
 * Backups are zip files holding the encrypted database and the key vault, so they are useless without the admin password
 * or recovery code. Attachments (photos, scanner reports) are included.
 */
object Backup {
    fun defaultDir(): File = File(Settings.get(Settings.BACKUP_DIR).ifBlank { File(Documents.outDir, "Backups").path }).apply { mkdirs() }

    fun run(target: File = defaultDir()): File {
        target.mkdirs()
        val f = File(target, "workshop-backup-${Dates.now().replace(":", "-")}.zip")
        synchronized(Db.lock) {
            Db.checkpoint()
            ZipOutputStream(f.outputStream()).use { z ->
                fun add(file: File, name: String) { if (file.exists()) { z.putNextEntry(ZipEntry(name)); file.inputStream().use { it.copyTo(z) }; z.closeEntry() } }
                add(Vault.dbFile, "workshop.db")
                add(Vault.vaultFileForBackup, "vault.properties")
                File(Vault.dataDir, "attachments").walkTopDown().filter { it.isFile }.forEach { add(it, "attachments/" + it.relativeTo(File(Vault.dataDir, "attachments")).path.replace('\\', '/')) }
            }
        }
        Settings.set(Settings.LAST_BACKUP, Dates.now())
        // Keep the 30 newest backups in this folder
        target.listFiles { x -> x.name.startsWith("workshop-backup-") && x.name.endsWith(".zip") }?.sortedByDescending { it.name }?.drop(30)?.forEach { it.delete() }
        return f
    }

    /** Daily automatic backup: runs if none was made today. */
    fun autoIfDue(): File? {
        val last = Settings.get(Settings.LAST_BACKUP)
        return if (last.take(10) != Dates.today()) try { run() } catch (e: Exception) { null } else null
    }

    /** Restores a backup zip over the current data. The app must restart and log in with the password that was valid when the backup was made. */
    fun restore(zip: File) {
        val entries = HashSet<String>()
        ZipInputStream(zip.inputStream()).use { z -> var e = z.nextEntry; while (e != null) { entries += e.name; e = z.nextEntry } }
        if ("workshop.db" !in entries || "vault.properties" !in entries) fail("This is not a Workshop Manager backup.")
        Db.close()
        val dir = Vault.dataDir
        File(dir, "workshop.db-wal").delete(); File(dir, "workshop.db-shm").delete()
        ZipInputStream(zip.inputStream()).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val dest = File(dir, e.name).canonicalFile
                if (!dest.path.startsWith(dir.canonicalPath)) fail("Unsafe path in backup.")
                dest.parentFile.mkdirs()
                dest.outputStream().use { z.copyTo(it) }
                e = z.nextEntry
            }
        }
    }
}
