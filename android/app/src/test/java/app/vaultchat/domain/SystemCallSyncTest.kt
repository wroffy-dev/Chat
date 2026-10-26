package app.vaultchat.domain

import app.vaultchat.domain.call.CallController
import app.vaultchat.domain.call.CallEndReason
import app.vaultchat.domain.call.CallInfo
import app.vaultchat.domain.call.CallKind
import app.vaultchat.domain.call.CallPhase
import app.vaultchat.domain.call.CallStatus
import app.vaultchat.domain.call.MediaEvent
import app.vaultchat.domain.call.SystemCallActions
import app.vaultchat.domain.call.SystemCallCommand
import app.vaultchat.domain.call.SystemCallInfo
import app.vaultchat.domain.call.SystemCallSync
import app.vaultchat.domain.call.SystemCalls
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stands in for Android's telecom framework: records what it was told, lets a test press its buttons. */
class FakeSystemCalls(private val refuse: Boolean = false) : SystemCalls {
    var info: SystemCallInfo? = null
    val received = mutableListOf<SystemCallCommand>()
    lateinit var actions: SystemCallActions
    var removed = false

    override suspend fun run(info: SystemCallInfo, commands: ReceiveChannel<SystemCallCommand>, actions: SystemCallActions) {
        this.info = info
        this.actions = actions
        if (refuse) throw IllegalStateException("call not permitted at present time")
        actions.registered()
        for (c in commands) {
            received += c
            if (c is SystemCallCommand.Disconnect) break
        }
        removed = true
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SystemCallSyncTest {
    private val repo = FakeCallRepo()
    private val media = FakeMedia()
    private var systemAudio = 0

    private fun TestScope.setup(system: SystemCalls): CallController {
        val c = CallController(repo, media, backgroundScope, nowMs = { testScheduler.currentTime })
        SystemCallSync(c, system, backgroundScope, onSystemAudio = { systemAudio++ }).start()
        return c
    }

    private fun incoming(kind: CallKind = CallKind.VOICE) = CallInfo("c9", kind, CallStatus.RINGING, false, "alice")

    @Test
    fun `an outgoing call is added to the system, made active when answered, and removed at hang-up`() = runTest {
        val system = FakeSystemCalls()
        val c = setup(system)
        c.startOutgoing("conn", CallKind.VIDEO, "bob")
        runCurrent()
        assertEquals(SystemCallInfo("c1", incoming = false, video = true, peerUsername = "bob"), system.info)
        assertEquals(1, systemAudio)
        assertEquals(listOf<SystemCallCommand>(SystemCallCommand.Speaker(true)), system.received)

        repo.status = CallStatus.ACCEPTED
        advanceTimeBy(CallController.STATUS_POLL_MS + 1)
        media.events.emit(MediaEvent.RemoteJoined)
        runCurrent()
        assertEquals(CallPhase.ACTIVE, c.state.value.phase)
        assertEquals(1, system.received.count { it == SystemCallCommand.Active })

        c.hangup()
        runCurrent()
        assertEquals(SystemCallCommand.Disconnect(CallEndReason.HANGUP), system.received.last())
        assertTrue(system.removed)
    }

    @Test
    fun `answering from a headset or car accepts the call, and the system is not told to answer twice`() = runTest {
        val system = FakeSystemCalls()
        val c = setup(system)
        c.showIncoming(incoming())
        runCurrent()
        assertEquals(true, system.info?.incoming)
        assertEquals(false, system.info?.video)

        system.actions.answer()
        runCurrent()
        assertTrue("accepted on the server", "accept" in repo.calls)
        assertEquals(CallPhase.CONNECTING, c.state.value.phase)
        assertFalse(SystemCallCommand.Answer in system.received)
    }

    @Test
    fun `answering in the app tells the system`() = runTest {
        val system = FakeSystemCalls()
        val c = setup(system)
        c.showIncoming(incoming())
        runCurrent()
        c.accept()
        runCurrent()
        assertEquals(1, system.received.count { it == SystemCallCommand.Answer })
    }

    @Test
    fun `ending from the system declines a ringing call and hangs up an active one`() = runTest {
        val system = FakeSystemCalls()
        val c = setup(system)
        c.showIncoming(incoming())
        runCurrent()
        system.actions.hangup()
        runCurrent()
        assertTrue("decline" in repo.calls)
        assertEquals(CallEndReason.DECLINED, c.state.value.endReason)
        assertEquals(SystemCallCommand.Disconnect(CallEndReason.DECLINED), system.received.last())
    }

    @Test
    fun `a hold from the system mutes the microphone, and resuming unmutes only what the hold muted`() = runTest {
        val system = FakeSystemCalls()
        val c = setup(system)
        c.showIncoming(incoming())
        runCurrent()
        system.actions.answer()
        runCurrent()

        system.actions.hold(true)
        runCurrent()
        assertFalse(c.state.value.micOn)
        system.actions.hold(false)
        runCurrent()
        assertTrue(c.state.value.micOn)

        // Muted by the user before the hold: stays muted afterwards.
        c.toggleMicrophone()
        system.actions.hold(true)
        system.actions.hold(false)
        runCurrent()
        assertFalse(c.state.value.micOn)
    }

    @Test
    fun `speaker changes in the app are passed to the system`() = runTest {
        val system = FakeSystemCalls()
        val c = setup(system)
        c.startOutgoing("conn", CallKind.VOICE, "bob")
        runCurrent()
        c.toggleSpeaker()
        runCurrent()
        assertEquals(listOf<SystemCallCommand>(SystemCallCommand.Speaker(false), SystemCallCommand.Speaker(true)), system.received)
    }

    @Test
    fun `when the system refuses the call, the call goes on in the app and media keeps its own audio`() = runTest {
        val system = FakeSystemCalls(refuse = true)
        val c = setup(system)
        c.startOutgoing("conn", CallKind.VOICE, "bob")
        runCurrent()
        assertEquals(CallPhase.RINGING_OUT, c.state.value.phase)
        assertEquals(0, systemAudio)
        c.hangup()
        runCurrent()
        assertEquals(CallPhase.ENDED, c.state.value.phase)
    }

    @Test
    fun `a call that fails before ringing never reaches the system`() = runTest {
        val system = FakeSystemCalls()
        repo.startResult = app.vaultchat.domain.call.CallResult.Failure(app.vaultchat.domain.call.CallFailure.IN_PROGRESS)
        val c = setup(system)
        c.startOutgoing("conn", CallKind.VOICE, "bob")
        runCurrent()
        assertEquals(CallEndReason.BUSY, c.state.value.endReason)
        assertNull(system.info)
    }
}
