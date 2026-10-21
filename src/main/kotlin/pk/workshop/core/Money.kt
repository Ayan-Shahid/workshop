package pk.workshop.core

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Money is stored everywhere as Long paisa (1 rupee = 100 paisa) so there is never floating-point drift.
 * Quantities are stored as Long thousandths (1.5 litres = 1500) so oil can be sold loose.
 */
object Money {
    var lakhGrouping: Boolean = false

    fun toBig(paisa: Long): BigDecimal = BigDecimal.valueOf(paisa, 2)
    fun fromBig(v: BigDecimal): Long = v.setScale(2, RoundingMode.HALF_UP).unscaledValue().toLong()

    /** Parses user text like "1,250.50" or "1250" into paisa. Returns null if invalid. */
    fun parse(text: String): Long? {
        val t = text.replace(",", "").replace("Rs", "", ignoreCase = true).trim()
        if (t.isEmpty()) return 0
        return try { fromBig(BigDecimal(t)) } catch (e: NumberFormatException) { null }
    }

    /** "12,500" (whole rupees) or "12,500.50" when there are paisa. */
    fun plain(paisa: Long, alwaysDecimals: Boolean = false): String {
        val neg = paisa < 0
        val abs = kotlin.math.abs(paisa)
        val rupees = abs / 100
        val p = abs % 100
        val grouped = if (lakhGrouping) groupLakh(rupees) else groupWestern(rupees)
        val dec = if (p != 0L || alwaysDecimals) "." + p.toString().padStart(2, '0') else ""
        return (if (neg) "-" else "") + grouped + dec
    }

    fun rs(paisa: Long): String = "Rs " + plain(paisa)

    private fun groupWestern(n: Long): String = "%,d".format(n)

    private fun groupLakh(n: Long): String {
        val s = n.toString()
        if (s.length <= 3) return s
        val last3 = s.takeLast(3)
        var rest = s.dropLast(3)
        val parts = ArrayList<String>()
        while (rest.length > 2) { parts.add(0, rest.takeLast(2)); rest = rest.dropLast(2) }
        if (rest.isNotEmpty()) parts.add(0, rest)
        return parts.joinToString(",") + "," + last3
    }

    /** Rounds to the nearest whole rupee (invoices are settled in whole rupees). */
    fun roundToRupee(paisa: Long): Long {
        val r = BigDecimal.valueOf(paisa, 2).setScale(0, RoundingMode.HALF_UP)
        return r.toLong() * 100
    }

    /** percent of amount, e.g. pct(10000, 1800) = 18% of Rs 100. Rate is in basis points (18% = 1800). */
    fun pct(paisa: Long, basisPoints: Int): Long =
        BigDecimal.valueOf(paisa).multiply(BigDecimal.valueOf(basisPoints.toLong()))
            .divide(BigDecimal.valueOf(10000), 0, RoundingMode.HALF_UP).toLong()

    /** amount × qty, where qty is in thousandths. */
    fun times(ratePaisa: Long, qtyMilli: Long): Long =
        BigDecimal.valueOf(ratePaisa).multiply(BigDecimal.valueOf(qtyMilli))
            .divide(BigDecimal.valueOf(1000), 0, RoundingMode.HALF_UP).toLong()

    fun words(paisa: Long): String {
        val rupees = kotlin.math.abs(paisa) / 100
        val p = kotlin.math.abs(paisa) % 100
        val sb = StringBuilder("Rupees ")
        sb.append(if (rupees == 0L) "Zero" else numberWords(rupees))
        if (p > 0) sb.append(" and ").append(numberWords(p)).append(" Paisa")
        sb.append(" Only")
        return sb.toString()
    }

    private val ones = arrayOf("", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
        "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen")
    private val tens = arrayOf("", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety")

    private fun below1000(n: Int): String {
        val parts = ArrayList<String>()
        var x = n
        if (x >= 100) { parts.add(ones[x / 100] + " Hundred"); x %= 100 }
        if (x >= 20) { parts.add(tens[x / 10] + (if (x % 10 != 0) " " + ones[x % 10] else "")) }
        else if (x > 0) parts.add(ones[x])
        return parts.joinToString(" ")
    }

    fun numberWords(n: Long): String {
        if (n == 0L) return "Zero"
        var x = n
        val parts = ArrayList<String>()
        val units = listOf(1_000_000_000L to "Billion", 1_000_000L to "Million", 1_000L to "Thousand")
        for ((size, name) in units) {
            if (x >= size) { parts.add(below1000((x / size).toInt()) + " " + name); x %= size }
        }
        if (x > 0) parts.add(below1000(x.toInt()))
        return parts.joinToString(" ")
    }
}

object Qty {
    fun parse(text: String): Long? {
        val t = text.replace(",", "").trim()
        if (t.isEmpty()) return null
        return try { BigDecimal(t).multiply(BigDecimal(1000)).setScale(0, RoundingMode.HALF_UP).toLong() }
        catch (e: NumberFormatException) { null }
    }
    fun fmt(milli: Long): String {
        val b = BigDecimal.valueOf(milli, 3).stripTrailingZeros()
        return if (b.scale() < 0) b.setScale(0).toPlainString() else b.toPlainString()
    }
}

/** Percent stored in basis points: 18% = 1800. */
object Pct {
    fun parse(text: String): Int? = try {
        BigDecimal(text.replace("%", "").trim()).multiply(BigDecimal(100)).setScale(0, RoundingMode.HALF_UP).toInt()
    } catch (e: Exception) { null }
    fun fmt(bp: Int): String {
        val b = BigDecimal.valueOf(bp.toLong(), 2).stripTrailingZeros()
        return (if (b.scale() < 0) b.setScale(0).toPlainString() else b.toPlainString()) + "%"
    }
}
