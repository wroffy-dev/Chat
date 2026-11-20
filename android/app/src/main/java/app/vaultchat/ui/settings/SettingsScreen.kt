package app.vaultchat.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vaultchat.BuildConfig
import app.vaultchat.R
import app.vaultchat.ui.components.AppIcons
import app.vaultchat.ui.components.InitialsAvatar
import app.vaultchat.ui.components.SectionHeader
import app.vaultchat.ui.components.SettingsDivider
import app.vaultchat.ui.components.SettingsGroup
import app.vaultchat.ui.components.SettingsRow
import app.vaultchat.ui.components.TopBar
import app.vaultchat.ui.permissions.openAppSettings
import app.vaultchat.ui.theme.Sizes
import app.vaultchat.ui.theme.Spacing

/**
 * Settings (UI upgrade, 2026-11-20). Only real, working rows: nothing here pretends to change behaviour the
 * app does not have. Notification and permission rows open Android's own pages for Vault. There is no
 * sign-out and no location switch (the owner removed both on 2026-11-02, D-06).
 */
@Composable
fun SettingsScreen(
    username: String,
    onBack: () -> Unit,
    onChangePassword: () -> Unit,
) {
    val context = LocalContext.current
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            TopBar(stringResource(R.string.settings_title), onBack = onBack)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = Spacing.xxxl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(modifier = Modifier.widthIn(max = Sizes.contentMaxWidth)) {
                    // Account: who is signed in on this phone. No profile, photo or bio by design (docs/01).
                    SectionHeader(stringResource(R.string.settings_account))
                    SettingsGroup {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                        ) {
                            InitialsAvatar(username, size = 52.dp)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(username, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    stringResource(R.string.settings_account_note),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    SectionHeader(stringResource(R.string.settings_privacy))
                    SettingsGroup {
                        SettingsRow(
                            title = stringResource(R.string.settings_change_password),
                            icon = Icons.Filled.Lock,
                            onClick = onChangePassword,
                            trailing = { Chevron() },
                        )
                        SettingsDivider()
                        SettingsRow(
                            title = stringResource(R.string.settings_screen_protection),
                            description = stringResource(R.string.settings_screen_protection_body),
                            icon = AppIcons.VideocamOff,
                        )
                        SettingsDivider()
                        SettingsRow(
                            title = stringResource(R.string.settings_messages),
                            description = stringResource(R.string.chat_deletion_rule),
                            icon = Icons.Filled.Info,
                        )
                        SettingsDivider()
                        SettingsRow(
                            title = stringResource(R.string.settings_location),
                            description = stringResource(R.string.settings_location_body),
                            icon = Icons.Filled.LocationOn,
                            onClick = { openAppSettings(context) },
                            trailing = { Chevron() },
                        )
                    }

                    SectionHeader(stringResource(R.string.settings_notifications))
                    SettingsGroup {
                        SettingsRow(
                            title = stringResource(R.string.settings_notifications_system),
                            description = stringResource(R.string.settings_notifications_system_body),
                            icon = Icons.Filled.Notifications,
                            onClick = { openNotificationSettings(context) },
                            trailing = { Chevron() },
                        )
                        SettingsDivider()
                        SettingsRow(
                            title = stringResource(R.string.settings_full_screen_calls),
                            description = stringResource(R.string.settings_full_screen_calls_body),
                            icon = Icons.Filled.Phone,
                            onClick = { open(context, Settings.ACTION_MANAGE_OVERLAY_PERMISSION) },
                            trailing = { Chevron() },
                        )
                    }

                    SectionHeader(stringResource(R.string.settings_appearance))
                    SettingsGroup {
                        SettingsRow(
                            title = stringResource(R.string.settings_theme),
                            description = stringResource(R.string.settings_theme_body),
                            icon = Icons.Filled.Star,
                        )
                    }

                    SectionHeader(stringResource(R.string.settings_about))
                    SettingsGroup {
                        SettingsRow(
                            title = stringResource(R.string.settings_version),
                            icon = Icons.Filled.Info,
                            trailing = {
                                Text(
                                    "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                        SettingsDivider()
                        SettingsRow(
                            title = stringResource(R.string.settings_about_vault),
                            description = stringResource(R.string.settings_about_vault_body),
                        )
                    }
                    Spacer(Modifier.height(Spacing.lg))
                }
            }
        }
    }
}

@Composable
private fun Chevron() {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(Sizes.icon),
    )
}

/** Android's notification page for Vault (calls, messages, snaps); the app has no switches of its own. */
private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        openAppSettings(context)
    }
}

private fun open(context: Context, action: String) {
    try {
        context.startActivity(Intent(action, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        openAppSettings(context)
    }
}
