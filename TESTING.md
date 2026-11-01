# Testing with the sample data

Start the app with sample data:

```
./gradlew run -Pdemo
```

Log in as **admin** / **demo123**. The first start takes a few seconds while the data is created.

The sample data is **about four months of a busy Lahore workshop**, running from the 1st of the month three months ago up to today. It is made through the same functions the screens use, so every balance, report and ledger entry is real.

| | |
|---|---|
| Customers | about 125: individuals, 2 fleet companies, 1 insurer |
| Vehicles | about 140 |
| Job cards | about 320 |
| Bills | about 460, including about 150 counter sales |
| Ledger entries | about 1,150 |
| Inventory | 34 parts, 5 suppliers |
| Staff | 7, with 5 of them technicians |

To start over, quit the app and delete the `demo-data` folder in the project. The data is rebuilt the same way, with dates shifted to the new "today".

Amounts depend on the day you generate the data. Names and registration numbers are always the same.

---

## Who's who in the sample data

| Customer | Car | What's going on |
|---|---|---|
| **Ayesha Siddiqui** | LED-2201 Yaris | Arrived this morning. Job card is **Received**, with no work yet |
| **Mariam Javed** | LEE-1990 City | Came from a booking. Job is **Inspecting**, diagnosis written |
| **Farooq Sheikh** | LEC-4321 Fortuner | Estimate made. Job is **Awaiting approval** |
| **Imran Qureshi** | ICT-5590 Cultus | **Awaiting parts**. Timing belt is out of stock and on a purchase order |
| | ICT-202 Alto | Serviced, paid and delivered today. Service reminder overdue |
| **Rehman Transport** (fleet) | LES-7810 Hiace | **In progress**, timer running. Approved by phone |
| **Bilal Chaudhry** | FDA-221 Swift | **Quality check** |
| **Usman Tariq** | RIM-776 Wagon R | **Ready**. Billed but not paid; pays when he collects |
| **Faisal Rent a Car** (fleet) | LEC-910 Corolla | **Ready**, billed on account. The company is **over its credit limit** |
| **Zubair Ahmed** | LHR-3344 Mehran | Clutch done and delivered today. Booked again tomorrow for AC |
| **Ali Khan** | LEA-1234 Corolla | Has loyalty points and once paid with points. Has **store credit** from a returned air filter. Booked for 4:30 pm today |
| **Sana Malik** | LEB-778 City | **Comeback**: back within a week for the same suspension noise |
| **Nadia Hussain** | LEA-6612 Sportage | **Insurance claim**: paid the Rs 10,000 excess; Crescent Insurance paid the rest by cheque |
| **Hamza Butt** | LEF-90 Civic | **Insurance claim** where the insurer **hasn't paid yet** |
| **Crescent Insurance Co.** | — | The insurer's account (owes on Hamza's claim) |
| **Tariq Mehmood, Rubina Akhtar** | LEB-1010, LEA-5050 | Haven't been back in over 3 months. Rubina once took her car away without the work (a **cancelled** job) |
| **Kamran Ahmed** | LED-4410 | New customer, not in the system yet. Booked for 2 pm today with **pickup** from DHA |

Suppliers:
- **Madina Auto Parts:** general parts.
- **Lubricants House:** oils, always paid on the spot.
- **Karachi Battery Centre:** batteries.
- **Cool Air AC Parts:** AC parts.
- **Hafeez Denting Works:** sublet denting and paint.

Technicians:
- Usman and Bilal: mechanical.
- Shahid: electrical.
- Naveed: AC.
- Kashif: denting and tyres.

---

## 1. Dashboard

**What's there:** today's sales, cash, money owed, the 14-day sales chart, cars by status, service reminders and low stock.

**Try:** click each tile (Sales today, Cash in hand, Cars in workshop, Parts running low). Each one opens the matching screen.

**You should see:**
- 8 cars in the workshop, all at different stages.
- 2 parts running low: the timing belt and the clutch plate.
- 4 service reminders.
- A cheques-in-hand amount, which is Rehman's post-dated cheque.

## 2. A job from arrival to delivery

1. **Job cards (F2)** → open **LED-2201** (Received).
2. Change the status to **Inspecting**, and type a diagnosis in the job card's notes.
3. Add work:
   - **Part:** scan or type **P000001**, or search "oil filter".
   - **Labour:** pick a chip such as *Scanning / diagnostics*.
   - **Sublet** (optional): use Hafeez Denting Works.
4. **Estimate** → a PDF opens. Then **Record approval** (who approved and how).
5. **Start timer**, then **Stop timer**.
6. Print a **Job card**, **Key tag** and **Service sticker**.
7. **Make invoice.** Parts get 18% FBR sales tax and labour gets 16% services tax. The total rounds to whole rupees.
8. **Deliver car.** You're asked to take payment first. Pay **Cash** and enter more than the bill to see the change. Then deliver; the gate pass opens.

**Check afterwards:**
- **Bookkeeping → Vouchers:** a *Sale* and a *Receipt* entry.
- **Inventory:** the oil filter's stock is one lower.

## 3. Other job situations already in the data

**Awaiting approval: LEC-4321**
- **Record approval**, and the job moves to In progress.

**Awaiting parts: ICT-5590**
- Try adding a timing belt: it's out of stock and the app refuses.
- Receive the purchase order (see section 8), then add it.

**Timer running: LES-7810**
- Stop the timer and look at the time log.

**Ready but unpaid: RIM-776**
- Press **Deliver car**. You're asked to **take payment** first.
- Pay with **JazzCash** and a transaction ID.

**Fleet car on account: LEC-910**
- Press **Deliver car**, then **Deliver on credit**. Because Faisal Rent a Car is over its limit, you'll see a **credit warning**.

**Comeback: Sana Malik's LEB-778**
- Open the latest job: it's flagged as a comeback of the job the week before.

**Insurance claim: Hamza Butt's LEF-90**
- Open the bill (**Invoice** on the job): it shows what the insurer pays and what the customer paid (the Rs 10,000 excess).
- The A4 PDF prints the insurer's share too.
- To collect the insurer's share: **Customers → Crescent Insurance Co. → Receive payment** (e.g. by cheque). It's allocated to Hamza's bill, and the bill shows as paid.
- The job card has a **damage photo** attached.

**Cancelled job: Rubina Akhtar's LEA-5050**
- Open the cancelled job: the ATF went back into stock.

## 4. Bookings

**Bookings:**
- Today: Kamran Ahmed at 2 pm (with pickup) and Ali Khan at 4:30 pm.
- Tomorrow: Zubair at 10:00 and Faisal Rent a Car at 11:30 (pickup from their office).
- Hamza later in the week.

**Try:**
1. **Car arrived** on Kamran's booking. He isn't a customer yet, so press **Change vehicle → New customer and vehicle** and create him and his car.
2. Open the job card: the complaint is filled in from the booking, and the booking shows **Arrived**.
3. Cancel Hamza's booking with ×.
4. Press the message icon to see the WhatsApp reminder text.

## 5. Counter sale (F3)

**Try:**
1. Scan **8964000123454** (the Havoline 20W-50 pack's EAN-13 barcode), or search "wiper".
2. Add a tyre ×4 and give a discount.
3. At payment, enter only part of the bill as **Cash**, then pay the rest by **Card**. That's a split payment.
4. **Print receipt.** Without a thermal printer set up, it saves as an 80 mm PDF.

**Also try:**
- **Sell on credit (udhaar)** to Ali Khan.
- Selling a part below cost, to see the warning.

**Check:** the sale appears in **Billing**, and **Reports → Profit per bill** shows the profit on it.

## 6. Billing and payments (F6)

**Invoices:**
- Tick **Unpaid only**: Faisal, Rehman, Usman Tariq and Crescent Insurance appear.
- Open one and try **Open A4 PDF**, **Print receipt** and **WhatsApp**.

**Credit note:**
1. Open Ali Khan's older bill with the air filter. A credit note for the filter already exists.
2. Make another credit note for one litre of oil. The oil goes back into stock.
3. Ali's store credit grows, shown as a negative "Owes" in Customers.

**Payments received:**
- The mix includes Cash, Card, Bank transfer, JazzCash, Easypaisa, Raast, Cheque and Loyalty points.

**Cheques:**
- Rehman Transport's cheques:
  - one **cleared**
  - one **bounced** (the amount went back on his account and he later paid by bank transfer)
  - the current one is **post-dated and in hand**
- Mark the one in hand as **Cleared**, then check the bank balance in Bookkeeping.

**FBR queue:**
- The last two days' bills are waiting for an FBR number. Older ones have one.
- Enter a number on one of them.

**Withholding tax:** receive a payment from **Rehman Transport** and enter income tax withheld (e.g. 4%). It goes to *Advance tax deducted by customers*.

## 7. Customers and vehicles (F4)

**Search:**
- By name, phone, registration (`LEA-1234` or `lea1234`) or chassis number.
- Filter by type: Walk-in, Regular, Company / fleet, Insurance company.

**Customer pages:**
- **Rehman Transport:** **Statement** (PDF) shows bills, cheques, the bounced cheque and tax withheld.
- **Faisal Rent a Car:** owes more than its credit limit.
- **Ali Khan:** points balance and store credit.
- Any car: **History** lists every visit with odometer readings.

**New customer:**
- Mobile numbers (0300-1234567) and landlines (042-35761234) are both accepted.
- A wrong CNIC format is refused.

## 8. Inventory and purchasing (F5)

**Stock:**
- **Low stock only:** timing belt and clutch plate.
- Price an item: **Shelf label** prints a barcode label PDF.
- Remove a damaged part with **Remove (damage, loss)**. It posts a stock-adjustment entry.

**Stock counts:**
- An **open spot check** of rack A shows one Honda oil filter missing.
- **Approve count**: stock is corrected and the difference posted.
- An older **approved** count of the B racks is also there.

**Price update:**
- Raise all **Shell** prices by 5%, rounded to Rs 10.
- The data already has a 6% Shell rise and a 5% Electrical rise from last month.

**Purchasing:**
- **Suppliers:** Madina, Karachi Battery Centre and Cool Air are owed money. Pay one part of its balance.
- **Purchase bills:** weekly restocks. Some include freight ("landed cost"), which is spread into the part costs.
- **Purchase orders:**
  - Open: timing belts and Suzuki filters. Press **Goods arrived** to turn it into a bill and add stock.
  - One received, one cancelled.
- **Returns:** wrong-spec brake pads returned to Madina.
- **Warranty claims:**
  - A battery claim resolved by **replacement**: stock came back free and the average cost dropped.
  - A **leaking shock** claim still open. **Resolve** it as *Credited* to reduce what you owe Madina.
- **Fill from reorder suggestions** builds an order for everything running low.

## 9. Bookkeeping

**Vouchers:**
- Filter by type: SALE, RCPT, PUR, PAY, EXP, SAL, CPV, BPV, CRV, JV.
- There's a **reversed** duplicate fuel entry.
- Open any voucher to see its debit and credit lines.
- **New voucher:**
  - **CPV:** pay a misc expense in cash.
  - **JV:** move money between two accounts.
  - **Edit** it (change the amount), then **Delete** it.

**Editing and deleting (anything):**
- **Counter sale:** Billing › open a counter bill › **Edit**. Change a quantity and save: the number stays the same, and the stock and books follow.
- **Job bill:** open a job's invoice › **Change job and re-bill**. The job opens again; change a line, then make the invoice. It keeps its old number.
- **Purchase bill:** Purchasing › Purchase bills › open one › **Edit** the cost. If the parts are already sold, the cost difference goes to cost of parts automatically.
- **Payments:** Billing › Payments received (or Purchasing › Payments made) › click one › edit or delete.
- **Stock adjustment:** Inventory › Stock movements › click an ADJUST line.
- **Locked month:** lock a month, then try to delete a bill from it. It refuses. Unlock it again.

**Expenses:**
- Rent on the 1st, LESCO bills, gas, internet, staff food, fuel, generator service and tools.

**Cash closing:**
- The last 6 working days are closed, and one was **Rs 150 short**.
- Close yesterday or today: count the notes and see the difference posted.

**Ledger:**
- Pick *Cash in hand* or *Bank account* to see every movement with a running balance.

**Trial balance:**
- Shows **Balanced**.

**Profit & loss:**
- For **This month** and **This fiscal year** (July–June).

**Balance sheet:**
- Shows *Assets equal liabilities plus capital*.
- Includes owner's drawings, equipment less depreciation, and tax payable.

**Cash flow:**
- Money in and out by kind.

**Bank reconciliation:**
- Lines are ticked as cleared up to four days ago.
- Tick the rest against a pretend bank statement.

**Monthly sales tax returns:**
- FBR sales tax and PRA services tax are paid around the 15th as BPVs.

**Weekly deposits:**
- Cash and JazzCash/Easypaisa balances are moved to the bank on Saturdays.

**Fixed assets:**
- Car lift, scanner and compressor, plus an alignment machine bought during the period.
- Depreciation is posted monthly.
- **Charge depreciation** for this month.

**Month locks:**
- The first month is **locked**. Try entering an expense dated in it: refused.
- **Unlock** it and try again.

**Close a fiscal year:**
- Leave this alone in the demo. It moves the year's profit into capital and can't be undone.

## 10. Staff and payroll

**Staff:**
- Javed has a Rs 5,000 **advance** outstanding.

**Salaries:**
- Each month is paid on its last working day: basic + commission − advance deducted.
- Commission is a percentage of each technician's labour.
- Pay **this month** for someone: the advance deduction is offered.

**Technicians:**
- Commission rates.
- **Reports → Technician performance** shows labour sold per person.

**Attendance:**
- This month and last month are filled in.
- Today shows clock-in times only.

**Import machine log:**
- Make a CSV with lines like `101,2026-10-21 09:05` and `101,2026-10-21 18:40`.
- 101 to 107 are the staff machine IDs.

## 11. Reports

Every report has **PDF**, **Print** and **Excel**. Change the dates or use *Today / This month / This fiscal year*.

| Report | What to look for |
|---|---|
| Sales by day | Parts, labour and tax per day |
| Money received by method | Cash vs card vs wallets vs bank |
| Profit per bill / by item | Margins, with counter sales included |
| Stock value and movement / Dead stock | The steering rack and fog lamp kit have never sold |
| Receivables ageing | Fleet and insurer balances by age |
| Supplier balances | Who you owe |
| Technician performance / Most-requested services | Oil changes and brake jobs lead |
| Top customers / Not seen in 3 months | Tariq Mehmood and Rubina Akhtar appear |
| Vehicles by make | Suzuki and Toyota dominate |
| Purchases / Price changes | Lubricant and battery costs went up about 4% in the third month |
| Tax summary | Output tax for the sales tax returns |
| Expenses / Audit log | Every action, including bulk price changes and FBR numbers |

## 12. Printing and machines (Settings → Printers and machines)

**Without a printer:** documents save as PDFs in *Documents/Workshop Manager*.

**Try:**
- **Test receipt printer**, **Test label**, **Test cash drawer**.
- Turn on the **customer display** and make a counter sale: a second window shows the running bill.

**Barcodes:** scan **P000001** to **P000033**, or the EAN codes on lubricants, batteries, plugs and bulbs (e.g. 8964000123454).

## 13. Settings, backup and security

**Business:** change the name. The next invoice PDF shows it.

**Tax and FBR:** add a new rate from a future date. Bills dated after it use the new rate.

**Lists:** add a complaint, service or department. It shows up in the job card.

**Appearance:** switch between light, dark and system.

**Backup:**
1. **Back up now** makes a zip.
2. Make a change, then **Restore** the backup: the change is gone.

**Password:**
1. Change it, log out, and log in with the new one.
2. On the login screen, use **Forgot password** with the recovery code shown at setup. The demo data skips that screen, so test the recovery code on a fresh install (`./gradlew run`).

**Encryption:** `demo-data/workshop.db` can't be opened with an SQLite viewer; the file is encrypted.

---

## Automated checks

`./gradlew test` runs three checks:

- **FlowTest:** the accounting cycle.
- **VaultTest:** encryption and passwords.
- **DemoTest:** builds this whole sample data set and checks all of the following:
  - The trial balance and balance sheet balance, and no entry is one-sided.
  - The stock value in the books equals the shelf value plus parts sitting in open jobs.
  - No stock goes negative.
  - Every flow above has data, and every report has rows.

It prints a summary of how many records each flow has.
