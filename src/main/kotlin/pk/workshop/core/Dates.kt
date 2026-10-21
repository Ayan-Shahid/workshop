package pk.workshop.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object Dates {
    private val display = DateTimeFormatter.ofPattern("dd-MM-yyyy")
    private val displayTime = DateTimeFormatter.ofPattern("dd-MM-yyyy hh:mm a")
    private val iso = DateTimeFormatter.ISO_LOCAL_DATE

    /** Only set while the demo data is being generated, so past events get past dates. Always null in normal use. */
    @Volatile var clock: LocalDateTime? = null

    fun today(): String = (clock?.toLocalDate() ?: LocalDate.now()).format(iso)
    fun now(): String = (clock ?: LocalDateTime.now()).withNano(0).toString()

    fun show(isoDate: String?): String {
        if (isoDate.isNullOrBlank()) return ""
        return try {
            if (isoDate.length > 10) LocalDateTime.parse(isoDate).format(displayTime)
            else LocalDate.parse(isoDate).format(display)
        } catch (e: Exception) { isoDate }
    }

    /** Accepts dd-MM-yyyy or yyyy-MM-dd; returns ISO yyyy-MM-dd or null. */
    fun parse(text: String): String? {
        val t = text.trim()
        return try { LocalDate.parse(t, iso).format(iso) } catch (e: Exception) {
            try { LocalDate.parse(t, display).format(iso) } catch (e2: Exception) { null }
        }
    }

    /** Pakistan fiscal year runs July–June. Returns e.g. "2627" for 1 Jul 2026 – 30 Jun 2027. */
    fun fiscalCode(isoDate: String): String {
        val d = LocalDate.parse(isoDate.take(10))
        val start = if (d.monthValue >= 7) d.year else d.year - 1
        return "%02d%02d".format(start % 100, (start + 1) % 100)
    }

    fun fiscalStart(isoDate: String): String {
        val d = LocalDate.parse(isoDate.take(10))
        val start = if (d.monthValue >= 7) d.year else d.year - 1
        return LocalDate.of(start, 7, 1).format(iso)
    }

    fun fiscalEnd(isoDate: String): String {
        val d = LocalDate.parse(fiscalStart(isoDate))
        return d.plusYears(1).minusDays(1).format(iso)
    }

    fun monthStart(isoDate: String = today()): String = LocalDate.parse(isoDate.take(10)).withDayOfMonth(1).format(iso)
    fun plusDays(isoDate: String, days: Long): String = LocalDate.parse(isoDate.take(10)).plusDays(days).format(iso)
    fun daysBetween(fromIso: String, toIso: String): Long =
        java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(fromIso.take(10)), LocalDate.parse(toIso.take(10)))
}
