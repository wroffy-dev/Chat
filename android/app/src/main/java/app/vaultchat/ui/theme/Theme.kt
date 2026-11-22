package app.vaultchat.ui.theme

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Fixed colours for the always-dark media screens (camera, snap viewer, calls). The names keep their old roles:
 * Deepest is the darkest ground, Mint the text on it, Sage the accent.
 */
object VaultGreen {
    val Deepest = Color(0xFF111316)
    val Deep = Color(0xFF1C1F23)
    val Forest = Color(0xFF2B2E33)
    val Pine = Color(0xFF44474E)
    val Sage = Color(0xFFB4C5FF)
    val Mint = Color(0xFFF1F0F7)
}

/**
 * The light Material 3 look (owner 2026-11-22: "google msg jaisa look", "dark app ko bhi light krdo"). On Android 12
 * and later the colours come from the phone's wallpaper (Material You), as in Google's own apps; otherwise this
 * calm blue scheme is used. The app is always light.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF3557C6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE1FF),
    onPrimaryContainer = Color(0xFF00174B),
    secondary = Color(0xFF595D72),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE1F9),
    onSecondaryContainer = Color(0xFF161B2C),
    tertiary = Color(0xFF75546F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD7F5),
    onTertiaryContainer = Color(0xFF2C1229),
    background = Color(0xFFF8F9FF),
    onBackground = Color(0xFF1A1B21),
    surface = Color(0xFFF8F9FF),
    onSurface = Color(0xFF1A1B21),
    surfaceVariant = Color(0xFFE2E2EC),
    onSurfaceVariant = Color(0xFF45464F),
    outline = Color(0xFF767680),
    outlineVariant = Color(0xFFC6C6D0),
    surfaceDim = Color(0xFFD9D9E0),
    surfaceBright = Color(0xFFF8F9FF),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF2F3FA),
    surfaceContainer = Color(0xFFECEDF4),
    surfaceContainerHigh = Color(0xFFE7E8EE),
    surfaceContainerHighest = Color(0xFFE1E2E8),
    inverseSurface = Color(0xFF2F3036),
    inverseOnSurface = Color(0xFFF1F0F7),
    inversePrimary = Color(0xFFB6C4FF),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

/**
 * Typography with a calm, premium hierarchy (owner 2026-11-22): large text tight and heavy, body text airy,
 * small labels tracked a little wider, and times in tabular figures so they do not jump.
 */
private val AppTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.2).sp),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = (-0.1).sp),
        bodyLarge = base.bodyLarge.copy(lineHeight = 23.sp, letterSpacing = 0.1.sp),
        bodyMedium = base.bodyMedium.copy(lineHeight = 20.sp, letterSpacing = 0.1.sp),
        bodySmall = base.bodySmall.copy(letterSpacing = 0.2.sp, fontFeatureSettings = "tnum"),
        labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
        labelMedium = base.labelMedium.copy(letterSpacing = 0.4.sp, fontFeatureSettings = "tnum"),
        labelSmall = base.labelSmall.copy(letterSpacing = 0.4.sp, fontFeatureSettings = "tnum"),
    )
}

// One set of corner radii for the whole app (docs/ui-upgrade); screens use MaterialTheme.shapes or Radii.
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(Radii.sm),
    small = RoundedCornerShape(Radii.sm),
    medium = RoundedCornerShape(Radii.md),
    large = RoundedCornerShape(Radii.lg),
    // Dialogs and sheets: soft, generous corners.
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun VaultChatTheme(content: @Composable () -> Unit) {
    // Always light, whatever the phone's setting (owner 2026-11-22).
    val context = LocalContext.current
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicLightColorScheme(context) else LightColors
    MaterialTheme(colorScheme = colors, typography = AppTypography, shapes = AppShapes, content = content)
}
