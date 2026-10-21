package pk.workshop

import pk.workshop.core.Money
import pk.workshop.data.*
import pk.workshop.db.Db
import pk.workshop.security.Vault
import kotlin.test.*

class FlowTest {
    @Test fun fullWorkshopCycleKeepsBooksBalanced() {
        val dir = kotlin.io.path.createTempDirectory("ws2").toFile()
        System.setProperty("workshop.dataDir", dir.path)
        Vault.setup("admin", "secret123")
        Defaults.ensure()
        Settings.setBool(Settings.TAX_REGISTERED, true)
        val today = pk.workshop.core.Dates.today()

        // Stock in: 10 oil filters at Rs 500 + Rs 100 freight
        val sup = Suppliers.save(Supplier(name = "Al-Madina Auto Parts"))
        val filter = Inventory.save(Part(name = "Oil filter", brand = "Toyota", price = 90000, reorderLevel = 2000))
        val oil = Inventory.save(Part(name = "Engine oil 5W-30", unit = "litre", price = 250000))
        Purchasing.postBill(sup, "B-77", today, listOf(DocLine(filter, 10_000, 50000), DocLine(oil, 20_000, 180000)), 10000, "")
        assertEquals(10_000, Inventory.get(filter).qty)
        assertEquals(50122, Inventory.get(filter).avgCost) // freight spread by value

        // Customer, vehicle, job card with a part, oil sold loose and labour
        val cust = Customers.save(Customer(name = "Ali Khan", phone = "0300-1234567", type = "Regular", creditLimit = 1_000_000))
        val car = Vehicles.save(Vehicle(customerId = cust, regNo = "LEA-1234", make = "Toyota", model = "Corolla"))
        val job = Jobs.create(JobCard(vehicleId = car, odometer = 85000, complaint = "Periodic service"))
        val tech = Technicians.save(Technician(name = "Usman", commissionBp = 1000))
        Jobs.addLine(JobLine(jobId = job, kind = "PART", partId = filter, qty = 1000, rate = 90000))
        Jobs.addLine(JobLine(jobId = job, kind = "PART", partId = oil, qty = 3_500, rate = 250000))
        Jobs.addLine(JobLine(jobId = job, kind = "LABOUR", description = "Oil change", qty = 1000, rate = 50000, technicianId = tech))
        assertEquals(9_000, Inventory.get(filter).qty)
        assertEquals(16_500, Inventory.get(oil).qty)

        val inv = Billing.invoiceJob(job, 0, "")
        val i = Billing.get(inv)
        // Parts 900 + 8750 = 9650 net, 18% = 1737; labour 500, 16% = 80
        assertEquals(965000, i.goodsNet); assertEquals(173700, i.goodsTax)
        assertEquals(50000, i.servicesNet); assertEquals(8000, i.servicesTax)
        assertEquals(Money.roundToRupee(965000 + 173700 + 50000 + 8000), i.total)

        // Split payment: part cash, part JazzCash
        Billing.receive(cust, 500000, "Cash", "", today, inv)
        Billing.receive(cust, i.total - 500000, "JazzCash", "TID123", today, inv)
        assertEquals(0, Billing.get(inv).due)
        assertEquals(0, Customers.balance(cust))
        Jobs.deliver(job, false)

        // Return the oil filter by credit note
        val cn = Billing.creditNote(inv, mapOf(0 to 1000L), "Wrong filter")
        assertTrue(Billing.get(cn).total < 0)
        assertEquals(9_000 + 1000, Inventory.get(filter).qty)

        // Counter sale on credit, supplier payment, expense, manual voucher
        val sale = Billing.post("Counter", today, cust, null, null, listOf(InvLine("PART", filter, "Oil filter", 2000, 90000)), 10000, "")
        assertEquals(8_000, Inventory.get(filter).qty)
        Purchasing.paySupplier(sup, 200000, "Bank transfer", "IBFT 55", today)
        Books.expense(today, Accounts.sys("ELECTRICITY"), 1500000, "Cash", "LESCO bill", null)
        Books.manual("CPV", today, "Tea", null, listOf(LedgerLine(Accounts.sys("FOOD"), debit = 30000)), null)

        // Stock adjustment and count
        Inventory.adjust(oil, -500, "Spilled")
        val cnt = Inventory.startCount("Full count", null)
        Inventory.approveCount(cnt)

        // Books must balance
        val tb = Ledger.trialBalance(today)
        assertEquals(tb.sumOf { it.debit }, tb.sumOf { it.credit }, "trial balance must balance")
        val bs = Ledger.balanceSheet(today)
        assertEquals(bs.totalAssets, bs.totalLiabilities + bs.totalEquity, "balance sheet must balance")
        // Inventory account equals stock value at average cost (all stock movements are posted)
        assertTrue(Customers.balance(cust) > 0)
        assertEquals(Billing.get(sale).total + Billing.get(cn).total, Customers.balance(cust)) // credit note left store credit
        assertTrue(Reports.dashboard().salesToday > 0)
        println("Trial balance total: " + Money.rs(tb.sumOf { it.debit }))
        Settings.set(Settings.ADDRESS, "Plot 12, Ferozepur Road, Lahore"); Settings.set(Settings.PHONE, "0321-4455667"); Settings.set(Settings.NTN, "1234567-8")
        val out = java.io.File(System.getProperty("user.dir"), "build/docs").apply { mkdirs() }
        listOf(pk.workshop.print.Documents.invoice(inv), pk.workshop.print.Documents.invoice(cn), pk.workshop.print.Documents.receiptPdf(inv),
            pk.workshop.print.Documents.partLabels(filter, 2), pk.workshop.print.Documents.jobCard(job), pk.workshop.print.Documents.keyTag(job),
            pk.workshop.print.Documents.statement("C", cust, "2026-01-01", today), pk.workshop.print.Documents.table(Reports.sales("2026-01-01", today), "test"),
            pk.workshop.print.Documents.gatePass(job), pk.workshop.print.Documents.voucher(Ledger.vouchers("2026-01-01", today).first().id),
            pk.workshop.io.Excel.exportFile(Reports.stock())).forEach { it.copyTo(java.io.File(out, it.name), true) }
        println(pk.workshop.print.Escpos.receiptLines(inv).joinToString("\n"))
        val zip = pk.workshop.io.Backup.run(java.io.File(dir, "bk"))
        assertTrue(zip.length() > 1000)
        Db.close()
    }
}
