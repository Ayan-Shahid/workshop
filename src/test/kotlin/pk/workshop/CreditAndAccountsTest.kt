package pk.workshop

import pk.workshop.core.Dates
import pk.workshop.data.*
import pk.workshop.db.Db
import kotlin.test.*

/** Customer credit after editing a bill, and payments into a bank account of the user's choosing. */
class CreditAndAccountsTest {
    @Test fun creditAndBankAccounts() {
        val dir = kotlin.io.path.createTempDirectory("wscredit").toFile()
        System.setProperty("workshop.dataDir", dir.path)
        System.setProperty("workshop.demo", "1")
        try { assertTrue(Demo.maybeStart()) } finally { System.clearProperty("workshop.demo") }

        val cid = Customers.save(Customer(name = "Credit Test", phone = "0333-1231231"))
        val p = Inventory.list().first { it.qty > 10_000 }
        fun sale(qty: Long) = Billing.post("Counter", Dates.today(), cid, null, null, listOf(InvLine("PART", p.id, p.name, qty, p.price, 0)), 0, "")

        // A credit sale, partly paid, then lowered: the extra becomes credit with us
        val first = sale(3000)
        Billing.receive(cid, Billing.get(first).total * 2 / 3, "Cash", "", Dates.today(), first)
        val paid = Billing.get(first).paid
        Edits.editCounterInvoice(first, Billing.get(first).date, cid, listOf(InvLine("PART", p.id, p.name, 1000, p.price, 0)), 0, "")
        val credit = -Customers.balance(cid)
        assertTrue(credit > 0, "customer has credit with us after the bill was lowered")
        assertEquals(paid - Billing.get(first).total, credit)

        // The next bill uses that credit up by itself
        val second = sale(1000)
        assertEquals(minOf(credit, Billing.get(second).total), Billing.get(second).paid, "new bill is paid from the credit")
        assertEquals(Billing.get(second).total - minOf(credit, Billing.get(second).total), Customers.balance(cid).coerceAtLeast(0))

        // A second bank account appears in the list and payments can go into it
        val group = Db.scalarStr("SELECT id FROM accounts WHERE code='1100'")!!.toLong()
        val meezan = Accounts.save(null, "1125", "Meezan Bank", "ASSET", group, false)
        assertTrue(Accounts.cashAndBank().any { it.id == meezan })
        val before = Ledger.balance(meezan)
        val due = Billing.get(second).due
        if (due > 0) {
            Billing.receive(cid, due, "Bank transfer", "TX1", Dates.today(), second, accountId = meezan)
            assertEquals(before + due, Ledger.balance(meezan), "money went into the chosen bank")
            assertEquals(meezan, Accounts.forMethod("Bank transfer"), "the choice is remembered for next time")
        }
        assertFailsWith<pk.workshop.db.AppError> { Billing.receive(cid, 100, "Cash", "", Dates.today(), accountId = Accounts.sys("AR")) }
        Db.close()
    }
}
