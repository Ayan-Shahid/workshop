package pk.workshop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Red on white (light) and red on soft black (dark).
 * The reds are true reds; the dark-theme red is a touch brighter so it reads well on black.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFFD32F2F), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD6), onPrimaryContainer = Color(0xFF410002),
    secondary = Color(0xFF6E5E5C), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3E4E2), onSecondaryContainer = Color(0xFF261918),
    tertiary = Color(0xFF2E6B3A), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD4EED7), onTertiaryContainer = Color(0xFF0B2912),
    background = Color(0xFFF6F4F4), onBackground = Color(0xFF1C1B1E),
    surface = Color(0xFFFDFBFB), onSurface = Color(0xFF1C1B1E),
    surfaceVariant = Color(0xFFF0E8E7), onSurfaceVariant = Color(0xFF534746),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFAF6F6),
    surfaceContainer = Color(0xFFF5F0F0), surfaceContainerHigh = Color(0xFFEFE9E9), surfaceContainerHighest = Color(0xFFE9E2E2),
    outline = Color(0xFF857372), outlineVariant = Color(0xFFD8C2C0),
    error = Color(0xFF9F1D1D), onError = Color.White, errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE53935), onPrimary = Color.White,
    primaryContainer = Color(0xFF8C1D18), onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFFD9C1BE), onSecondary = Color(0xFF3B2C2A),
    secondaryContainer = Color(0xFF3A3133), onSecondaryContainer = Color(0xFFF3E4E2),
    tertiary = Color(0xFF8FD49B), onTertiary = Color(0xFF00390F),
    tertiaryContainer = Color(0xFF1F4F2A), onTertiaryContainer = Color(0xFFD4EED7),
    background = Color(0xFF141316), onBackground = Color(0xFFECE6E6),
    surface = Color(0xFF1C1B1E), onSurface = Color(0xFFECE6E6),
    surfaceVariant = Color(0xFF2A2729), onSurfaceVariant = Color(0xFFD2C3C1),
    surfaceContainerLowest = Color(0xFF121113), surfaceContainerLow = Color(0xFF1F1D20),
    surfaceContainer = Color(0xFF242225), surfaceContainerHigh = Color(0xFF2D2A2D), surfaceContainerHighest = Color(0xFF383537),
    outline = Color(0xFF9E8C8A), outlineVariant = Color(0xFF4F4445),
    error = Color(0xFFFF8A80), onError = Color(0xFF5F0000), errorContainer = Color(0xFF7F1D1D), onErrorContainer = Color(0xFFFFDAD6),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        bodyMedium = t.bodyMedium.copy(fontSize = 14.sp),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

val Money = TextStyle(fontWeight = FontWeight.Medium)

@Composable
fun AppTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = AppTypography, content = content)
}

/** Status colours used on chips. */
@Composable
fun statusColor(status: String): Color = when (status) {
    "Ready", "Delivered", "Paid", "Approved", "Cleared", "Received", "Replaced", "Credited", "Done" -> MaterialTheme.colorScheme.tertiary
    "Cancelled", "Bounced", "Rejected", "Overdue" -> MaterialTheme.colorScheme.error
    "Awaiting approval", "Awaiting parts", "Quality check", "In hand", "Open", "Sent", "Booked", "Due" -> Color(0xFFE08A00)
    else -> MaterialTheme.colorScheme.primary
}
