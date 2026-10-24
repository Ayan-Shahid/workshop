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
}
