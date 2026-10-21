package pk.workshop.data

import pk.workshop.core.Dates
import pk.workshop.db.Db

/** A generic report table: column headers, rows of cells, and which columns are money (paisa) or quantities. */
data class Table(val title: String, val columns: List<String>, val rows: List<List<Any?>>, val money: Set<Int> = emptySet(), val qty: Set<Int> = emptySet(),
                 val totals: Boolean = true)

object Reports {
    data class Dashboard(val salesToday: Long, val invoicesToday: Long, val cashInHand: Long, val receivables: Long, val dueToday: Long,
                         val carsIn: Map<String, Long>, val lowStock: Long, val bookingsToday: Long, val salesMonth: Long, val profitMonth: Long,
                         val last14: List<Pair<String, Long>>, val chequesInHand: Long, val servicesDue: Long)

    fun dashboard(): Dashboard {
        val today = Dates.today()
        val salesToday = Db.scalarLong("SELECT coalesce(sum(total),0) FROM invoices WHERE date=?", today)
        val count = Db.scalarLong("SELECT count(*) FROM invoices WHERE date=? AND kind<>'Credit note'", today)
        val cash = Ledger.balance(Accounts.sys("CASH"))
        val ar = Ledger.balance(Accounts.sys("AR"))
        val dueToday = Billing.list("0000-00-00", today, unpaidOnly = true).filter { inv ->
            val terms = inv.customerId?.let { Customers.get(it).termsDays } ?: 0
            Dates.plusDays(inv.date, terms.toLong()) <= today
        }.sumOf { it.due }
        val cars = Db.query("SELECT status, count(*) n FROM job_cards WHERE status NOT IN ('Delivered','Cancelled') GROUP BY status")
            .associate { it.str("status") to it.long("n") }
        val low = Inventory.list().count { it.low }.toLong()
        val bookings = Db.scalarLong("SELECT count(*) FROM bookings WHERE substr(when_at,1,10)=? AND status='Booked'", today)
        val m0 = Dates.monthStart(today)
        val salesMonth = Db.scalarLong("SELECT coalesce(sum(goods_net+services_net),0) FROM invoices WHERE date BETWEEN ? AND ?", m0, today)
        val profit = Ledger.profitAndLoss(m0, today).profit
        val days = (13 downTo 0).map { Dates.plusDays(today, -it.toLong()) }
        val byDay = Db.query("SELECT date, sum(total) t FROM invoices WHERE date>=? GROUP BY date", days.first()).associate { it.str("date") to it.long("t") }
        val cheques = Db.scalarLong("SELECT coalesce(sum(amount),0) FROM payments WHERE method='Cheque' AND cheque_status='In hand'")
        return Dashboard(salesToday, count, cash, ar, dueToday, cars, low, bookings, salesMonth, profit, days.map { it to (byDay[it] ?: 0L) }, cheques,
            Vehicles.dueForService().size.toLong())
    }

    fun sales(from: String, to: String): Table {
        val rows = Db.query("""SELECT date, count(CASE WHEN kind<>'Credit note' THEN 1 END) n, sum(goods_net) g, sum(services_net) s, sum(goods_tax+services_tax) t,
            sum(total) tot FROM invoices WHERE date BETWEEN ? AND ? GROUP BY date ORDER BY date""", from, to)
            .map { listOf(Dates.show(it.str("date")), it.long("n"), it.long("g"), it.long("s"), it.long("t"), it.long("tot")) }
        return Table("Sales by day", listOf("Date", "Bills", "Parts", "Labour & sublet", "Tax", "Total"), rows, setOf(2, 3, 4, 5))
    }

    fun salesByMethod(from: String, to: String): Table {
        val rows = Db.query("SELECT method, count(*) n, sum(amount) a FROM payments WHERE direction='IN' AND date BETWEEN ? AND ? GROUP BY method ORDER BY a DESC", from, to)
            .map { listOf(it.str("method"), it.long("n"), it.long("a")) }
        return Table("Money received by method", listOf("Method", "Payments", "Amount"), rows, setOf(2))
    }

    fun profitByInvoice(from: String, to: String): Table {
        val rows = Db.query("""SELECT i.date, i.inv_no, coalesce(c.name,'') cust, i.goods_net+i.services_net net, i.cost,
            coalesce((SELECT sum(cost) FROM invoice_lines WHERE invoice_id=i.id AND kind='SUBLET'),0) sub
            FROM invoices i LEFT JOIN customers c ON c.id=i.customer_id WHERE i.date BETWEEN ? AND ? ORDER BY i.date, i.id""", from, to).map {
            val profit = it.long("net") - it.long("cost") - it.long("sub")
            listOf(Dates.show(it.str("date")), it.str("inv_no"), it.str("cust"), it.long("net"), it.long("cost") + it.long("sub"), profit)
        }
        return Table("Profit per bill (before tax)", listOf("Date", "Bill", "Customer", "Sales", "Cost", "Gross profit"), rows, setOf(3, 4, 5))
    }

    fun profitByService(from: String, to: String): Table {
        val rows = Db.query("""SELECT l.kind, l.description, count(*) n, sum(l.net) net, sum(l.cost) cost FROM invoice_lines l JOIN invoices i ON i.id=l.invoice_id
            WHERE i.date BETWEEN ? AND ? GROUP BY l.kind, l.description ORDER BY net DESC LIMIT 200""", from, to)
            .map { listOf(it.str("kind").lowercase().replaceFirstChar { c -> c.uppercase() }, it.str("description"), it.long("n"), it.long("net"), it.long("cost"),
                it.long("net") - it.long("cost")) }
        return Table("Sales and profit by item or service", listOf("Type", "Item / service", "Times", "Sales", "Cost", "Profit"), rows, setOf(3, 4, 5))
    }

    fun stock(): Table {
        val since = Dates.plusDays(Dates.today(), -90)
        val rows = Db.query("""SELECT p.part_no, p.name, p.brand, p.location, p.qty, p.avg_cost, p.price,
            coalesce((SELECT -sum(qty) FROM stock_moves m WHERE m.part_id=p.id AND m.qty<0 AND m.kind IN ('SALE','ISSUE') AND m.date>=?),0) sold,
            (SELECT max(date) FROM stock_moves m WHERE m.part_id=p.id AND m.kind IN ('SALE','ISSUE')) last
            FROM parts p WHERE p.active=1 ORDER BY sold DESC""", since).map {
            listOf(it.str("part_no"), it.str("name"), it.str("brand"), it.str("location"), it.long("qty"), it.long("avg_cost"),
                pk.workshop.core.Money.times(it.long("avg_cost"), it.long("qty")), it.long("sold"), Dates.show(it.strOrNull("last")))
        }
        return Table("Stock value and movement (sold = last 90 days)", listOf("Part no", "Name", "Brand", "Shelf", "In stock", "Avg cost", "Value", "Sold 90d", "Last sold"),
            rows, setOf(5, 6), setOf(4, 7))
    }

    fun deadStock(days: Long = 180): Table {
        val since = Dates.plusDays(Dates.today(), -days)
        val rows = Db.query("""SELECT p.name, p.brand, p.location, p.qty, p.avg_cost, (SELECT max(date) FROM stock_moves m WHERE m.part_id=p.id AND m.qty<0) last
            FROM parts p WHERE p.qty>0 AND NOT EXISTS(SELECT 1 FROM stock_moves m WHERE m.part_id=p.id AND m.qty<0 AND m.date>=?) ORDER BY p.qty*p.avg_cost DESC""", since)
            .map { listOf(it.str("name"), it.str("brand"), it.str("location"), it.long("qty"), pk.workshop.core.Money.times(it.long("avg_cost"), it.long("qty")),
                Dates.show(it.strOrNull("last")).ifEmpty { "Never" }) }
        return Table("Dead stock: nothing sold in $days days", listOf("Part", "Brand", "Shelf", "In stock", "Value", "Last sold"), rows, setOf(4), setOf(3))
    }

    fun technicians(from: String, to: String): Table {
        val rows = Db.query("""SELECT t.id, t.name, t.commission_bp,
            (SELECT count(DISTINCT i.job_id) FROM invoice_lines l JOIN invoices i ON i.id=l.invoice_id WHERE l.technician_id=t.id AND i.date BETWEEN ? AND ?) jobs,
            coalesce((SELECT sum(l.net) FROM invoice_lines l JOIN invoices i ON i.id=l.invoice_id WHERE l.technician_id=t.id AND l.kind='LABOUR' AND i.date BETWEEN ? AND ?),0) labour,
            coalesce((SELECT sum((julianday(coalesce(stopped,started))-julianday(started))*24*60) FROM job_time WHERE technician_id=t.id AND substr(started,1,10) BETWEEN ? AND ?),0) mins,
            (SELECT count(*) FROM job_cards j WHERE j.comeback_of IS NOT NULL AND substr(j.date_in,1,10) BETWEEN ? AND ? AND EXISTS(SELECT 1 FROM job_lines l WHERE l.job_id=j.comeback_of AND l.technician_id=t.id)) comebacks
            FROM technicians t ORDER BY labour DESC""", from, to, from, to, from, to, from, to).map {
            listOf(it.str("name"), it.long("jobs"), it.long("labour"), "%.1f".format(it.long("mins") / 60.0), it.long("comebacks"),
                pk.workshop.core.Money.pct(it.long("labour"), it.int("commission_bp")))
        }
        return Table("Technician performance", listOf("Technician", "Jobs", "Labour billed", "Hours logged", "Comebacks", "Commission"), rows, setOf(2, 5))
    }

    fun services(from: String, to: String): Table {
        val rows = Db.query("""SELECT l.description, count(*) n, sum(l.net) net, avg(l.net) avg FROM invoice_lines l JOIN invoices i ON i.id=l.invoice_id
            WHERE l.kind<>'PART' AND i.date BETWEEN ? AND ? GROUP BY l.description ORDER BY n DESC LIMIT 100""", from, to)
            .map { listOf(it.str("description"), it.long("n"), it.long("net"), it.long("avg")) }
        val days = Db.query("""SELECT CASE strftime('%w',date) WHEN '0' THEN 'Sunday' WHEN '1' THEN 'Monday' WHEN '2' THEN 'Tuesday' WHEN '3' THEN 'Wednesday'
            WHEN '4' THEN 'Thursday' WHEN '5' THEN 'Friday' ELSE 'Saturday' END d, count(*) n FROM invoices WHERE date BETWEEN ? AND ? GROUP BY d ORDER BY n DESC""", from, to)
        val busiest = days.firstOrNull()?.let { " — busiest day: ${it.str("d")} (${it.long("n")} bills)" } ?: ""
        return Table("Most-requested services$busiest", listOf("Service", "Times", "Sales", "Average"), rows, setOf(2, 3))
    }

    fun customers(from: String, to: String): Table {
        val rows = Db.query("""SELECT c.name, c.phone, c.ctype, count(i.id) n, coalesce(sum(i.total),0) t, max(i.date) last,
            (SELECT min(date) FROM invoices WHERE customer_id=c.id) first FROM customers c LEFT JOIN invoices i ON i.customer_id=c.id AND i.date BETWEEN ? AND ?
            GROUP BY c.id HAVING n>0 ORDER BY t DESC""", from, to).map {
            listOf(it.str("name"), it.str("phone"), it.str("ctype"), it.long("n"), it.long("t"), if (it.str("first") >= from) "New" else "Returning", Dates.show(it.str("last")))
        }
        return Table("Top customers", listOf("Customer", "Phone", "Type", "Bills", "Spent", "New / returning", "Last visit"), rows, setOf(4))
    }

    /** Customers whose last bill is older than [months] months (cars are usually due a service every 3 months). */
    fun lapsedCustomers(months: Long = 3): Table {
        val cutoff = Dates.plusDays(Dates.today(), -months * 30)
        val rows = Db.query("""SELECT c.name, c.phone, max(i.date) last, count(i.id) n, sum(i.total) t FROM customers c JOIN invoices i ON i.customer_id=c.id
            GROUP BY c.id HAVING last < ? ORDER BY t DESC""", cutoff).map { listOf(it.str("name"), it.str("phone"), Dates.show(it.str("last")), it.long("n"), it.long("t")) }
        return Table("Customers not seen in $months months", listOf("Customer", "Phone", "Last visit", "Bills", "Spent"), rows, setOf(4))
    }

    fun vehiclesByMake(): Table {
        val rows = Db.query("SELECT coalesce(nullif(make,''),'Unknown') m, count(*) n FROM vehicles GROUP BY m ORDER BY n DESC").map { listOf(it.str("m"), it.long("n")) }
        return Table("Vehicles by make", listOf("Make", "Vehicles"), rows)
    }

    fun purchases(from: String, to: String): Table {
        val rows = Db.query("""SELECT s.name, count(p.id) n, sum(p.total) t FROM purchases p JOIN suppliers s ON s.id=p.supplier_id
            WHERE p.date BETWEEN ? AND ? GROUP BY s.id ORDER BY t DESC""", from, to).map { listOf(it.str("name"), it.long("n"), it.long("t")) }
        return Table("Purchases by supplier", listOf("Supplier", "Bills", "Amount"), rows, setOf(2))
    }

    fun priceChanges(from: String, to: String): Table {
        val rows = Db.query("""SELECT p.name, s.name sname, pb.date, l.cost FROM purchase_lines l JOIN purchases pb ON pb.id=l.purchase_id JOIN parts p ON p.id=l.part_id
            JOIN suppliers s ON s.id=pb.supplier_id WHERE pb.date BETWEEN ? AND ? AND pb.kind='Bill' ORDER BY p.name, pb.date""", from, to)
        val out = ArrayList<List<Any?>>()
        rows.groupBy { it.str("name") }.forEach { (name, list) ->
            if (list.size < 2) return@forEach
            val first = list.first().long("cost"); val last = list.last().long("cost")
            if (first != last) out += listOf(name, list.last().str("sname"), first, last, if (first == 0L) "" else "%+.1f%%".format((last - first) * 100.0 / first))
        }
        return Table("Purchase price changes", listOf("Part", "Supplier", "First cost", "Latest cost", "Change"), out, setOf(2, 3), totals = false)
    }

    fun tax(from: String, to: String): Table {
        val rows = Db.query("""SELECT substr(date,1,7) m, sum(goods_net) gn, sum(goods_tax) gt, sum(services_net) sn, sum(services_tax) st FROM invoices
            WHERE date BETWEEN ? AND ? GROUP BY m ORDER BY m""", from, to)
            .map { listOf(it.str("m"), it.long("gn"), it.long("gt"), it.long("sn"), it.long("st")) }
        val g = Tax.rateFor("GOODS", to)?.authority ?: "FBR"; val s = Tax.rateFor("SERVICES", to)?.authority ?: "Province"
        return Table("Sales tax for returns", listOf("Month", "Parts sold (value)", "Sales tax — $g", "Services (value)", "Services tax — $s"), rows, setOf(1, 2, 3, 4))
    }

    fun expenses(from: String, to: String): Table {
        val rows = Db.query("""SELECT a.name, sum(l.debit-l.credit) n FROM voucher_lines l JOIN vouchers v ON v.id=l.voucher_id JOIN accounts a ON a.id=l.account_id
            WHERE a.atype='EXPENSE' AND v.date BETWEEN ? AND ? AND v.vtype<>'CLOSE' GROUP BY a.id HAVING n<>0 ORDER BY n DESC""", from, to)
            .map { listOf(it.str("name"), it.long("n")) }
        return Table("Spending by category", listOf("Category", "Amount"), rows, setOf(1))
    }

    fun auditLog(from: String, to: String): Table {
        val rows = Db.query("SELECT ts, action, entity, entity_id, detail FROM audit_log WHERE substr(ts,1,10) BETWEEN ? AND ? ORDER BY id DESC LIMIT 5000", from, to)
            .map { listOf(Dates.show(it.str("ts")), it.str("action"), it.str("entity"), it.strOrNull("entity_id") ?: "", it.str("detail")) }
        return Table("Change log", listOf("When", "Action", "Record", "ID", "Details"), rows, totals = false)
    }
}
