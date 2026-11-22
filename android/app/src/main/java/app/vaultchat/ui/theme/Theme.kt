package app.vaultchat.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

    /** Premium finishes (owner 2026-11-22: "luxury … premium"). */
    val MintLight = Color(0xFF9DECC9)
    val MintDeep = Color(0xFF63D3A2)
    val Silver = Color(0xFFC9D0D4)
}

/** Mint with depth: lighter at the top, deeper at the bottom, for my bubbles, the send button and badges. */
val MintGradient = Brush.verticalGradient(0f to Glass.MintLight, 1f to Glass.MintDeep)

/** Brushed silver for large titles: white at the top fading to silver. */
val SilverText = Brush.verticalGradient(0f to Color.White, 1f to Glass.Silver)

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
    outlineVariant = Color(0xFF2A2E32),
    // Material's own surfaces (dialogs, menus, sheets, pickers) default to a purple-grey; these keep them in the
    // app's neutral glass greys, and elevation lightens them with white instead of tinting them mint.
    surfaceTint = Color.White,
    surfaceDim = Glass.Ground,
    surfaceBright = Color(0xFF2B2F33),
    surfaceContainerLowest = Color(0xFF0E0F11),
    surfaceContainerLow = Color(0xFF141618),
    surfaceContainer = Color(0xFF181B1D),
    surfaceContainerHigh = Color(0xFF1D2023),
    surfaceContainerHighest = Color(0xFF24282B),
    inverseSurface = Color(0xFFE8ECEE),
    inverseOnSurface = Color(0xFF16191B),
    inversePrimary = Color(0xFF0C7F57),
    scrim = Color.Black,
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
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
    // Always dark, whatever the phone's setting (owner 2026-11-22).
    MaterialTheme(
        colorScheme = DarkColors,
        typography = AppTypography,
        shapes = AppShapes,
    ) {
        CompositionLocalProvider(LocalGlass provides DarkGlass, content = content)
    }
}
