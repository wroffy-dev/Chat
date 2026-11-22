package app.vaultchat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Fixed colours for the always-dark screens (camera, snap viewer, calls) and the avatar initials. The green
 * palette of 2026-11-19 was replaced by the Liquid Glass look (owner 2026-11-22): near-black glass greys with
 * one bright green accent. The names keep their old roles: Deepest is the darkest ground, Mint the text on
 * it, Sage the accent.
 */
object VaultGreen {
    val Deepest = Color(0xFF0C0C0F)
    val Deep = Color(0xFF18191D)
    val Forest = Color(0xFF26282D)
    val Pine = Color(0xFF3A3D44)
    val Sage = Color(0xFF3DDC97)
    val Mint = Color(0xFFEDEFF1)
}

/** The Liquid Glass colours of the mockup (owner 2026-11-22). */
object Glass {
    val Green = Color(0xFF0C7F57)
    val GreenBright = Color(0xFF3DDC97)
    val Ground = Color(0xFFF2F2F5)
    val GroundDark = Color(0xFF0C0C0F)
    val Ink = Color(0xFF12161A)
    val InkDark = Color(0xFFEDEFF1)
}

private val LightColors = lightColorScheme(
    primary = Glass.Green,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEFE0),
    onPrimaryContainer = Color(0xFF05301F),
    secondary = Color(0xFF0B7650),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCEFE6),
    onSecondaryContainer = Color(0xFF05301F),
    background = Glass.Ground,
    onBackground = Glass.Ink,
    // Cards, bubbles and bars are white over the light grey screen, so text keeps its contrast.
    surface = Color.White,
    onSurface = Glass.Ink,
    surfaceVariant = Color(0xFFE6E7EC),
    onSurfaceVariant = Color(0xFF575E66),
    outline = Color(0xFFC5C9D0),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkColors = darkColorScheme(
    primary = Glass.GreenBright,
    onPrimary = Color(0xFF04150D),
    primaryContainer = Color(0xFF0E4D35),
    onPrimaryContainer = Color(0xFFCFF5E3),
    secondary = Color(0xFF6FD9AE),
    onSecondary = Color(0xFF04150D),
    secondaryContainer = Color(0xFF173A2C),
    onSecondaryContainer = Color(0xFFCFF5E3),
    background = Glass.GroundDark,
    onBackground = Glass.InkDark,
    surface = Color(0xFF1A1B1F),
    onSurface = Glass.InkDark,
    surfaceVariant = Color(0xFF26282D),
    onSurfaceVariant = Color(0xFFA6ACB3),
    outline = Color(0xFF3E4148),
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
    ) {
        CompositionLocalProvider(LocalGlass provides if (darkTheme) DarkGlass else LightGlass, content = content)
    }
}
