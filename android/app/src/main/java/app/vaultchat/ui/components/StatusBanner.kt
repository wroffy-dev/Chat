package app.vaultchat.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

enum class BannerKind { INFO, SUCCESS, WARNING, ERROR }

/** Inline status message. Announced by TalkBack when it changes (polite live region). */
@Composable
fun StatusBanner(
    kind: BannerKind,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (kind) {
        BannerKind.ERROR -> colors.errorContainer to colors.onErrorContainer
        BannerKind.WARNING -> colors.surfaceVariant to colors.onSurfaceVariant
        BannerKind.SUCCESS, BannerKind.INFO -> colors.primaryContainer to colors.onPrimaryContainer
    }
    val icon = when (kind) {
        BannerKind.SUCCESS -> Icons.Filled.CheckCircle
        BannerKind.INFO -> Icons.Filled.Info
        BannerKind.WARNING, BannerKind.ERROR -> Icons.Filled.Warning
    }
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Decorative: the text carries the meaning, so colour is never the only signal.
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = content)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                if (body != null) Text(body, style = MaterialTheme.typography.bodySmall)
            }
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}
