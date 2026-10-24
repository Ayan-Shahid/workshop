package pk.workshop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The carbon textures: forged carbon behind everything, woven carbon on cards, dialogs and the sidebar.
 * They are pre-rendered images shipped with the app (see TextureGen), so drawing them costs about the same as a flat colour.
 */
object Textures {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, ImageBitmap>()
    private fun load(name: String): ImageBitmap? = cache[name] ?: try {
        Textures::class.java.getResourceAsStream("/textures/$name")?.use { org.jetbrains.skia.Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }
            ?.also { cache[name] = it }
    } catch (_: Throwable) { null }

    fun forged(dark: Boolean) = load(if (dark) "forged-dark.jpg" else "forged-light.jpg")
    fun carbon(dark: Boolean) = load(if (dark) "carbon-dark.png" else "carbon-light.png")
}

@Composable
fun isDarkTheme() = MaterialTheme.colorScheme.background.luminance() < 0.4f

/** Forged carbon behind the whole window (scaled to cover it), with a soft glow of the primary colour from the top-right corner. */
fun Modifier.carbonBackground(): Modifier = composed {
    val dark = isDarkTheme()
    val fallback = MaterialTheme.colorScheme.background
    val glow = MaterialTheme.colorScheme.primary.copy(alpha = if (dark) 0.15f else 0.08f)
    val img = remember(dark) { Textures.forged(dark) }
    drawBehind {
        drawRect(fallback)
        if (img != null) {
            val k = max(size.width / img.width, size.height / img.height)
            drawImage(img, dstSize = IntSize((img.width * k).roundToInt() + 1, (img.height * k).roundToInt() + 1), filterQuality = FilterQuality.Medium)
        }
        val radius = max(size.width, size.height) * 0.95f
        drawRect(Brush.radialGradient(listOf(glow, Color.Transparent), center = Offset(size.width * 1.02f, -size.height * 0.15f), radius = radius))
    }
}

/** Woven carbon card surface (a seamless tile, repeated) with a flat 1 dp border (no shadows). */
fun Modifier.carbonCard(shape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), border: Boolean = true): Modifier = composed {
    val dark = isDarkTheme()
    val fallback = MaterialTheme.colorScheme.surface
    val line = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (dark) 0.9f else 0.7f)
    val tile = remember(dark) { Textures.carbon(dark)?.let { ShaderBrush(ImageShader(it, TileMode.Repeated, TileMode.Repeated)) } }
    this.clip(shape)
        .drawBehind {
            drawRect(tile ?: SolidColor(fallback))
            // A faint diagonal sheen across the panel, like light on a lacquered carbon part (dark theme only)
            if (dark) drawRect(Brush.linearGradient(0f to Color.Transparent, 0.45f to Color.White.copy(alpha = 0.035f), 0.6f to Color.Transparent,
                start = Offset.Zero, end = Offset(size.width, size.height)))
        }
        .let { if (border) it.border(BorderStroke(1.dp, line), shape) else it }
}

// ---------------- mesh gradient (primary shades only) ----------------

/**
 * Mesh gradient from five shades of the primary red (no other hues), built from soft radial blends over the base red.
 * Shades are scattered (light bottom-left, deepest top-right) so it never reads as a lit, raised 3D button.
 */
fun DrawScope.drawMesh(dark: Boolean) {
    val c0 = if (dark) Color(0xFFFF5F57) else Color(0xFFEB4F49)   // lightest
    val c1 = if (dark) Color(0xFFE53935) else Color(0xFFD32F2F)   // primary
    val c2 = if (dark) Color(0xFFC62828) else Color(0xFFB42222)   // deeper
    val c3 = if (dark) Color(0xFF9C1A1A) else Color(0xFF8E1515)   // deepest
    val c4 = if (dark) Color(0xFFEF4640) else Color(0xFFDE3A35)   // bright primary
    val w = size.width; val h = size.height; val r = max(w, h)
    fun blob(c: Color, x: Float, y: Float, rad: Float, a: Float = 1f) =
        drawRect(Brush.radialGradient(listOf(c.copy(alpha = a), c.copy(alpha = 0f)), center = Offset(x, y), radius = rad))
    drawRect(c1)
    blob(c3, w, 0f, r * 0.62f)
    blob(c2, w, h, r * 0.45f, 0.9f)
    blob(c0, w * 0.04f, h, r * 0.55f)
    blob(c4, w * 0.48f, h * 0.45f, r * 0.28f, 0.7f)
}

fun meshBorderColor(dark: Boolean) = if (dark) Color(0xFF8A1717) else Color(0xFF8E1515)

/** Fills with the primary mesh gradient and a simple 1 dp border. */
fun Modifier.meshFill(shape: Shape): Modifier = composed {
    val dark = isDarkTheme()
    this.clip(shape)
        .drawBehind { drawMesh(dark) }
        .border(1.dp, meshBorderColor(dark), shape)
}
