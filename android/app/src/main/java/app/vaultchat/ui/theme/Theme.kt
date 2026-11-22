package app.vaultchat.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Fixed colours for the camera, snap viewer and call screens. The green palette of 2026-11-19 was replaced by
 * the dark glass look (owner 2026-11-22): near-black greys with one mint accent. The names keep their old
 * roles: Deepest is the darkest ground, Mint the text on it, Sage the accent.
 */
object VaultGreen {
    val Deepest = Color(0xFF0B0B0D)
    val Deep = Color(0xFF16191B)
    val Forest = Color(0xFF24282B)
    val Pine = Color(0xFF3A3F43)
    val Sage = Color(0xFF7FE0B5)
    val Mint = Color(0xFFF4F6F7)
}

/**
 * The dark glass colours of the owner's mockup (2026-11-22: "Dark hi rhegi ab app"): a near-black screen with a
 * faint teal light at the top and a faint warm light at the bottom, glass panels, light grey text and a mint
 * accent. The app no longer has a light mode.
 */
object Glass {
    val Ground = Color(0xFF0B0B0D)
    val TealLight = Color(0xFF1D2A2B)
    val WarmLight = Color(0xFF1F1A16)
    val Mint = Color(0xFF7FE0B5)
    val OnMint = Color(0xFF06140E)
    val Ink = Color(0xFFF4F6F7)
    val InkSoft = Color(0xFFA3ACB1)
}

private val DarkColors = darkColorScheme(
    primary = Glass.Mint,
    onPrimary = Glass.OnMint,
    primaryContainer = Color(0xFF1E3A30),
    onPrimaryContainer = Color(0xFFCFF5E3),
    secondary = Color(0xFF9FD9C2),
    onSecondary = Glass.OnMint,
    secondaryContainer = Color(0xFF1E2F2A),
    onSecondaryContainer = Color(0xFFCFF5E3),
    background = Glass.Ground,
    onBackground = Glass.Ink,
    // Dialogs, menus and sheets: a dark grey that matches the glass over the near-black screen.
    surface = Color(0xFF1A1D1F),
    onSurface = Glass.Ink,
    surfaceVariant = Color(0xFF24282B),
    onSurfaceVariant = Glass.InkSoft,
    outline = Color(0xFF3A3F43),
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
fun VaultChatTheme(content: @Composable () -> Unit) {
    // Always dark, whatever the phone's setting (owner 2026-11-22).
    MaterialTheme(
        colorScheme = DarkColors,
        typography = AppTypography,
        shapes = AppShapes,
    ) {
        CompositionLocalProvider(LocalGlass provides DarkGlass, content = content)
    }
}
