package app.vaultchat.platform.call

import android.content.Context
import android.net.Uri
import android.telecom.DisconnectCause
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import app.vaultchat.domain.call.CallEndReason
import app.vaultchat.domain.call.SystemCallActions
import app.vaultchat.domain.call.SystemCallCommand
import app.vaultchat.domain.call.SystemCallInfo
import app.vaultchat.domain.call.SystemCalls
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Self-managed calls through androidx core-telecom (docs/08-calling.md §10). Android draws no call
 * screen for these: the app keeps its own. What it adds is that Bluetooth headsets, cars and watches
 * can answer and end the call, other call apps know a call is in progress, and the system routes the
 * audio. Only used when the build sets `vaultchat.systemCalls=true`; **not yet tested on a phone**.
 */
class TelecomSystemCalls(context: Context) : SystemCalls {
    private val manager = CallsManager(context.applicationContext)
    private var registered = false

    override suspend fun run(info: SystemCallInfo, commands: ReceiveChannel<SystemCallCommand>, actions: SystemCallActions) {
        if (!registered) {
            manager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE or CallsManager.CAPABILITY_SUPPORTS_VIDEO_CALLING)
            registered = true
        }
        val attributes = CallAttributesCompat(
            displayName = info.peerUsername,
            // A self-managed call needs an address; this one names the call, not a phone number.
            address = Uri.fromParts("vaultchat", info.callId, null),
            direction = if (info.incoming) CallAttributesCompat.DIRECTION_INCOMING else CallAttributesCompat.DIRECTION_OUTGOING,
            callType = if (info.video) CallAttributesCompat.CALL_TYPE_VIDEO_CALL else CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
        )
        // Suspends for as long as the call exists in the system.
        manager.addCall(
            attributes,
            onAnswer = { actions.answer() },
            onDisconnect = { actions.hangup() },
            onSetActive = { actions.hold(false) },
            onSetInactive = { actions.hold(true) },
        ) {
            actions.registered()
            val endpoints = MutableStateFlow<List<CallEndpointCompat>>(emptyList())
            launch { availableEndpoints.collect { endpoints.value = it } }
            launch {
                for (command in commands) {
                    when (command) {
                        SystemCallCommand.Answer -> answer(attributes.callType)
                        SystemCallCommand.Active -> setActive()
                        is SystemCallCommand.Speaker -> route(command.on, endpoints.value)?.let { requestEndpointChange(it) }
                        is SystemCallCommand.Disconnect -> {
                            disconnect(DisconnectCause(cause(command.reason)))
                            break
                        }
                    }
                }
            }
        }
    }

    /** Loudspeaker or earpiece; a connected headset (wired or Bluetooth) is left alone, as without telecom. */
    private fun route(speaker: Boolean, endpoints: List<CallEndpointCompat>): CallEndpointCompat? {
        if (endpoints.any { it.type == CallEndpointCompat.TYPE_BLUETOOTH || it.type == CallEndpointCompat.TYPE_WIRED_HEADSET }) return null
        val wanted = if (speaker) CallEndpointCompat.TYPE_SPEAKER else CallEndpointCompat.TYPE_EARPIECE
        return endpoints.firstOrNull { it.type == wanted }
    }

    private fun cause(reason: CallEndReason) = when (reason) {
        CallEndReason.HANGUP -> DisconnectCause.LOCAL
        CallEndReason.REMOTE_HANGUP -> DisconnectCause.REMOTE
        CallEndReason.DECLINED -> DisconnectCause.REJECTED
        CallEndReason.NO_ANSWER -> DisconnectCause.MISSED
        CallEndReason.CANCELLED -> DisconnectCause.CANCELED
        CallEndReason.BUSY -> DisconnectCause.BUSY
        CallEndReason.NETWORK_LOST, CallEndReason.FAILED, CallEndReason.MEDIA_UNAVAILABLE, CallEndReason.SESSION_ENDED -> DisconnectCause.ERROR
    }
}
