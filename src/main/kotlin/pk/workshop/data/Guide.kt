package pk.workshop.data

import pk.workshop.db.Db

/**
 * The "Getting started" checklist. Each step ticks itself off from what's actually in the system,
 * so the list always matches reality (nothing to remember to tick).
 */
object Guide {
    /** Where a step's "Go" button leads: a screen name plus the tab to open on it. */
    data class Step(val key: String, val title: String, val why: String, val done: Boolean, val target: String, val tab: Int = 0, val skippable: Boolean = false)

    const val TOUR_DONE = "guide.tour_done"
    const val HIDDEN = "guide.hidden"
    const val TAXES_CHECKED = "guide.taxes_checked"
    private fun skipped(key: String) = Settings.bool("guide.skip.$key")
    fun skip(key: String) = Settings.setBool("guide.skip.$key", true)

    private fun count(sql: String) = Db.scalarLong(sql)

    fun steps(): List<Step> = listOf(
        Step("business", "Enter your business details", "Name, address, phone, NTN and STRN print on every bill.",
            Settings.get(Settings.BUSINESS).let { it.isNotBlank() && it != "My Auto Workshop" } && Settings.get(Settings.PHONE).isNotBlank(), "Settings", 0),
        Step("tax", "Check the tax rates", "Sales tax on parts (FBR) and services tax on labour (your province). Defaults: 18% and 16%.",
            Settings.bool(TAXES_CHECKED), "Settings", 1),
        Step("printers", "Set up your printers", "Receipt printer, A4 printer and label printer. Skip if you only want PDFs for now.",
            Settings.get(Settings.RECEIPT_PRINTER).isNotBlank() || Settings.get(Settings.A4_PRINTER).isNotBlank() || skipped("printers"), "Settings", 2, skippable = true),
        Step("backup", "Choose a backup folder", "Pick a USB drive or a Google Drive / OneDrive folder, so a dead PC doesn't take your records with it.",
            Settings.get(Settings.BACKUP_DIR).isNotBlank(), "Settings", 5),
        Step("techs", "Add your mechanics", "Technicians are chosen on labour lines; their commission is worked out from it.",
            count("SELECT count(*) FROM technicians") > 0, "Staff", 1),
        Step("parts", "Add your parts and stock", "Add parts one by one, or fill the Excel template and import it. Enter opening stock with each part.",
            count("SELECT count(*) FROM parts") > 0, "Inventory", 0),
        Step("opening", "Enter opening balances", "Cash in the drawer, money in the bank, and what customers owe you or you owe suppliers on day one.",
            count("SELECT count(*) FROM vouchers WHERE vtype='OPEN' AND ref_type IN ('account','customer','supplier')") > 0 || skipped("opening"), "Books", 11, skippable = true),
        Step("customer", "Add a customer and their car", "Or just start a job card: you can add the customer and car from there.",
            count("SELECT count(*) FROM vehicles") > 0, "Customers", 0),
        Step("job", "Open your first job card", "When a car arrives: job card → add parts and labour → make invoice → take payment → deliver.",
            count("SELECT count(*) FROM job_cards") > 0, "Jobs", 0),
        Step("payment", "Take your first payment", "From the bill, press Receive payment. Cash shows the change to give back.",
            count("SELECT count(*) FROM payments WHERE direction='IN'") > 0, "Billing", 0),
    )
}
