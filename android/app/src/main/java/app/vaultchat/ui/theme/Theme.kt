package app.vaultchat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Green palette chosen by the owner on 2026-11-19 (docs/15-android-ui-ux.md §1). Six shades, darkest first.
object VaultGreen {
    val Deepest = Color(0xFF051F20)
    val Deep = Color(0xFF0B2B26)
    val Forest = Color(0xFF163832)
    val Pine = Color(0xFF235347)
    val Sage = Color(0xFF8EB69B)
    val Mint = Color(0xFFDAF1DE)
}

private val LightColors = lightColorScheme(
    primary = VaultGreen.Forest,
    onPrimary = VaultGreen.Mint,
    primaryContainer = VaultGreen.Sage,
    onPrimaryContainer = VaultGreen.Deepest,
    secondary = VaultGreen.Pine,
    onSecondary = VaultGreen.Mint,
    secondaryContainer = VaultGreen.Sage,
    onSecondaryContainer = VaultGreen.Deepest,
    background = VaultGreen.Mint,
    onBackground = VaultGreen.Deepest,
    // Cards, bubbles and bars stay near-white over the mint background, so text keeps its contrast.
    surface = Color(0xFFF6FBF7),
    onSurface = VaultGreen.Deepest,
    surfaceVariant = Color(0xFFC3DCC9),
    onSurfaceVariant = VaultGreen.Pine,
    outline = VaultGreen.Sage,
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkColors = darkColorScheme(
    primary = VaultGreen.Sage,
    onPrimary = VaultGreen.Deepest,
    primaryContainer = VaultGreen.Pine,
    onPrimaryContainer = VaultGreen.Mint,
    secondary = VaultGreen.Sage,
    onSecondary = VaultGreen.Deepest,
    secondaryContainer = VaultGreen.Pine,
    onSecondaryContainer = VaultGreen.Mint,
    background = VaultGreen.Deepest,
    onBackground = VaultGreen.Mint,
    surface = VaultGreen.Deep,
    onSurface = VaultGreen.Mint,
    surfaceVariant = VaultGreen.Forest,
    onSurfaceVariant = VaultGreen.Sage,
    outline = VaultGreen.Pine,
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

private val AppTypography = Typography().let { base ->
    base.copy(
        // Calm hierarchy: one bold title size per screen, medium-weight names, readable body text.
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
        bodyLarge = base.bodyLarge.copy(lineHeight = 22.sp, letterSpacing = 0.15.sp),
        labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
    )
}

// One set of corner radii for the whole app (docs/ui-upgrade); screens use MaterialTheme.shapes or Radii.
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(Radii.sm),
    small = RoundedCornerShape(Radii.sm),
    medium = RoundedCornerShape(Radii.md),
    large = RoundedCornerShape(Radii.lg),
    extraLarge = RoundedCornerShape(Radii.sheet),
)

@Composable
fun VaultChatTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
