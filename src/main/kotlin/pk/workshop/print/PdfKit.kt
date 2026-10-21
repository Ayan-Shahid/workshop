package pk.workshop.print

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File

/** Millimetres to PDF points. */
fun mm(v: Float) = v * 72f / 25.4f

enum class Align { LEFT, RIGHT, CENTER }

data class Col(val title: String, val width: Float, val align: Align = Align.LEFT)

/**
 * A small top-down layout helper over PDFBox: text, wrapped paragraphs, tables with page breaks, images.
 * Coordinates: x from the left margin, [y] is the current baseline cursor moving down the page.
 */
class PdfKit(private val size: PDRectangle = PDRectangle.A4, val margin: Float = 36f) {
    val doc = PDDocument()
    val regular: PDFont = PDType1Font(Standard14Fonts.FontName.HELVETICA)
    val bold: PDFont = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
    val mono: PDFont = PDType1Font(Standard14Fonts.FontName.COURIER)
    val monoBold: PDFont = PDType1Font(Standard14Fonts.FontName.COURIER_BOLD)
    private lateinit var cs: PDPageContentStream
    private var page: PDPage? = null
    var y = 0f
    val width get() = size.width - 2 * margin
    val left get() = margin
    var onNewPage: (() -> Unit)? = null
    var pageNo = 0
        private set

    init { newPage() }

    fun newPage() {
        if (page != null) cs.close()
        page = PDPage(size).also { doc.addPage(it) }
        cs = PDPageContentStream(doc, page)
        y = size.height - margin
        pageNo++
        onNewPage?.invoke()
    }

    fun ensure(space: Float) { if (y - space < margin) newPage() }

    /** Helvetica only covers Western characters; anything else becomes '?'. */
    fun safe(s: String, f: PDFont = regular): String = buildString {
        for (ch in s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')) {
            val ok = try { f.getStringWidth(ch.toString()); true } catch (e: Exception) { false }
            append(if (ok) ch else '?')
        }
    }

    fun textWidth(s: String, sz: Float, f: PDFont = regular) = f.getStringWidth(safe(s, f)) / 1000f * sz

    fun text(s: String, x: Float, yy: Float = y, sz: Float = 10f, f: PDFont = regular, align: Align = Align.LEFT, color: Color = Color.BLACK) {
        val t = safe(s, f)
        val w = textWidth(t, sz, f)
        val xx = when (align) { Align.LEFT -> left + x; Align.RIGHT -> left + x - w; Align.CENTER -> left + x - w / 2 }
        cs.beginText(); cs.setNonStrokingColor(color); cs.setFont(f, sz); cs.newLineAtOffset(xx, yy); cs.showText(t); cs.endText()
        cs.setNonStrokingColor(Color.BLACK)
    }

    /** Cuts text to fit a width, adding "…" style dots. */
    fun fit(s: String, maxW: Float, sz: Float, f: PDFont = regular): String {
        var t = safe(s, f)
        if (textWidth(t, sz, f) <= maxW) return t
        while (t.isNotEmpty() && textWidth("$t...", sz, f) > maxW) t = t.dropLast(1)
        return "$t..."
    }

    fun wrap(s: String, maxW: Float, sz: Float, f: PDFont = regular): List<String> {
        val out = ArrayList<String>()
        for (para in s.split('\n')) {
            var line = ""
            for (word in safe(para, f).split(' ')) {
                val cand = if (line.isEmpty()) word else "$line $word"
                if (textWidth(cand, sz, f) <= maxW) line = cand else { if (line.isNotEmpty()) out += line; line = word }
            }
            out += line
        }
        return out
    }

    fun paragraph(s: String, sz: Float = 9f, f: PDFont = regular, x: Float = 0f, w: Float = width - x, lead: Float = sz * 1.35f) {
        for (l in wrap(s, w, sz, f)) { ensure(lead); y -= lead; text(l, x, y, sz, f) }
    }

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, wdt: Float = 0.6f, color: Color = Color.GRAY) {
        cs.setStrokingColor(color); cs.setLineWidth(wdt); cs.moveTo(left + x1, y1); cs.lineTo(left + x2, y2); cs.stroke()
    }

    fun rect(x: Float, yy: Float, w: Float, h: Float, fill: Color? = null, stroke: Color? = Color.GRAY) {
        cs.addRect(left + x, yy, w, h)
        when {
            fill != null && stroke != null -> { cs.setNonStrokingColor(fill); cs.setStrokingColor(stroke); cs.fillAndStroke() }
            fill != null -> { cs.setNonStrokingColor(fill); cs.fill() }
            else -> { cs.setStrokingColor(stroke ?: Color.GRAY); cs.stroke() }
        }
        cs.setNonStrokingColor(Color.BLACK)
    }

    fun image(img: BufferedImage, x: Float, yy: Float, w: Float, h: Float) {
        val pd = LosslessFactory.createFromImage(doc, img)
        cs.drawImage(pd, left + x, yy, w, h)
    }

    fun gap(h: Float) { y -= h }

    /** Draws a table; repeats the header on new pages. Rows are lists of cell strings. */
    fun table(cols: List<Col>, rows: List<List<String>>, sz: Float = 9f, boldLast: Boolean = false, header: Boolean = true) {
        val rowH = sz * 1.7f
        val totalW = cols.sumOf { it.width.toDouble() }.toFloat()
        val scale = width / totalW
        val xs = ArrayList<Float>(); var acc = 0f
        cols.forEach { xs += acc; acc += it.width * scale }
        fun cellX(i: Int) = when (cols[i].align) { Align.LEFT -> xs[i] + 3; Align.RIGHT -> xs[i] + cols[i].width * scale - 3; Align.CENTER -> xs[i] + cols[i].width * scale / 2 }
        fun drawHeader() {
            ensure(rowH * 2)
            rect(0f, y - rowH + 3, width, rowH, fill = Color(0xEE, 0xEE, 0xEE), stroke = null)
            cols.forEachIndexed { i, c -> text(fit(c.title, c.width * scale - 6, sz, bold), cellX(i), y - rowH + 3 + sz * 0.55f, sz, bold, c.align) }
            y -= rowH
        }
        if (header) drawHeader()
        rows.forEachIndexed { ri, r ->
            if (y - rowH < margin) { newPage(); if (header) drawHeader() }
            val f = if (boldLast && ri == rows.lastIndex) bold else regular
            r.forEachIndexed { i, v -> if (i < cols.size) text(fit(v, cols[i].width * scale - 6, sz, f), cellX(i), y - rowH + 3 + sz * 0.55f, sz, f, cols[i].align) }
            y -= rowH
            line(0f, y + 3, width, y + 3, 0.3f, Color.LIGHT_GRAY)
        }
    }

    fun save(file: File): File {
        cs.close()
        file.parentFile?.mkdirs()
        doc.save(file); doc.close()
        return file
    }
}
