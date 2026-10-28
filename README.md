# Workshop Manager

Desktop software for a car workshop in Pakistan. It covers job cards, billing, counter sales, parts inventory, purchasing, double-entry bookkeeping, staff and payroll, and reports. It runs on one Windows PC, with one admin login.

Built with Kotlin + Compose Multiplatform (desktop), with an encrypted SQLite database.

---

## Installing on the workshop PC

**Recommended: the installer (MSI)**
The installer bundles its own Java, so nothing else needs installing.

1. Push this folder to a GitHub repository.
2. Open **Actions → Windows installer → Run workflow**.
3. When the run finishes, download `workshop-manager-installers` from the run page. It contains the `.msi` and `.exe`.
4. Run the `.msi` on the workshop PC. It adds a Start-menu entry and a desktop shortcut.

You can also build the installer on any Windows PC that has JDK 21:

```
gradlew.bat packageMsi
```

The output goes to `build\compose\binaries\main\msi\`.

**Quick run without installing**
The `WorkshopManager-windows-x64-1.0.0.jar` file runs on Windows if Java 21 is installed (for example Eclipse Temurin 21). Put the jar and `run-windows.bat` in the same folder, then double-click the `.bat` file.

**Trying it on a Mac**
`WorkshopManager-mac-1.0.0.jar` runs on Apple Silicon and Intel Macs that have Java 21 (for example Temurin 21 from adoptium.net).

```
# with sample data (log in as admin / demo123)
java -Dworkshop.demo=1 -Dworkshop.dataDir="$HOME/WorkshopDemo" -jar WorkshopManager-mac-1.0.0.jar

# empty, like a real first run
java -jar WorkshopManager-mac-1.0.0.jar
```

On a Mac, real data is kept in `~/Library/Application Support/WorkshopManager/`. Delete the `WorkshopDemo` folder to reset the sample data.

**Trying every feature:** `./gradlew run -Pdemo` starts with four months of sample workshop data. [TESTING.md](TESTING.md) walks through each flow with that data.

## First run

The app guides first-time use itself:

- **Guided tour:** opens after the admin login is created and points at each part of the screen. You can replay it from **Help & tour** at the bottom of the menu.
- **Getting started checklist:** on the dashboard. Each step ticks itself off once it's really done, and **Go** jumps to the right screen and tab.
- **How this works:** short instructions on every screen. They open the first time you visit a screen; after that, use the **?** next to the title.


1. Choose the admin username and password.
2. The app shows a **recovery code** once. Write it down and keep it somewhere safe.
   - The code is the only way to reset a forgotten password.
   - Without the password or the recovery code, the data cannot be opened, by anyone.
3. Go to **Settings** and set up the business:
   - **Business:** name, address, phone, NTN and STRN.
   - **Tax and FBR:** province and tax rates. Defaults are FBR 18% on parts and PRA 16% on labour, so adjust these if needed.
   - **Printers and machines:** receipt printer, A4 printer, label printer, label size, cash drawer and customer display.
   - **Backup:** pick a USB drive or cloud-synced folder.
   - **Appearance:** light, dark or System (follows Windows).
4. Add opening stock (Inventory → Import parts from Excel, or add parts one at a time) and opening balances (Bookkeeping → Opening balances).

## Where things are kept

| What | Where |
|---|---|
| Database (encrypted) and password vault | `%APPDATA%\WorkshopManager\` |
| Invoices, job cards, labels, reports (PDF/Excel) | `Documents\Workshop Manager\` |
| Backups (automatic daily, keeps the latest 30) | `Documents\Workshop Manager\Backups\` unless changed in Settings |

A backup is a single `.zip` file holding the encrypted database, the vault and any attachments. Restore it from Settings → Backup. You then log in with the password that was in use when the backup was made.

## Keyboard shortcuts

F2 Job cards · F3 Counter sale · F4 Customers · F5 Inventory · F6 Billing

A USB barcode scanner works in any field marked with the scan icon. Scan a part on a job card, at the counter, or during a stock count.

## Hardware supported

- **Thermal receipt printers (80 mm, ESC/POS):** printed directly, with QR code, paper cut and cash-drawer kick. A "through driver" mode covers printers that won't take raw printing.
- **A4 laser/inkjet printers:** invoices, job cards, estimates, statements, vouchers, reports.
- **Label printers** (Zebra, TSC, Xprinter, etc. via the Windows driver): part labels, bin labels, key tags, service stickers, at any size (default 50×25 mm).
- **Barcode scanners:** USB or keyboard-wedge.
- **Cash drawer:** connected through the receipt printer.
- **Customer-facing display:** a second-monitor window showing the running bill.
- **Fingerprint/attendance machines:** import their exported CSV/Excel log in Staff → Attendance → Import machine log.

## Features

- **Job cards**
  - Check-in details: odometer, fuel, complaint and diagnosis.
  - Parts issued by scanning, labour with technician, sublet work.
  - Estimates and customer approval, job timer, attachments (photos).
  - Comeback detection (same car back within 15 days), status board.
  - Delivery with gate pass; unpaid jobs need payment or a credit account.
- **Billing**
  - Invoices from jobs, plus counter sales.
  - FBR sales tax on parts and provincial services tax on labour, rounded to whole rupees.
  - Payment methods: cash with change calculator, card, bank transfer, JazzCash/Easypaisa, cheques (post-dated tracking, bounce), and split payments.
  - Withholding tax deducted by companies.
  - Credit notes and returns, loyalty points.
  - FBR digital-invoice number queue.
  - WhatsApp messages to customers.
- **Customers and vehicles:** history by registration or chassis number, service reminders, credit limits, statements, ageing, bookings with pickup.
- **Inventory**
  - Parts with barcodes (EAN-13 / Code 128), brands, grades (genuine, OEM, local), bins.
  - Weighted-average cost, reorder levels, stock counts by scanner.
  - Bulk price changes, Excel import/export, dead-stock report.
- **Purchasing:** suppliers, purchase bills with landed cost, returns, purchase orders, reorder suggestions, warranty claims, supplier payments.
- **Bookkeeping**
  - Full double-entry ledger with automatic postings.
  - Manual vouchers: CPV, CRV, BPV, BRV, JV.
  - Expenses, daily cash closing, bank reconciliation.
  - Trial balance, profit & loss, balance sheet, cash flow, ledgers.
  - Period locks and year-end close (Pakistan fiscal year, July–June).
- **Staff:** salaries, advances, technician commission, payroll, attendance.
- **Fixed assets** with monthly depreciation.
- **Reports:**
  - Sales, profit by invoice and by service, technician performance.
  - Stock, purchases, price changes, tax summary, expenses.
  - Lapsed customers, audit log.
  - Every report can be exported to Excel or PDF.

## Security

- **Password:** a single admin password. It never leaves the PC.
- **Encryption:** the whole database is encrypted with ChaCha20 (SQLite3MultipleCiphers). The encryption key is itself wrapped with AES-256-GCM under the password and under the recovery code (PBKDF2, 310,000 rounds).
- **Mistakes in the books:** posted entries are never edited. They are reversed, and every action is written to the audit log.

## Developing

Needs **JDK 21 or newer** (a JDK, not just a JRE), for example Temurin 21 from adoptium.net or `brew install --cask temurin@21` on a Mac. Gradle downloads itself the first time you build.

```
./gradlew run -Pdemo                        # start with sample data (admin / demo123), kept in ./demo-data
./gradlew run                               # start empty, like a first install; data kept in ./dev-data
./gradlew test                              # accounting + security tests
./gradlew packageUberJarForCurrentOS        # single runnable jar
./gradlew -PtargetOs=windows packageUberJarForCurrentOS   # Windows jar built from Linux/macOS
./gradlew -PtargetOs=mac packageUberJarForCurrentOS       # Mac jar (Apple Silicon + Intel)
./gradlew packageDmg                        # Mac app with Java bundled (run on a Mac)
```

Development runs keep their data inside the project folder (`dev-data/`, `demo-data/`), never in the real data folder. Delete those folders to start over.

In IntelliJ IDEA (Community is fine): File → Open → choose this folder, wait for the Gradle import, then run `run` from the Gradle panel (Tasks → compose desktop). Add `-Pdemo` to the run configuration's arguments for sample data.

Code layout (`src/main/kotlin/pk/workshop`):
- `core`: money and dates
- `db`: database and schema
- `security`: the vault
- `data`: business logic and ledger postings
- `print`: PDFs, ESC/POS, barcodes
- `io`: Excel and backups
- `ui`: screens
