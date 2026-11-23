package app.vaultchat.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The owner's six-shade green palette (2026-11-19; owner 2026-11-23: "ab vhi green color palate use krdo"), darkest
// first. The light Material look keeps its layout; only its colours come from these greens.
object VaultGreen {
    val Deepest = Color(0xFF051F20)
    val Deep = Color(0xFF0B2B26)
    val Forest = Color(0xFF163832)
    val Pine = Color(0xFF235347)
    val Sage = Color(0xFF8EB69B)
    val Mint = Color(0xFFDAF1DE)
}

/** The always-light Material 3 scheme in the owner's greens (fixed: no wallpaper colours). */
private val LightColors = lightColorScheme(
    primary = VaultGreen.Pine,
    onPrimary = Color.White,
    primaryContainer = VaultGreen.Mint,
    onPrimaryContainer = VaultGreen.Deepest,
    secondary = VaultGreen.Forest,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC9E2CF),
    onSecondaryContainer = VaultGreen.Deepest,
    tertiary = Color(0xFF75546F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD7F5),
    onTertiaryContainer = Color(0xFF2C1229),
    background = Color(0xFFF4FAF5),
    onBackground = VaultGreen.Deepest,
    surface = Color(0xFFF4FAF5),
    onSurface = VaultGreen.Deepest,
    surfaceVariant = Color(0xFFDCE8DF),
    onSurfaceVariant = Color(0xFF41544A),
    outline = Color(0xFF6F8A78),
    outlineVariant = Color(0xFFC2D3C6),
    surfaceDim = Color(0xFFD3DDD5),
    surfaceBright = Color(0xFFF4FAF5),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFEEF5EF),
    surfaceContainer = Color(0xFFE8F0EA),
    surfaceContainerHigh = Color(0xFFE2EBE4),
    surfaceContainerHighest = Color(0xFFDCE6DE),
    inverseSurface = VaultGreen.Forest,
    inverseOnSurface = VaultGreen.Mint,
    inversePrimary = VaultGreen.Sage,
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
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
    // Always light, in the owner's greens, whatever the phone's setting or wallpaper (owner 2026-11-23).
    MaterialTheme(colorScheme = LightColors, typography = AppTypography, shapes = AppShapes, content = content)
}
