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

class ChartLoopTest {
    @Test fun loopsAreRefusedAndRepaired() {
        val dir = kotlin.io.path.createTempDirectory("wschart").toFile()
        System.setProperty("workshop.dataDir", dir.path)
        pk.workshop.security.Vault.setup("admin", "secret123")
        val cashGroup = Db.scalarStr("SELECT id FROM accounts WHERE code='1100'")!!.toLong()
        val assets = Db.scalarStr("SELECT id FROM accounts WHERE code='1000'")!!.toLong()
        val cash = Accounts.sys("CASH")
        // A new group under Cash and bank; Cash and bank can't then go under it
        val g = Accounts.save(null, "0001", "Cash in hand", "ASSET", cashGroup, true)
        assertFailsWith<pk.workshop.db.AppError> { Accounts.save(cashGroup, "1100", "Cash and bank", "ASSET", g, true) }
        assertFailsWith<pk.workshop.db.AppError> { Accounts.save(cash, "1110", "Cash in hand", "ASSET", cash, false) }
        assertFailsWith<pk.workshop.db.AppError> { Accounts.save(cash, "1110", "Cash in hand", "ASSET", cashGroup, true) }
        // Moving the cash account into the new group is fine and it still counts as cash and bank
        Accounts.save(cash, "1110", "Cash in hand", "ASSET", g, false)
        assertTrue(Accounts.cashAndBank().any { it.id == cash })
        // A loop made by an older version is repaired when the records are opened
        Db.exec("UPDATE accounts SET parent_id=? WHERE id=?", g, cashGroup)
        Accounts.repairTree()
        val all = Accounts.all()
        for (a in all) assertTrue(Accounts.ancestors(a, all).size < all.size)
        assertTrue(Accounts.get(cashGroup).parentId == assets || Accounts.get(g).parentId == assets, "the loop is broken at the top of Assets")
        Ledger.balance(cashGroup)   // balances still work (no endless walk)
        Db.close()
    }
}
