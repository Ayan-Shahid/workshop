package pk.workshop.db

/**
 * Database schema. Money columns are paisa (INTEGER), quantities are thousandths (INTEGER),
 * tax rates are basis points (1800 = 18%). Dates are ISO text.
 */
object Schema {
    private const val VERSION = 1

    fun migrate() {
        Db.exec("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT)")
        val v = Db.scalarStr("SELECT v FROM meta WHERE k='schema'")?.toIntOrNull() ?: 0
        if (v < 1) {
            Db.tx {
                Db.script(V1)
                SeedAccounts.seed()
                Db.exec("INSERT OR REPLACE INTO meta(k,v) VALUES('schema', ?)", VERSION.toString())
            }
        }
    }

    private val V1 = """
CREATE TABLE settings (k TEXT PRIMARY KEY, v TEXT);
CREATE TABLE audit_log (id INTEGER PRIMARY KEY, ts TEXT NOT NULL, action TEXT NOT NULL, entity TEXT NOT NULL, entity_id INTEGER, detail TEXT);
CREATE TABLE tax_rates (id INTEGER PRIMARY KEY, kind TEXT NOT NULL, authority TEXT NOT NULL, rate_bp INTEGER NOT NULL, from_date TEXT NOT NULL);
CREATE TABLE counters (name TEXT PRIMARY KEY, next INTEGER NOT NULL);

CREATE TABLE customers (id INTEGER PRIMARY KEY, name TEXT NOT NULL, phone TEXT, phone2 TEXT, cnic TEXT, address TEXT, city TEXT,
  ctype TEXT NOT NULL DEFAULT 'Walk-in', ntn TEXT, credit_limit INTEGER NOT NULL DEFAULT 0, terms_days INTEGER NOT NULL DEFAULT 0,
  points INTEGER NOT NULL DEFAULT 0, notes TEXT, active INTEGER NOT NULL DEFAULT 1, created_at TEXT NOT NULL);
CREATE TABLE vehicles (id INTEGER PRIMARY KEY, customer_id INTEGER NOT NULL REFERENCES customers(id), reg_no TEXT NOT NULL, make TEXT, model TEXT,
  variant TEXT, year TEXT, color TEXT, engine_no TEXT, chassis_no TEXT, fuel TEXT, transmission TEXT, odometer INTEGER NOT NULL DEFAULT 0,
  next_service_km INTEGER, next_service_date TEXT, notes TEXT);
CREATE INDEX ix_vehicles_reg ON vehicles(reg_no);

CREATE TABLE suppliers (id INTEGER PRIMARY KEY, name TEXT NOT NULL, phone TEXT, address TEXT, ntn TEXT, notes TEXT, active INTEGER NOT NULL DEFAULT 1);

CREATE TABLE parts (id INTEGER PRIMARY KEY, part_no TEXT, name TEXT NOT NULL, brand TEXT, category TEXT, grade TEXT, unit TEXT NOT NULL DEFAULT 'pc',
  location TEXT, fits TEXT, avg_cost INTEGER NOT NULL DEFAULT 0, price INTEGER NOT NULL DEFAULT 0, qty INTEGER NOT NULL DEFAULT 0,
  reorder_level INTEGER NOT NULL DEFAULT 0, last_cost INTEGER NOT NULL DEFAULT 0, active INTEGER NOT NULL DEFAULT 1, created_at TEXT NOT NULL);
CREATE TABLE part_barcodes (code TEXT PRIMARY KEY, part_id INTEGER NOT NULL REFERENCES parts(id));
CREATE TABLE stock_moves (id INTEGER PRIMARY KEY, part_id INTEGER NOT NULL REFERENCES parts(id), date TEXT NOT NULL, kind TEXT NOT NULL,
  qty INTEGER NOT NULL, unit_cost INTEGER NOT NULL DEFAULT 0, ref_type TEXT, ref_id INTEGER, note TEXT);
CREATE INDEX ix_moves_part ON stock_moves(part_id, date);
CREATE TABLE stock_counts (id INTEGER PRIMARY KEY, date TEXT NOT NULL, note TEXT, status TEXT NOT NULL DEFAULT 'Open');
CREATE TABLE stock_count_lines (id INTEGER PRIMARY KEY, count_id INTEGER NOT NULL REFERENCES stock_counts(id), part_id INTEGER NOT NULL REFERENCES parts(id),
  system_qty INTEGER NOT NULL, counted_qty INTEGER NOT NULL);

CREATE TABLE purchases (id INTEGER PRIMARY KEY, doc_no TEXT NOT NULL, kind TEXT NOT NULL DEFAULT 'Bill', supplier_id INTEGER NOT NULL REFERENCES suppliers(id),
  bill_no TEXT, date TEXT NOT NULL, landed INTEGER NOT NULL DEFAULT 0, total INTEGER NOT NULL DEFAULT 0, notes TEXT, status TEXT NOT NULL DEFAULT 'Posted');
CREATE TABLE purchase_lines (id INTEGER PRIMARY KEY, purchase_id INTEGER NOT NULL REFERENCES purchases(id), part_id INTEGER NOT NULL REFERENCES parts(id),
  qty INTEGER NOT NULL, cost INTEGER NOT NULL);
CREATE TABLE purchase_orders (id INTEGER PRIMARY KEY, po_no TEXT NOT NULL, supplier_id INTEGER NOT NULL REFERENCES suppliers(id), date TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'Open', notes TEXT, bill_id INTEGER);
CREATE TABLE po_lines (id INTEGER PRIMARY KEY, po_id INTEGER NOT NULL REFERENCES purchase_orders(id), part_id INTEGER NOT NULL REFERENCES parts(id),
  qty INTEGER NOT NULL, cost INTEGER NOT NULL);
CREATE TABLE warranty_claims (id INTEGER PRIMARY KEY, date TEXT NOT NULL, part_id INTEGER NOT NULL REFERENCES parts(id), supplier_id INTEGER NOT NULL REFERENCES suppliers(id),
  qty INTEGER NOT NULL, reason TEXT, status TEXT NOT NULL DEFAULT 'Sent', resolved_date TEXT, notes TEXT);

CREATE TABLE technicians (id INTEGER PRIMARY KEY, name TEXT NOT NULL, phone TEXT, commission_bp INTEGER NOT NULL DEFAULT 0, department TEXT, active INTEGER NOT NULL DEFAULT 1);
CREATE TABLE services (id INTEGER PRIMARY KEY, name TEXT NOT NULL, price INTEGER NOT NULL DEFAULT 0, department TEXT, active INTEGER NOT NULL DEFAULT 1);
CREATE TABLE complaints (id INTEGER PRIMARY KEY, text TEXT NOT NULL);

CREATE TABLE bookings (id INTEGER PRIMARY KEY, customer_id INTEGER REFERENCES customers(id), vehicle_id INTEGER REFERENCES vehicles(id), name TEXT, phone TEXT,
  reg_no TEXT, when_at TEXT NOT NULL, service TEXT, pickup INTEGER NOT NULL DEFAULT 0, pickup_address TEXT, driver TEXT, return_at TEXT,
  status TEXT NOT NULL DEFAULT 'Booked', job_id INTEGER, notes TEXT);

CREATE TABLE job_cards (id INTEGER PRIMARY KEY, job_no TEXT NOT NULL, customer_id INTEGER NOT NULL REFERENCES customers(id), vehicle_id INTEGER NOT NULL REFERENCES vehicles(id),
  date_in TEXT NOT NULL, odometer INTEGER NOT NULL DEFAULT 0, fuel TEXT, complaint TEXT, inspection TEXT, items_in_car TEXT, brought_by TEXT, brought_phone TEXT,
  priority TEXT NOT NULL DEFAULT 'Normal', department TEXT, status TEXT NOT NULL DEFAULT 'Received', promised_at TEXT, bay TEXT, diagnosis TEXT,
  approved_by TEXT, approved_at TEXT, qc_note TEXT, delivered_at TEXT, invoice_id INTEGER, comeback_of INTEGER, notes TEXT);
CREATE TABLE job_lines (id INTEGER PRIMARY KEY, job_id INTEGER NOT NULL REFERENCES job_cards(id), kind TEXT NOT NULL, part_id INTEGER REFERENCES parts(id),
  description TEXT NOT NULL, qty INTEGER NOT NULL, rate INTEGER NOT NULL, discount INTEGER NOT NULL DEFAULT 0, technician_id INTEGER REFERENCES technicians(id),
  cost INTEGER NOT NULL DEFAULT 0, vendor TEXT, issued INTEGER NOT NULL DEFAULT 0, customer_supplied INTEGER NOT NULL DEFAULT 0);
CREATE TABLE job_time (id INTEGER PRIMARY KEY, job_id INTEGER NOT NULL REFERENCES job_cards(id), technician_id INTEGER REFERENCES technicians(id), started TEXT NOT NULL, stopped TEXT);
CREATE TABLE attachments (id INTEGER PRIMARY KEY, entity TEXT NOT NULL, entity_id INTEGER NOT NULL, path TEXT NOT NULL, label TEXT, added TEXT NOT NULL);

CREATE TABLE invoices (id INTEGER PRIMARY KEY, inv_no TEXT NOT NULL, kind TEXT NOT NULL, date TEXT NOT NULL, customer_id INTEGER REFERENCES customers(id),
  vehicle_id INTEGER REFERENCES vehicles(id), job_id INTEGER REFERENCES job_cards(id), goods_net INTEGER NOT NULL DEFAULT 0, services_net INTEGER NOT NULL DEFAULT 0,
  discount INTEGER NOT NULL DEFAULT 0, goods_tax INTEGER NOT NULL DEFAULT 0, services_tax INTEGER NOT NULL DEFAULT 0, rounding INTEGER NOT NULL DEFAULT 0,
  total INTEGER NOT NULL DEFAULT 0, cost INTEGER NOT NULL DEFAULT 0, insurer_id INTEGER REFERENCES customers(id), insurer_share INTEGER NOT NULL DEFAULT 0,
  points_used INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL DEFAULT 'Posted', against_id INTEGER, fbr_no TEXT, notes TEXT, created_at TEXT NOT NULL);
CREATE TABLE invoice_lines (id INTEGER PRIMARY KEY, invoice_id INTEGER NOT NULL REFERENCES invoices(id), kind TEXT NOT NULL, part_id INTEGER REFERENCES parts(id),
  description TEXT NOT NULL, qty INTEGER NOT NULL, rate INTEGER NOT NULL, discount INTEGER NOT NULL DEFAULT 0, tax_bp INTEGER NOT NULL DEFAULT 0,
  tax INTEGER NOT NULL DEFAULT 0, net INTEGER NOT NULL DEFAULT 0, cost INTEGER NOT NULL DEFAULT 0, technician_id INTEGER);
CREATE TABLE estimates (id INTEGER PRIMARY KEY, est_no TEXT NOT NULL, date TEXT NOT NULL, customer_id INTEGER REFERENCES customers(id), vehicle_id INTEGER REFERENCES vehicles(id),
  job_id INTEGER, total INTEGER NOT NULL DEFAULT 0, lines TEXT NOT NULL, notes TEXT);
CREATE TABLE payments (id INTEGER PRIMARY KEY, pay_no TEXT NOT NULL, date TEXT NOT NULL, direction TEXT NOT NULL, party_type TEXT NOT NULL, party_id INTEGER NOT NULL,
  invoice_id INTEGER, purchase_id INTEGER, method TEXT NOT NULL, amount INTEGER NOT NULL, wht INTEGER NOT NULL DEFAULT 0, reference TEXT,
  cheque_date TEXT, cheque_status TEXT, note TEXT, voucher_id INTEGER);
CREATE TABLE payment_allocations (id INTEGER PRIMARY KEY, payment_id INTEGER NOT NULL REFERENCES payments(id), invoice_id INTEGER, purchase_id INTEGER, amount INTEGER NOT NULL);

CREATE TABLE accounts (id INTEGER PRIMARY KEY, code TEXT NOT NULL UNIQUE, name TEXT NOT NULL, atype TEXT NOT NULL, parent_id INTEGER REFERENCES accounts(id),
  sys TEXT UNIQUE, is_group INTEGER NOT NULL DEFAULT 0, active INTEGER NOT NULL DEFAULT 1);
CREATE TABLE vouchers (id INTEGER PRIMARY KEY, v_no TEXT NOT NULL, vtype TEXT NOT NULL, date TEXT NOT NULL, narration TEXT, ref_type TEXT, ref_id INTEGER,
  attachment TEXT, reversal_of INTEGER, reversed INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL);
CREATE TABLE voucher_lines (id INTEGER PRIMARY KEY, voucher_id INTEGER NOT NULL REFERENCES vouchers(id), account_id INTEGER NOT NULL REFERENCES accounts(id),
  debit INTEGER NOT NULL DEFAULT 0, credit INTEGER NOT NULL DEFAULT 0, party_type TEXT, party_id INTEGER, memo TEXT);
CREATE INDEX ix_vl_account ON voucher_lines(account_id);
CREATE INDEX ix_vl_party ON voucher_lines(party_type, party_id);
CREATE TABLE period_locks (month TEXT PRIMARY KEY, locked_at TEXT NOT NULL);
CREATE TABLE year_closes (fy TEXT PRIMARY KEY, closed_at TEXT NOT NULL, voucher_id INTEGER);

CREATE TABLE cash_closings (id INTEGER PRIMARY KEY, date TEXT NOT NULL UNIQUE, opening INTEGER NOT NULL, cash_in INTEGER NOT NULL, cash_out INTEGER NOT NULL,
  expected INTEGER NOT NULL, counted INTEGER NOT NULL, difference INTEGER NOT NULL, note TEXT, voucher_id INTEGER);
CREATE TABLE bank_recon (voucher_line_id INTEGER PRIMARY KEY, cleared_date TEXT NOT NULL);

CREATE TABLE staff (id INTEGER PRIMARY KEY, name TEXT NOT NULL, role TEXT, phone TEXT, cnic TEXT, salary INTEGER NOT NULL DEFAULT 0, technician_id INTEGER,
  machine_id TEXT, joined TEXT, active INTEGER NOT NULL DEFAULT 1);
CREATE TABLE attendance (id INTEGER PRIMARY KEY, staff_id INTEGER NOT NULL REFERENCES staff(id), date TEXT NOT NULL, time_in TEXT, time_out TEXT, UNIQUE(staff_id, date));
CREATE TABLE payroll (id INTEGER PRIMARY KEY, staff_id INTEGER NOT NULL REFERENCES staff(id), month TEXT NOT NULL, basic INTEGER NOT NULL, commission INTEGER NOT NULL,
  advance_deducted INTEGER NOT NULL, other_deduction INTEGER NOT NULL, net INTEGER NOT NULL, method TEXT NOT NULL, voucher_id INTEGER, UNIQUE(staff_id, month));

CREATE TABLE fixed_assets (id INTEGER PRIMARY KEY, name TEXT NOT NULL, category TEXT, bought TEXT NOT NULL, cost INTEGER NOT NULL, salvage INTEGER NOT NULL DEFAULT 0,
  life_months INTEGER NOT NULL, depreciated INTEGER NOT NULL DEFAULT 0, last_month TEXT, disposed TEXT, notes TEXT);
CREATE TABLE loyalty_moves (id INTEGER PRIMARY KEY, customer_id INTEGER NOT NULL REFERENCES customers(id), date TEXT NOT NULL, points INTEGER NOT NULL, invoice_id INTEGER, note TEXT);
CREATE TABLE messages (id INTEGER PRIMARY KEY, date TEXT NOT NULL, channel TEXT NOT NULL, phone TEXT NOT NULL, text TEXT NOT NULL, ref_type TEXT, ref_id INTEGER, status TEXT NOT NULL)
"""
}

/** The preloaded chart of accounts for a workshop. `sys` keys are used by automatic postings. */
object SeedAccounts {
    data class A(val code: String, val name: String, val type: String, val parent: String?, val sys: String?, val group: Boolean = false)

    val list = listOf(
        A("1000", "Assets", "ASSET", null, null, true),
        A("1100", "Cash and bank", "ASSET", "1000", null, true),
        A("1110", "Cash in hand", "ASSET", "1100", "CASH"),
        A("1120", "Bank account", "ASSET", "1100", "BANK"),
        A("1130", "JazzCash / Easypaisa wallets", "ASSET", "1100", "WALLET"),
        A("1140", "Cheques in hand", "ASSET", "1100", "CHEQUES"),
        A("1200", "Customer receivables", "ASSET", "1000", "AR"),
        A("1300", "Stock of parts", "ASSET", "1000", "INVENTORY"),
        A("1400", "Advance tax deducted by customers", "ASSET", "1000", "WHT_REC"),
        A("1410", "Staff advances", "ASSET", "1000", "STAFF_ADV"),
        A("1500", "Equipment and fixed assets", "ASSET", "1000", "FIXED_ASSETS"),
        A("1510", "Accumulated depreciation", "ASSET", "1000", "ACC_DEP"),
        A("2000", "Liabilities", "LIABILITY", null, null, true),
        A("2100", "Supplier payables", "LIABILITY", "2000", "AP"),
        A("2200", "Sales tax payable (FBR)", "LIABILITY", "2000", "GST"),
        A("2210", "Sales tax on services payable (province)", "LIABILITY", "2000", "PST"),
        A("2300", "Salaries payable", "LIABILITY", "2000", "SAL_PAY"),
        A("2400", "Customer advances", "LIABILITY", "2000", "CUST_ADV"),
        A("3000", "Capital", "EQUITY", null, null, true),
        A("3100", "Owner's capital", "EQUITY", "3000", "CAPITAL"),
        A("3200", "Owner's drawings", "EQUITY", "3000", "DRAWINGS"),
        A("3300", "Retained earnings", "EQUITY", "3000", "RETAINED"),
        A("3400", "Opening balances", "EQUITY", "3000", "OPENING"),
        A("4000", "Income", "INCOME", null, null, true),
        A("4100", "Parts sales", "INCOME", "4000", "SALES_PARTS"),
        A("4200", "Labour income", "INCOME", "4000", "SALES_LABOUR"),
        A("4300", "Sublet work income", "INCOME", "4000", "SALES_SUBLET"),
        A("4400", "Other income", "INCOME", "4000", "OTHER_INCOME"),
        A("5000", "Expenses", "EXPENSE", null, null, true),
        A("5100", "Cost of parts sold", "EXPENSE", "5000", "COGS"),
        A("5110", "Sublet work cost", "EXPENSE", "5000", "SUBLET_COST"),
        A("5120", "Stock adjustments and losses", "EXPENSE", "5000", "STOCK_ADJ"),
        A("5200", "Salaries and wages", "EXPENSE", "5000", "SALARIES"),
        A("5210", "Technician commission", "EXPENSE", "5000", "COMMISSION"),
        A("5300", "Rent", "EXPENSE", "5000", "RENT"),
        A("5310", "Electricity", "EXPENSE", "5000", "ELECTRICITY"),
        A("5320", "Generator fuel", "EXPENSE", "5000", "FUEL"),
        A("5330", "Tea and food", "EXPENSE", "5000", "FOOD"),
        A("5340", "Tools and consumables", "EXPENSE", "5000", "TOOLS"),
        A("5350", "Utilities and internet", "EXPENSE", "5000", "UTILITIES"),
        A("5360", "Repairs and maintenance", "EXPENSE", "5000", "MAINT"),
        A("5400", "Depreciation", "EXPENSE", "5000", "DEPRECIATION"),
        A("5500", "Discounts and loyalty", "EXPENSE", "5000", "DISCOUNTS"),
        A("5600", "Cash shortage and rounding", "EXPENSE", "5000", "ROUNDING"),
        A("5900", "Miscellaneous expenses", "EXPENSE", "5000", "MISC"),
    )

    fun seed() {
        val ids = HashMap<String, Long>()
        for (a in list) {
            val id = Db.insert(
                "INSERT INTO accounts(code,name,atype,parent_id,sys,is_group) VALUES(?,?,?,?,?,?)",
                a.code, a.name, a.type, a.parent?.let { ids[it] }, a.sys, a.group
            )
            ids[a.code] = id
        }
    }
}
