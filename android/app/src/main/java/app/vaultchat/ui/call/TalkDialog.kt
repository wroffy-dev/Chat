package app.vaultchat.ui.call

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vaultchat.R
import app.vaultchat.domain.call.CallRepository
import app.vaultchat.domain.call.TalkOutcome
import app.vaultchat.domain.call.TalkPhase
import app.vaultchat.domain.call.TalkSender
import app.vaultchat.domain.call.TalkState
import app.vaultchat.domain.permissions.AppPermission
import app.vaultchat.domain.permissions.PermissionStatus
import app.vaultchat.platform.call.LiveKitMediaSession
import app.vaultchat.ui.permissions.PermissionPreferences
import app.vaultchat.ui.permissions.rememberPermissionRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Sending a walkie-talkie message (owner decision D-26, 2026-11-12): reaches the other phone, then lets
 * the person speak for up to 10 s; the other phone plays it aloud. Live audio, nothing recorded.
 * [scope] outlives the dialog, so closing it still ends the message on the server.
 */
@Composable
fun TalkDialog(
    repo: CallRepository,
    scope: CoroutineScope,
    connectionId: String,
    peerUsername: String,
    permissionPreferences: PermissionPreferences,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val mic = rememberPermissionRequest(AppPermission.MICROPHONE, permissionPreferences)
    val allowed = mic.status == PermissionStatus.Granted
    LaunchedEffect(Unit) { if (!allowed) mic.request() }

    var sender by remember { mutableStateOf<TalkSender?>(null) }
    LaunchedEffect(allowed) {
        if (allowed && sender == null) {
            sender = TalkSender(repo, LiveKitMediaSession(context.applicationContext), scope).also { it.start(connectionId) }
        }
    }
    DisposableEffect(Unit) { onDispose { sender?.stop() } }

    val idle = remember { MutableStateFlow(TalkState()) }
    val state by (sender?.state ?: idle).collectAsStateWithLifecycle()
    // A sent message needs no tap to dismiss.
    LaunchedEffect(state.outcome) {
        if (state.outcome == TalkOutcome.SENT) {
            delay(1_200)
            onClose()
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.talk_title, peerUsername)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when {
                        !allowed -> stringResource(R.string.talk_needs_microphone)
                        state.phase == TalkPhase.CONNECTING -> stringResource(R.string.talk_connecting, peerUsername)
                        state.phase == TalkPhase.SPEAKING -> stringResource(R.string.talk_speak_now, state.secondsLeft)
                        state.outcome == TalkOutcome.SENT -> stringResource(R.string.talk_sent)
                        state.outcome == TalkOutcome.NOT_REACHED -> stringResource(R.string.talk_not_reached, peerUsername)
                        state.outcome == TalkOutcome.BUSY -> stringResource(R.string.talk_busy, peerUsername)
                        else -> stringResource(R.string.talk_failed)
                    },
                    style = if (allowed && state.phase == TalkPhase.SPEAKING) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge,
                )
                if (allowed && state.phase == TalkPhase.CONNECTING) {
                    Text(stringResource(R.string.talk_wait_hint), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            when {
                !allowed -> TextButton(onClick = mic::request) { Text(stringResource(R.string.talk_allow_microphone)) }
                state.phase == TalkPhase.DONE -> TextButton(onClick = onClose) { Text(stringResource(R.string.call_close)) }
                else -> TextButton(onClick = onClose) {
                    Text(stringResource(if (state.phase == TalkPhase.SPEAKING) R.string.talk_stop else R.string.action_cancel))
                }
            }
        },
    )
}
