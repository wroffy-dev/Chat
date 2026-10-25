package app.vaultchat.domain

import app.vaultchat.domain.call.CallController
import app.vaultchat.domain.call.CallEndReason
import app.vaultchat.domain.call.CallFailure
import app.vaultchat.domain.call.CallInfo
import app.vaultchat.domain.call.CallKind
import app.vaultchat.domain.call.CallPhase
import app.vaultchat.domain.call.CallRepository
import app.vaultchat.domain.call.CallResult
import app.vaultchat.domain.call.CallStatus
import app.vaultchat.domain.call.JoinInfo
import app.vaultchat.domain.call.MediaEvent
import app.vaultchat.domain.call.MediaSession
import app.vaultchat.domain.call.StatsSample
import app.vaultchat.domain.realtime.RealtimeSignal
import app.vaultchat.domain.realtime.RealtimeSignals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeCallRepo : CallRepository {
    var status = CallStatus.RINGING
    var startResult: CallResult<JoinInfo> = CallResult.Ok(JoinInfo("c1", "wss://lk", "tok", 45))
    val calls = mutableListOf<String>()
    val diagnostics = mutableListOf<Map<String, Any?>>()

    override suspend fun start(connectionId: String, kind: CallKind) = startResult.also { calls += "start" }
    override suspend fun current() = CallResult.Ok(null)
    override suspend fun get(callId: String) = CallResult.Ok(CallInfo(callId, CallKind.VIDEO, status, true, "bob")).also { calls += "get" }
    override suspend fun accept(callId: String) = CallResult.Ok(JoinInfo(callId, "wss://lk", "tok", 45)).also { calls += "accept" }
    override suspend fun decline(callId: String) = CallResult.Ok(Unit).also { calls += "decline" }
    override suspend fun cancel(callId: String) = CallResult.Ok(Unit).also { calls += "cancel" }
    override suspend fun end(callId: String, reason: String) = CallResult.Ok(Unit).also { calls += "end:$reason" }
    override suspend fun diagnostics(callId: String, summary: Map<String, Any?>) = CallResult.Ok(Unit).also { diagnostics += summary }
}

class FakeMedia : MediaSession {
    override val events = MutableSharedFlow<MediaEvent>(extraBufferCapacity = 16)
    var connected = false
    var disconnects = 0
    var failConnect = false
    var video: Boolean? = null
    val log = mutableListOf<String>()

    override suspend fun connect(url: String, token: String, video: Boolean) {
        if (failConnect) throw IllegalStateException("no media")
        connected = true
        this.video = video
    }
    override suspend fun setMicrophoneEnabled(enabled: Boolean) { log += "mic:$enabled" }
    override suspend fun setCameraEnabled(enabled: Boolean) { log += "cam:$enabled" }
    override fun switchCamera() { log += "switch" }
    override fun setSpeakerphone(enabled: Boolean) { log += "speaker:$enabled" }
    override fun disconnect() {
        connected = false
        disconnects++
    }
    var nextStats: StatsSample? = null
    override suspend fun stats() = nextStats
}

@OptIn(ExperimentalCoroutinesApi::class)
class CallControllerTest {
    private val repo = FakeCallRepo()
    private val media = FakeMedia()

    private fun TestScope.controller() = CallController(repo, media, backgroundScope, nowMs = { testScheduler.currentTime })

    @Test
    fun `outgoing video call rings, is answered and becomes active when the peer's media arrives`() = runTest {
        val c = controller()
        c.startOutgoing("conn", CallKind.VIDEO, "bob")
        runCurrent()
        assertEquals(CallPhase.RINGING_OUT, c.state.value.phase)
        assertTrue("caller joins the room while ringing", media.connected)
        assertEquals(true, media.video)

        repo.status = CallStatus.ACCEPTED
        advanceTimeBy(CallController.STATUS_POLL_MS + 1)
        assertEquals(CallPhase.CONNECTING, c.state.value.phase)

        media.events.emit(MediaEvent.RemoteJoined)
        runCurrent()
        assertEquals(CallPhase.ACTIVE, c.state.value.phase)
    }

    @Test
    fun `declined and unanswered calls end and release media`() = runTest {
        val c = controller()
        c.startOutgoing("conn", CallKind.VOICE, "bob")
        runCurrent()
        assertEquals(false, media.video)
        repo.status = CallStatus.MISSED
        advanceTimeBy(CallController.STATUS_POLL_MS + 1)
        assertEquals(CallEndReason.NO_ANSWER, c.state.value.endReason)
        assertFalse(media.connected)
        runCurrent()
        assertEquals("no answer is not reported again", false, repo.calls.any { it.startsWith("end:") })
        assertEquals(1, repo.diagnostics.size)
    }

    @Test
    fun `hanging up while ringing cancels, after answer it ends`() = runTest {
        val c = controller()
        c.startOutgoing("conn", CallKind.VIDEO, "bob")
        runCurrent()
        c.hangup()
        runCurrent()
        assertEquals(CallEndReason.CANCELLED, c.state.value.endReason)
        assertTrue("cancel" in repo.calls)

        val c2 = controller()
        repo.status = CallStatus.RINGING
        c2.startOutgoing("conn", CallKind.VIDEO, "bob")
        runCurrent()
        media.events.emit(MediaEvent.RemoteJoined)
        runCurrent()
        c2.hangup()
        runCurrent()
        assertEquals(CallEndReason.HANGUP, c2.state.value.endReason)
        assertTrue("end:hangup" in repo.calls)
    }

    @Test
    fun `incoming call can be accepted, joins media, and ends when the peer leaves`() = runTest {
        val c = controller()
        c.showIncoming(CallInfo("c9", CallKind.VIDEO, CallStatus.RINGING, false, "alice"))
        assertEquals(CallPhase.RINGING_IN, c.state.value.phase)
        c.accept()
        runCurrent()
        assertTrue("accept" in repo.calls)
        assertTrue(media.connected)
        media.events.emit(MediaEvent.RemoteJoined)
        runCurrent()
        assertEquals(CallPhase.ACTIVE, c.state.value.phase)
        media.events.emit(MediaEvent.RemoteLeft)
        runCurrent()
        assertEquals(CallEndReason.REMOTE_HANGUP, c.state.value.endReason)
        assertFalse(media.connected)
    }

    @Test
    fun `declining an incoming call tells the server`() = runTest {
        val c = controller()
        c.showIncoming(CallInfo("c9", CallKind.VOICE, CallStatus.RINGING, false, "alice"))
        c.decline()
        runCurrent()
        assertTrue("decline" in repo.calls)
        assertEquals(CallEndReason.DECLINED, c.state.value.endReason)
    }

    @Test
    fun `gives up after 30 s of reconnecting, but recovers if the network returns in time`() = runTest {
        val c = controller()
        c.showIncoming(CallInfo("c9", CallKind.VIDEO, CallStatus.RINGING, false, "alice"))
        repo.status = CallStatus.ACTIVE
        c.accept()
        runCurrent()
        media.events.emit(MediaEvent.RemoteJoined)
        runCurrent()

        media.events.emit(MediaEvent.Reconnecting)
        runCurrent()
        assertEquals(CallPhase.RECONNECTING, c.state.value.phase)
        advanceTimeBy(10_000)
        media.events.emit(MediaEvent.Reconnected)
        runCurrent()
        assertEquals(CallPhase.ACTIVE, c.state.value.phase)

        media.events.emit(MediaEvent.Reconnecting)
        runCurrent()
        advanceTimeBy(CallController.RECONNECT_GIVE_UP_MS + 1)
        assertEquals(CallEndReason.NETWORK_LOST, c.state.value.endReason)
        runCurrent()
        assertTrue("end:network_lost" in repo.calls)
        assertEquals(2, c.state.value.reconnects)
    }

    @Test
    fun `media connection failure ends the call and reports an error`() = runTest {
        media.failConnect = true
        val c = controller()
        c.startOutgoing("conn", CallKind.VIDEO, "bob")
        // runCurrent (not advanceUntilIdle): the controller runs in backgroundScope, which
        // advanceUntilIdle deliberately does not wait for.
        runCurrent()
        assertEquals(CallEndReason.FAILED, c.state.value.endReason)
        assertTrue("end:error" in repo.calls)
    }

    @Test
    fun `busy and unavailable are reported without contacting media`() = runTest {
        repo.startResult = CallResult.Failure(CallFailure.IN_PROGRESS)
        val c = controller()
        c.startOutgoing("conn", CallKind.VIDEO, "bob")
        runCurrent()
        assertEquals(CallEndReason.BUSY, c.state.value.endReason)
        repo.startResult = CallResult.Failure(CallFailure.MEDIA_UNAVAILABLE)
        val c2 = controller()
        c2.startOutgoing("conn", CallKind.VIDEO, "bob")
        runCurrent()
        assertEquals(CallEndReason.MEDIA_UNAVAILABLE, c2.state.value.endReason)
        assertFalse(media.connected)
    }

    @Test
    fun `controls toggle microphone, camera and speaker, and voice calls have no camera`() = runTest {
        val c = controller()
        c.startOutgoing("conn", CallKind.VOICE, "bob")
        runCurrent()
        c.toggleMicrophone()
        c.toggleCamera()
        c.toggleSpeaker()
        runCurrent()
        assertFalse(c.state.value.micOn)
        assertFalse(c.state.value.cameraOn)
        assertTrue("mic:false" in media.log)
        assertFalse(media.log.any { it.startsWith("cam:") })
        assertTrue("speaker:true" in media.log)
    }

    @Test
    fun `diagnostics contain numbers and labels only`() = runTest {
        val c = controller()
        c.startOutgoing("conn", CallKind.VIDEO, "bob")
        runCurrent()
        media.events.emit(MediaEvent.RemoteJoined)
        runCurrent()
        advanceTimeBy(65_000)
        c.hangup()
        runCurrent()
        val d = repo.diagnostics.single()
        assertEquals(setOf("kind", "endReason", "durationSec", "reconnects"), d.keys)
        assertEquals(65L, d["durationSec"])
    }

    @Test
    fun `call statistics are sampled while connected and summarised at the end`() = runTest {
        val c = controller()
        c.startOutgoing("conn", CallKind.VIDEO, "bob")
        runCurrent()
        var sent = 0L
        media.nextStats = StatsSample(rttMs = 80.0, bytesSent = 0, sendWidth = 1280, sendHeight = 720, candidateType = "srflx", protocol = "udp")
        media.events.emit(MediaEvent.RemoteJoined)
        runCurrent()
        repeat(5) {
            sent += 250_000 // 1 Mbps over 2 s
            media.nextStats = media.nextStats!!.copy(bytesSent = sent, rttMs = 80.0 + it * 10)
            advanceTimeBy(CallController.STATS_INTERVAL_MS)
        }
        c.hangup()
        runCurrent()
        val d = repo.diagnostics.single()
        assertEquals(6, d["statsSamples"])
        assertEquals(80, d["rttMsMin"])
        assertEquals(120, d["rttMsMax"])
        assertEquals(1000, d["sendKbpsAvg"])
        assertEquals("1280x720", d["sendResMax"])
        assertEquals("srflx/udp", d["path"])
        assertTrue("within the server's 40-key limit", d.size <= 40)
    }

    @Test
    fun `a live call signal ends the ringing at once instead of at the next poll`() = runTest {
        val live = object : RealtimeSignals {
            override val signals = MutableSharedFlow<RealtimeSignal>(extraBufferCapacity = 8)
            override val connected = MutableStateFlow(true)
        }
        val c = CallController(repo, media, backgroundScope, { testScheduler.currentTime }, live)
        c.startOutgoing("conn", CallKind.VIDEO, "bob")
        runCurrent()
        repo.status = CallStatus.DECLINED
        advanceTimeBy(5_000)
        assertEquals("no 2 s polling while connected", CallPhase.RINGING_OUT, c.state.value.phase)
        live.signals.emit(RealtimeSignal.CallChanged("c1"))
        runCurrent()
        assertEquals(CallEndReason.DECLINED, c.state.value.endReason)
    }
}
