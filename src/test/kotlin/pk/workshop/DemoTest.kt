package pk.workshop

import pk.workshop.core.Dates
import pk.workshop.core.Money
import pk.workshop.data.*
import pk.workshop.db.Db
import kotlin.test.*

/** Generates the full sample data set and checks that it is consistent and that every flow has something to show. */
class DemoTest {
    @Test fun sampleDataCoversEveryFlowAndBalances() {
        val dir = kotlin.io.path.createTempDirectory("wsdemo").toFile()
        System.setProperty("workshop.dataDir", dir.path)
        System.setProperty("workshop.demo", "1")
        try { assertTrue(Demo.maybeStart()) } finally { System.clearProperty("workshop.demo") }
        assertNull(Dates.clock, "clock override must be cleared after seeding")

        val today = Dates.today()
        val from = "2000-01-01"
        fun n(sql: String, vararg a: Any?) = Db.scalarLong(sql, *a)

        // Books
        val tb = Ledger.trialBalance(today)
        assertEquals(tb.sumOf { it.debit }, tb.sumOf { it.credit }, "trial balance")
        val bs = Ledger.balanceSheet(today)
        assertEquals(bs.totalAssets, bs.totalLiabilities + bs.totalEquity, "balance sheet")
        assertEquals(0, n("SELECT count(*) FROM (SELECT voucher_id FROM voucher_lines GROUP BY voucher_id HAVING sum(debit)<>sum(credit))"))
        // Stock value in the ledger equals the item-level valuation
        // (parts issued to cars still in the workshop have left the shelf but are expensed only when the job is billed)
        val invAcct = Ledger.balance(Accounts.sys("INVENTORY"))
        val wip = n("""SELECT coalesce(sum(l.cost),0) FROM job_lines l JOIN job_cards j ON j.id=l.job_id
            WHERE l.issued=1 AND j.invoice_id IS NULL AND j.status<>'Cancelled'""")
        println("Inventory: ledger ${Money.rs(invAcct)}, shelf ${Money.rs(Inventory.stockValue())}, in open jobs ${Money.rs(wip)}")
        assertTrue(kotlin.math.abs(invAcct - Inventory.stockValue() - wip) < 100_00, "inventory ledger does not match shelf + open jobs")
        assertEquals(0, n("SELECT count(*) FROM parts WHERE qty<0"), "no negative stock")

        // Every flow has data
        val checks = linkedMapOf(
            "job cards" to n("SELECT count(*) FROM job_cards"),
            "delivered jobs" to n("SELECT count(*) FROM job_cards WHERE status='Delivered'"),
            "cancelled job" to n("SELECT count(*) FROM job_cards WHERE status='Cancelled'"),
            "comeback job" to n("SELECT count(*) FROM job_cards WHERE comeback_of IS NOT NULL"),
            "open statuses" to n("SELECT count(DISTINCT status) FROM job_cards WHERE status NOT IN ('Delivered','Cancelled')"),
            "estimates" to n("SELECT count(*) FROM estimates"),
            "attachments" to n("SELECT count(*) FROM attachments"),
            "timer logs" to n("SELECT count(*) FROM job_time"),
            "running timer" to n("SELECT count(*) FROM job_time WHERE stopped IS NULL"),
            "service invoices" to n("SELECT count(*) FROM invoices WHERE kind='Service'"),
            "counter sales" to n("SELECT count(*) FROM invoices WHERE kind='Counter'"),
            "credit notes" to n("SELECT count(*) FROM invoices WHERE kind='Credit note'"),
            "insurer invoices" to n("SELECT count(*) FROM invoices WHERE insurer_share>0"),
            "discounted invoices" to n("SELECT count(*) FROM invoices WHERE discount>0"),
            "sublet lines" to n("SELECT count(*) FROM invoice_lines WHERE kind='SUBLET'"),
            "pay methods" to n("SELECT count(DISTINCT method) FROM payments WHERE direction='IN'"),
            "cheques in hand" to n("SELECT count(*) FROM payments WHERE cheque_status='In hand'"),
            "cheques cleared" to n("SELECT count(*) FROM payments WHERE cheque_status='Cleared'"),
            "cheques bounced" to n("SELECT count(*) FROM payments WHERE cheque_status='Bounced'"),
            "tax withheld" to n("SELECT count(*) FROM payments WHERE wht>0"),
            "loyalty redemptions" to n("SELECT count(*) FROM payments WHERE method='Loyalty points'"),
            "FBR numbers" to n("SELECT count(*) FROM invoices WHERE fbr_no<>'' AND fbr_no<>'PENDING'"),
            "FBR pending" to n("SELECT count(*) FROM invoices WHERE fbr_no='PENDING'"),
            "purchase bills" to n("SELECT count(*) FROM purchases WHERE kind='Bill'"),
            "purchase returns" to n("SELECT count(*) FROM purchases WHERE kind<>'Bill'"),
            "supplier payments" to n("SELECT count(*) FROM payments WHERE direction='OUT'"),
            "POs open" to n("SELECT count(*) FROM purchase_orders WHERE status='Open'"),
            "POs received" to n("SELECT count(*) FROM purchase_orders WHERE status='Received'"),
            "POs cancelled" to n("SELECT count(*) FROM purchase_orders WHERE status='Cancelled'"),
            "warranty open" to n("SELECT count(*) FROM warranty_claims WHERE status='Sent'"),
            "warranty closed" to n("SELECT count(*) FROM warranty_claims WHERE status<>'Sent'"),
            "stock counts approved" to n("SELECT count(*) FROM stock_counts WHERE status='Approved'"),
            "stock count open" to n("SELECT count(*) FROM stock_counts WHERE status='Open'"),
            "low stock parts" to Inventory.list(onlyLow = true).size.toLong(),
            "out of stock" to n("SELECT count(*) FROM parts WHERE qty=0 AND active=1"),
            "expenses" to n("SELECT count(*) FROM vouchers WHERE vtype='EXP'"),
            "manual vouchers" to n("SELECT count(*) FROM vouchers WHERE vtype IN ('CPV','CRV','BPV','BRV','JV')"),
            "reversed voucher" to n("SELECT count(*) FROM vouchers WHERE reversed=1"),
            "cash closings" to n("SELECT count(*) FROM cash_closings"),
            "cash short" to n("SELECT count(*) FROM cash_closings WHERE difference<>0"),
            "bank cleared" to n("SELECT count(*) FROM bank_recon"),
            "locked months" to Ledger.locks().size.toLong(),
            "payroll" to n("SELECT count(*) FROM payroll"),
            "advance outstanding" to StaffBook.list().count { StaffBook.advanceBalance(it.id) > 0 }.toLong(),
            "attendance" to n("SELECT count(*) FROM attendance"),
            "depreciation" to n("SELECT count(*) FROM vouchers WHERE vtype='DEP'"),
            "bookings upcoming" to n("SELECT count(*) FROM bookings WHERE status='Booked'"),
            "bookings arrived" to n("SELECT count(*) FROM bookings WHERE status='Arrived'"),
            "bookings cancelled" to n("SELECT count(*) FROM bookings WHERE status='Cancelled'"),
            "service reminders" to Vehicles.dueForService().size.toLong(),
            "customers owing" to Customers.list().count { Customers.balance(it.id) > 0 }.toLong(),
            "customer in credit" to Customers.list().count { Customers.balance(it.id) < 0 }.toLong(),
        )
        checks.forEach { (k, v) -> println("%-22s %d".format(k, v)) }
        val empty = checks.filter { it.value == 0L }.keys
        assertTrue(empty.isEmpty(), "flows with no sample data: $empty")
        assertTrue(Customers.creditWarning(Db.scalarLong("SELECT id FROM customers WHERE name='Faisal Rent a Car'"), 1000) != null, "credit warning")

        // Every report runs
        val start = Dates.plusDays(today, -120)
        listOf(Reports.sales(start, today), Reports.salesByMethod(start, today), Reports.profitByInvoice(start, today), Reports.profitByService(start, today),
            Reports.stock(), Reports.deadStock(), Reports.technicians(start, today), Reports.services(start, today), Reports.customers(start, today),
            Reports.lapsedCustomers(), Reports.vehiclesByMake(), Reports.purchases(start, today), Reports.priceChanges(start, today),
            Reports.tax(start, today), Reports.expenses(start, today), Reports.auditLog(start, today)).forEach {
            assertTrue(it.rows.isNotEmpty(), "report '${it.title}' is empty")
        }
        val d = Reports.dashboard()
        val pl = Ledger.profitAndLoss(Dates.monthStart(today), today)
        println("Dashboard: $d")
        println("Profit this month so far: ${Money.rs(pl.profit)}; year to date: ${Money.rs(Ledger.profitAndLoss(Dates.fiscalStart(today), today).profit)}")
        println("Cash ${Money.rs(Ledger.balance(Accounts.sys("CASH")))}, bank ${Money.rs(Ledger.balance(Accounts.sys("BANK")))}")
        assertTrue(Ledger.balance(Accounts.sys("CASH")) > 0 && Ledger.balance(Accounts.sys("BANK")) > 0, "cash and bank stay positive")
        assertTrue(Billing.ageing().isNotEmpty())
        // Documents for an insurance invoice and a credit note render
        pk.workshop.print.Documents.invoice(Db.scalarLong("SELECT id FROM invoices WHERE insurer_share>0 LIMIT 1"))
        pk.workshop.print.Documents.invoice(Db.scalarLong("SELECT id FROM invoices WHERE kind='Credit note' LIMIT 1"))
        println("Totals: ${n("SELECT count(*) FROM invoices")} invoices, ${n("SELECT count(*) FROM vouchers")} vouchers, from $from")
    }
}
