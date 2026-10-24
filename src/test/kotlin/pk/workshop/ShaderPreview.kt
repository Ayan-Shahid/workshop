package pk.workshop

import org.jetbrains.skia.*
import pk.workshop.ui.Shaders
import kotlin.test.Test

/** Renders mock screens with the candidate shaders so they can be compared before building them into the app. */
class ShaderPreview {
    private class Pal(val dark: Boolean, val bg: Int, val surface: Int, val primary: Int, val on: Int, val onVar: Int, val outlineVar: Int,
                      val outline: Int, val error: Int, val navSel: Int, val navSelText: Int, val field: Int, val fieldRO: Int,
                      val mesh: List<Int>, val meshBorder: Int, val green: Int, val amber: Int)

    private val light = Pal(false, 0xFFF6F4F4.toInt(), 0xFFFDFBFB.toInt(), 0xFFD32F2F.toInt(), 0xFF1C1B1E.toInt(), 0xFF534746.toInt(),
        0xFFD8C2C0.toInt(), 0xFF857372.toInt(), 0xFF9F1D1D.toInt(), 0xFFFFDAD6.toInt(), 0xFF410002.toInt(), 0xFFFFFFFF.toInt(), 0xFFF0E8E7.toInt(),
        listOf(0xFFEB4F49, 0xFFD32F2F, 0xFFB42222, 0xFF8E1515, 0xFFDE3A35).map { it.toInt() }, 0xFF8E1515.toInt(), 0xFF2E6B3A.toInt(), 0xFFB86E00.toInt())
    private val dark = Pal(true, 0xFF141316.toInt(), 0xFF1C1B1E.toInt(), 0xFFE53935.toInt(), 0xFFECE6E6.toInt(), 0xFFD2C3C1.toInt(),
        0xFF4F4445.toInt(), 0xFF9E8C8A.toInt(), 0xFFFF8A80.toInt(), 0xFF8C1D18.toInt(), 0xFFFFDAD6.toInt(), 0xFF232126.toInt(), 0xFF2A2729.toInt(),
        listOf(0xFFFF5F57, 0xFFE53935, 0xFFC62828, 0xFF9C1A1A, 0xFFEF4640).map { it.toInt() }, 0xFF8A1717.toInt(), 0xFF8FD49B.toInt(), 0xFFFFB74D.toInt())

    private fun RuntimeShaderBuilder.col(name: String, c: Int) =
        uniform(name, ((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f, 1f)

    private fun shader(src: String, w: Float, h: Float, setup: RuntimeShaderBuilder.() -> Unit): Shader {
        val b = RuntimeShaderBuilder(RuntimeEffect.makeForShader(src))
        b.uniform("size", w, h); b.setup()
        return b.makeShader()
    }

    private val regular = FontMgr.default.matchFamilyStyle("DejaVu Sans", FontStyle.NORMAL) ?: FontMgr.default.legacyMakeTypeface("", FontStyle.NORMAL)!!
    private val bold = FontMgr.default.matchFamilyStyle("DejaVu Sans", FontStyle.BOLD) ?: regular

    private fun Canvas.text(s: String, x: Float, y: Float, size: Float, color: Int, b: Boolean = false, right: Boolean = false) {
        val f = Font(if (b) bold else regular, size)
        val xx = if (right) x - f.measureTextWidth(s) else x
        drawString(s, xx, y, f, Paint().apply { this.color = color; isAntiAlias = true })
    }
    private fun width(s: String, size: Float, b: Boolean = false) = Font(if (b) bold else regular, size).measureTextWidth(s)

    /** A card: shader fill in its own coordinates plus a simple 1 px border. */
    private fun Canvas.card(x: Float, y: Float, w: Float, h: Float, p: Pal, cardSrc: String, setup: RuntimeShaderBuilder.() -> Unit) {
        val rr = RRect.makeXYWH(x, y, w, h, 14f)
        save(); translate(x, y)
        drawRRect(RRect.makeXYWH(0f, 0f, w, h, 14f), Paint().apply { shader = shader(cardSrc, w, h, setup); isAntiAlias = true })
        restore()
        drawRRect(rr, Paint().apply { color = p.outlineVar; mode = PaintMode.STROKE; strokeWidth = 1f; isAntiAlias = true })
    }

    private fun Canvas.meshButton(x: Float, y: Float, label: String, p: Pal, h: Float = 40f): Float {
        val w = width(label, 14f, true) + 48f
        save(); translate(x, y)
        drawRRect(RRect.makeXYWH(0f, 0f, w, h, h / 2), Paint().apply { isAntiAlias = true
            shader = shader(Shaders.BUTTON_MESH, w, h) { col("c0", p.mesh[0]); col("c1", p.mesh[1]); col("c2", p.mesh[2]); col("c3", p.mesh[3]); col("c4", p.mesh[4]) } })
        drawRRect(RRect.makeXYWH(0.5f, 0.5f, w - 1, h - 1, h / 2), Paint().apply { color = p.meshBorder; mode = PaintMode.STROKE; strokeWidth = 1f; isAntiAlias = true })
        restore()
        text(label, x + 24f, y + h / 2 + 5f, 14f, 0xFFFFFFFF.toInt(), b = true)
        return w
    }

    private fun Canvas.outlinedButton(x: Float, y: Float, label: String, p: Pal) {
        val w = width(label, 14f, true) + 40f
        drawRRect(RRect.makeXYWH(x + 0.5f, y + 0.5f, w - 1, 39f, 20f), Paint().apply { color = p.outline; mode = PaintMode.STROKE; strokeWidth = 1f; isAntiAlias = true })
        text(label, x + 20f, y + 25f, 14f, p.primary, b = true)
    }

    /** Input with a persistent label above a solid box; the border colour carries the state. */
    private fun Canvas.input(x: Float, y: Float, w: Float, label: String, value: String, state: String, p: Pal, hint: String = "") {
        text(label, x, y + 12f, 12.5f, if (state == "focused") p.primary else if (state == "error") p.error else p.onVar, b = true)
        val by = y + 20f
        val fill = if (state == "readonly") p.fieldRO else p.field
        val border = when (state) { "focused" -> p.primary; "error" -> p.error; "hover" -> p.outline; else -> p.outlineVar }
        val sw = if (state == "focused" || state == "error") 2f else 1f
        drawRRect(RRect.makeXYWH(x, by, w, 44f, 8f), Paint().apply { color = fill; isAntiAlias = true })
        drawRRect(RRect.makeXYWH(x + sw / 2, by + sw / 2, w - sw, 44f - sw, 8f), Paint().apply { color = border; mode = PaintMode.STROKE; strokeWidth = sw; isAntiAlias = true })
        if (value.isEmpty()) text(hint, x + 14f, by + 28f, 14.5f, (p.onVar and 0x00FFFFFF) or 0x99000000.toInt())
        else text(value, x + 14f, by + 28f, 14.5f, if (state == "readonly") p.onVar else p.on)
        if (state == "focused") drawRect(Rect.makeXYWH(x + 14f + width(value, 14.5f), by + 13f, 1.5f, 19f), Paint().apply { color = p.primary })
        if (state == "error") text("Phone should look like 0300-1234567", x, by + 62f, 12f, p.error)
        if (state == "readonly") text("Set automatically", x + w - 14f, by + 28f, 12f, p.onVar, right = true)
    }

    private fun Canvas.chip(x: Float, y: Float, label: String, c: Int, p: Pal) {
        val w = width(label, 11.5f) + 18f
        drawRRect(RRect.makeXYWH(x, y, w, 22f, 11f), Paint().apply { color = (c and 0x00FFFFFF) or (if (p.dark) 0x33000000 else 0x22000000); isAntiAlias = true })
        text(label, x + 9f, y + 15f, 11.5f, c)
    }

    private fun render(p: Pal, bgName: String, cardName: String, file: String) {
        val W = 1280; val H = 800
        val surface = Surface.makeRasterN32Premul(W, H)
        val c = surface.canvas
        // Background shader over the whole window
        val bg = when (bgName) {
            "topo" -> shader(Shaders.BG_TOPO, W.toFloat(), H.toFloat()) { col("base", p.bg); col("ink", p.primary)
                uniform("lineAlpha", if (p.dark) 0.17f else 0.11f); uniform("glowAlpha", if (p.dark) 0.16f else 0.10f) }
            "forged" -> shader(Shaders.BG_FORGED, W.toFloat(), H.toFloat()) {
                if (p.dark) { col("resin", 0xFF08080A.toInt()); col("flakeLo", 0xFF0F0E11.toInt()); col("flakeHi", 0xFF3A383D.toInt()) }
                else { col("resin", 0xFFE2DDDD.toInt()); col("flakeLo", 0xFFEAE6E6.toInt()); col("flakeHi", 0xFFFFFFFF.toInt()) }
                col("ink", p.primary); uniform("glowAlpha", if (p.dark) 0.14f else 0.08f); uniform("scale", 60f) }
            else -> shader(Shaders.BG_SILK, W.toFloat(), H.toFloat()) { col("base", p.bg); col("ink", p.primary)
                col("ink2", if (p.dark) 0xFF4A0B0B.toInt() else 0xFF8E1414.toInt()); uniform("amount", if (p.dark) 0.30f else 0.15f) }
        }
        c.drawRect(Rect.makeWH(W.toFloat(), H.toFloat()), Paint().apply { shader = bg })
        val cardSrc = when (cardName) { "brushed" -> Shaders.CARD_BRUSHED; "carbon" -> Shaders.CARD_CARBON; else -> Shaders.CARD_MATTE }
        val cardSetup: RuntimeShaderBuilder.() -> Unit = if (cardName == "carbon") ({
            if (p.dark) { col("base", 0xFF1E1D20.toInt()); col("lo", 0xFF1A191C.toInt()); col("hi", 0xFF2E2C30.toInt()) }
            else { col("base", 0xFFFBF9F9.toInt()); col("lo", 0xFFF3F0F0.toInt()); col("hi", 0xFFFFFFFF.toInt()) }
            uniform("tow", 7f); uniform("amount", 1f)
        }) else if (cardName == "brushed") ({
            col("base", if (p.dark) 0xFF1F1E21.toInt() else 0xFFF8F5F5.toInt()); col("sheen", if (p.dark) 0xFF3B3739.toInt() else 0xFFFFFFFF.toInt())
            uniform("streak", if (p.dark) 0.035f else 0.03f); uniform("sheenAmt", if (p.dark) 0.35f else 0.75f)
        }) else ({
            col("base", if (p.dark) 0xFF1D1C1F.toInt() else 0xFFFAF8F8.toInt()); col("sheen", if (p.dark) 0xFF2F2C30.toInt() else 0xFFFFFFFF.toInt())
            uniform("grain", if (p.dark) 0.035f else 0.03f); uniform("sheenAmt", if (p.dark) 0.55f else 0.7f)
        })

        // Sidebar (sits on the background, separated by a hairline)
        c.drawRect(Rect.makeXYWH(240f, 0f, 1f, H.toFloat()), Paint().apply { color = p.outlineVar })
        c.drawRRect(RRect.makeXYWH(20f, 22f, 36f, 36f, 9f), Paint().apply { color = p.primary; isAntiAlias = true })
        c.text("W", 31f, 46f, 16f, 0xFFFFFFFF.toInt(), b = true)
        c.text("Al-Noor Auto", 66f, 37f, 15f, p.on, b = true); c.text("Workshop Manager", 66f, 54f, 11f, p.onVar)
        listOf("Dashboard", "Job cards", "Bookings", "Counter sale", "Billing", "Customers", "Inventory", "Purchasing", "Bookkeeping", "Staff", "Reports", "Settings")
            .forEachIndexed { i, s ->
                val y = 92f + i * 44f
                if (i == 0) c.drawRRect(RRect.makeXYWH(14f, y, 212f, 38f, 19f), Paint().apply { color = p.navSel; isAntiAlias = true })
                c.drawRRect(RRect.makeXYWH(30f, y + 11f, 16f, 16f, 3f), Paint().apply { color = if (i == 0) p.primary else p.onVar; isAntiAlias = true })
                c.text(s, 60f, y + 24f, 14.5f, if (i == 0) p.navSelText else p.on, b = i == 0)
            }

        // Header
        c.text("Today, 21-10-2026", 272f, 58f, 24f, p.on, b = true)
        val bw = width("+  New job card", 14f, true) + 48f
        c.meshButton(W - 32f - bw, 30f, "+  New job card", p)

        // Stat cards; the first one uses the mesh gradient as the accent
        val stats = listOf("Sales today" to "Rs 102,608", "Cash in hand" to "Rs 91,521", "Customers owe" to "Rs 78,037", "Cars in workshop" to "8")
        val sw = (W - 272f - 32f - 3 * 16f) / 4
        stats.forEachIndexed { i, (k, v) ->
            val x = 272f + i * (sw + 16f); val y = 92f
            if (i == 0) {
                c.save(); c.translate(x, y)
                c.drawRRect(RRect.makeXYWH(0f, 0f, sw, 96f, 14f), Paint().apply { isAntiAlias = true
                    shader = shader(Shaders.BUTTON_MESH, sw, 96f) { col("c0", p.mesh[0]); col("c1", p.mesh[1]); col("c2", p.mesh[2]); col("c3", p.mesh[3]); col("c4", p.mesh[4]) } })
                c.drawRRect(RRect.makeXYWH(0.5f, 0.5f, sw - 1, 95f, 14f), Paint().apply { color = p.meshBorder; mode = PaintMode.STROKE; strokeWidth = 1f; isAntiAlias = true })
                c.restore()
                c.text(k, x + 18f, y + 32f, 13f, 0xEEFFFFFF.toInt()); c.text(v, x + 18f, y + 72f, 26f, 0xFFFFFFFF.toInt(), b = true)
            } else {
                c.card(x, y, sw, 96f, p, cardSrc, cardSetup)
                c.text(k, x + 18f, y + 32f, 13f, p.onVar); c.text(v, x + 18f, y + 72f, 26f, p.on, b = true)
            }
        }

        // Form card: inputs in each state and the buttons
        val fx = 272f; val fy = 212f; val fw = 560f; val fh = 556f
        c.card(fx, fy, fw, fh, p, cardSrc, cardSetup)
        c.text("New customer", fx + 24f, fy + 40f, 18f, p.on, b = true)
        c.input(fx + 24f, fy + 62f, fw - 48f, "Customer name", "Hamza Butt", "focused", p)
        c.input(fx + 24f, fy + 142f, fw - 48f, "Mobile number", "0301-44455", "error", p)
        c.input(fx + 24f, fy + 240f, (fw - 64f) / 2, "City", "", "normal", p, hint = "e.g. Lahore")
        c.input(fx + 40f + (fw - 64f) / 2, fy + 240f, (fw - 64f) / 2, "CNIC (optional)", "35202-1234567-1", "hover", p)
        c.input(fx + 24f, fy + 320f, fw - 48f, "Customer number", "C-00126", "readonly", p)
        c.text("Border colour shows the state:  light grey = normal  ·  darker grey = mouse over", fx + 24f, fy + 418f, 11.5f, p.onVar)
        c.text("red = typing here  ·  dark red + message = needs fixing  ·  shaded box = read-only", fx + 24f, fy + 436f, 11.5f, p.onVar)
        val sx = fx + fw - 24f - (width("Save customer", 14f, true) + 48f)
        c.meshButton(sx, fy + fh - 64f, "Save customer", p)
        c.outlinedButton(sx - 16f - (width("Cancel", 14f, true) + 40f), fy + fh - 64f, "Cancel", p)

        // List card
        val lx = fx + fw + 16f; val lw = W - 32f - lx
        c.card(lx, fy, lw, fh, p, cardSrc, cardSetup)
        c.text("Open job cards", lx + 24f, fy + 40f, 18f, p.on, b = true)
        val jobs = listOf(Triple("JC-2627-00320", "LEC-910 Toyota Corolla", "Ready" to p.green), Triple("JC-2627-00319", "RIM-776 Suzuki Wagon R", "Ready" to p.green),
            Triple("JC-2627-00318", "FDA-221 Suzuki Swift", "Quality check" to p.amber), Triple("JC-2627-00317", "LES-7810 Toyota Hiace", "In progress" to p.primary),
            Triple("JC-2627-00316", "ICT-5590 Suzuki Cultus", "Awaiting parts" to p.amber), Triple("JC-2627-00315", "LEC-4321 Toyota Fortuner", "Awaiting approval" to p.amber),
            Triple("JC-2627-00314", "LEE-1990 Honda City", "Inspecting" to p.primary), Triple("JC-2627-00313", "LED-2201 Toyota Yaris", "Received" to p.green))
        jobs.forEachIndexed { i, (no, car, st) ->
            val y = fy + 70f + i * 56f
            if (i > 0) c.drawRect(Rect.makeXYWH(lx + 24f, y - 10f, lw - 48f, 1f), Paint().apply { color = (p.outlineVar and 0x00FFFFFF) or 0x88000000.toInt() })
            c.text(no, lx + 24f, y + 14f, 13.5f, p.primary, b = true)
            c.text(car, lx + 24f, y + 34f, 13.5f, p.on)
            c.chip(lx + lw - 24f - (width(st.first, 11.5f) + 18f), y + 6f, st.first, st.second, p)
        }

        val png = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
        java.io.File("build/previews").mkdirs(); java.io.File("build/previews/$file.png").writeBytes(png)
    }

    @Test fun renderPreviews() {
        for ((bg, card, name) in listOf(Triple("forged", "carbon", "C-forged-carbon"), Triple("topo", "carbon", "D-topo-carbon"))) {
            render(light, bg, card, "$name-light"); render(dark, bg, card, "$name-dark")
        }
    }
}
