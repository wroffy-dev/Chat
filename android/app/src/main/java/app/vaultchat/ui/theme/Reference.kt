package app.vaultchat.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Colours of the owner's reference screens (chat_conversation.html, message_expiry_settings.html;
 * 2026-11-20), mapped onto the app's own green theme (owner, 2026-11-20: "core app ke green UI mein"). The
 * layouts follow the references; the colours come from [VaultChatTheme], so light and dark mode both work.
 */
object Ref {
    val Primary: Color @Composable get() = MaterialTheme.colorScheme.primary
    val OnPrimary: Color @Composable get() = MaterialTheme.colorScheme.onPrimary
    val PrimaryContainer: Color @Composable get() = MaterialTheme.colorScheme.primaryContainer
    val OnPrimaryContainer: Color @Composable get() = MaterialTheme.colorScheme.onPrimaryContainer

    /** The screen itself: mint in light mode, the darkest green in dark mode. */
    val Surface: Color @Composable get() = MaterialTheme.colorScheme.background
    val OnSurface: Color @Composable get() = MaterialTheme.colorScheme.onBackground
    val OnSurfaceVariant: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

    /** Panels, received bubbles and inputs: near-white on mint, dark green on the darkest green. */
    val ContainerLow: Color @Composable get() = MaterialTheme.colorScheme.surface
    val Container: Color @Composable get() = MaterialTheme.colorScheme.surface
    val ContainerHigh: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant
    val Outline: Color @Composable get() = MaterialTheme.colorScheme.outline
    val OutlineVariant: Color @Composable get() = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)

    /** The reference's teal accent (privacy strip, typing). */
    val Teal: Color @Composable get() = MaterialTheme.colorScheme.secondary
    val Error: Color @Composable get() = MaterialTheme.colorScheme.error
    val ErrorContainer: Color @Composable get() = MaterialTheme.colorScheme.errorContainer
    val OnErrorContainer: Color @Composable get() = MaterialTheme.colorScheme.onErrorContainer
}
