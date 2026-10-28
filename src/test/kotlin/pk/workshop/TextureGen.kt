package pk.workshop

import org.jetbrains.skia.*
import pk.workshop.ui.Shaders
import kotlin.test.Test

/**
 * Renders the carbon texture images shipped in src/main/resources/textures. Run only when the look changes:
 *   ./gradlew test --tests pk.workshop.TextureGen -Pregen
 * The app itself never runs these shaders; it just draws the images.
 */
class TextureGen {
    private fun RuntimeShaderBuilder.col(n: String, argb: Long) = uniform(n, ((argb shr 16) and 255) / 255f, ((argb shr 8) and 255) / 255f, (argb and 255) / 255f, 1f)

    private fun render(src: String, w: Int, h: Int, setup: RuntimeShaderBuilder.() -> Unit): Image {
        val b = RuntimeShaderBuilder(RuntimeEffect.makeForShader(src)); b.uniform("size", w.toFloat(), h.toFloat()); b.setup()
        val s = Surface.makeRasterN32Premul(w, h)
        s.canvas.drawRect(Rect.makeWH(w.toFloat(), h.toFloat()), Paint().apply { shader = b.makeShader() })
        return s.makeImageSnapshot()
    }
    private fun renderTile(w: Int, setup: RuntimeShaderBuilder.() -> Unit): Image {
        val b = RuntimeShaderBuilder(RuntimeEffect.makeForShader(Shaders.CARD_CARBON_TILE)); b.setup()
        val s = Surface.makeRasterN32Premul(w, w)
        s.canvas.drawRect(Rect.makeWH(w.toFloat(), w.toFloat()), Paint().apply { shader = b.makeShader() })
        return s.makeImageSnapshot()
    }

    @Test fun generate() {
        if (System.getProperty("regen") != "1" && java.io.File("src/main/resources/textures/forged-dark.jpg").exists()) return
        val out = java.io.File("src/main/resources/textures").apply { mkdirs() }
        // Forged carbon: one large image per theme, drawn scaled to cover the window
        for (dark in listOf(true, false)) {
            val img = render(Shaders.BG_FORGED, 2560, 1600) {
                if (dark) { col("resin", 0xFF08080A); col("flakeLo", 0xFF0F0E11); col("flakeHi", 0xFF3A383D) }
                else { col("resin", 0xFFE2DDDD); col("flakeLo", 0xFFEAE6E6); col("flakeHi", 0xFFFFFFFF) }
                col("ink", 0xFFD32F2F); uniform("glowAlpha", 0f); uniform("scale", 64f)
            }
            java.io.File(out, "forged-${if (dark) "dark" else "light"}.jpg").writeBytes(img.encodeToData(EncodedImageFormat.JPEG, 92)!!.bytes)
        }
        // Woven carbon: seamless 256 px tile per theme, repeated across cards
        for (dark in listOf(true, false)) {
            val img = renderTile(256) {
                if (dark) { col("base", 0xFF1E1D20); col("lo", 0xFF1A191C); col("hi", 0xFF2E2C30) }
                else { col("base", 0xFFFBF9F9); col("lo", 0xFFF3F0F0); col("hi", 0xFFFFFFFF) }
                uniform("tow", 8f); uniform("streak", 32f); uniform("tile", 256f); uniform("amount", 1f)
            }
            java.io.File(out, "carbon-${if (dark) "dark" else "light"}.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }

    /**
     * Primary-button textures (WebP): a smooth mesh of primary reds per theme, stretched to each button,
     * and one seamless, transparent sandpaper grain tile repeated at its real size on top.
     */
    @Test fun generateButtons() {
        val out = java.io.File("src/main/resources/textures").apply { mkdirs() }
        if (System.getProperty("regen") != "1" && java.io.File(out, "grain.webp").exists()) return
        for (dark in listOf(true, false)) {
            val img = render(Shaders.BUTTON_MESH, 640, 160) {
                if (dark) { col("c0", 0xFFFF5F57); col("c1", 0xFFE53935); col("c2", 0xFFC62828); col("c3", 0xFF9C1A1A); col("c4", 0xFFEF4640) }
                else { col("c0", 0xFFEB4F49); col("c1", 0xFFD32F2F); col("c2", 0xFFB42222); col("c3", 0xFF8E1515); col("c4", 0xFFDE3A35) }
            }
            java.io.File(out, "mesh-${if (dark) "dark" else "light"}.webp").writeBytes(img.encodeToData(EncodedImageFormat.WEBP, 95)!!.bytes)
        }
        // Sandpaper: fine per-pixel grit (light and dark), plus sparse coarser specks; wraps at the edges so it tiles seamlessly
        val n = 160
        val rnd = java.util.Random(7)
        val s = Surface.makeRasterN32Premul(n, n)
        val c = s.canvas
        c.clear(0x00000000)
        val p = Paint().apply { isAntiAlias = false }
        fun dot(x: Int, y: Int, light: Boolean, a: Float) {
            p.color = ((a.coerceIn(0f, 1f) * 255).toInt() shl 24) or (if (light) 0xFFFFFF else 0x000000)
            c.drawRect(Rect.makeXYWH(((x % n + n) % n).toFloat(), ((y % n + n) % n).toFloat(), 1f, 1f), p)
        }
        for (y in 0 until n) for (x in 0 until n) {
            val v = rnd.nextGaussian().toFloat()
            if (v > 0) dot(x, y, true, v * 0.07f) else dot(x, y, false, -v * 0.10f)
        }
        repeat(n * n * 3 / 100) {
            val x = rnd.nextInt(n); val y = rnd.nextInt(n); val light = rnd.nextFloat() < 0.45f; val a = 0.18f + rnd.nextFloat() * 0.14f
            dot(x, y, light, a); if (rnd.nextBoolean()) dot(x + 1, y, light, a * 0.7f); if (rnd.nextBoolean()) dot(x, y + 1, light, a * 0.6f)
        }
        java.io.File(out, "grain.webp").writeBytes(s.makeImageSnapshot().encodeToData(EncodedImageFormat.WEBP, 100)!!.bytes)
    }
}
