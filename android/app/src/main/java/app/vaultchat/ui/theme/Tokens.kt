package app.vaultchat.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Vault's design tokens (UI upgrade, 2026-11-20; docs/ui-upgrade). Screens use these instead of hard-coded
 * numbers so spacing, corners, sizes and motion stay consistent everywhere.
 */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp

    /** Side margin of screen content. */
    val screen = 16.dp
}

object Radii {
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp

    /** Message bubbles: rounded but not cartoonish; the corner next to the sender tightens inside a run. */
    val bubble = 18.dp
    val bubbleTight = 6.dp
    val sheet = 24.dp

    /** Glass cards and chat rows (Liquid Glass, owner 2026-11-22). */
    val card = 24.dp
    val input = 22.dp
}

object Sizes {
    /** Smallest touch target (Material and Android accessibility guidance). */
    val touch = 48.dp
    val iconSm = 18.dp
    val icon = 24.dp
    val avatarRow = 52.dp
    val avatarHeader = 38.dp
    val avatarCall = 136.dp
    val headerAction = 44.dp

    /** Readable line length on tablets and in landscape. */
    val contentMaxWidth = 640.dp
}

/** Animation durations in milliseconds: short, so the app feels quick and does not waste battery. */
object Motion {
    const val FAST = 150
    const val MEDIUM = 220
    const val SLOW = 320
}
