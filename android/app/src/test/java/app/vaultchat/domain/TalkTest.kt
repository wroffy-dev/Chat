package app.vaultchat.domain

import app.vaultchat.domain.call.CallFailure
import app.vaultchat.domain.call.CallResult
import app.vaultchat.domain.call.MediaEvent
import app.vaultchat.domain.call.Talk
import app.vaultchat.domain.call.TalkOutcome
import app.vaultchat.domain.call.TalkPhase
import app.vaultchat.domain.call.TalkSender
import app.vaultchat.domain.call.listenToTalk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Walkie-talkie (D-26): speak only once the other phone joined, at most 10 s, listener sends nothing. */
@OptIn(ExperimentalCoroutinesApi::class)
class TalkTest {
    private val repo = FakeCallRepo()
    private val media = FakeMedia()

    @Test
    fun `speaking starts when the other phone joined and ends after ten seconds`() = runTest {
        val sender = TalkSender(repo, media, backgroundScope)
        sender.start("conn")
        runCurrent()
        assertEquals(TalkPhase.CONNECTING, sender.state.value.phase)
        assertTrue(media.connected)
        assertEquals(false, media.video)
        assertEquals(true, media.connectedMic)

        advanceTimeBy(5_000) // the other phone is still waking up
        assertEquals(TalkPhase.CONNECTING, sender.state.value.phase)
        media.events.emit(MediaEvent.RemoteJoined)
        advanceTimeBy(Talk.SETTLE_MS + 1)
        assertEquals(TalkPhase.SPEAKING, sender.state.value.phase)
        assertEquals(Talk.MAX_SECONDS, sender.state.value.secondsLeft)

        advanceTimeBy(3_000)
        assertEquals(Talk.MAX_SECONDS - 3, sender.state.value.secondsLeft)
        advanceTimeBy(Talk.MAX_SECONDS * 1_000L)
        assertEquals(TalkPhase.DONE, sender.state.value.phase)
        assertEquals(TalkOutcome.SENT, sender.state.value.outcome)
        assertTrue("end:hangup" in repo.calls)
        assertFalse(media.connected)
    }

    @Test
    fun `nothing is sent when the other phone never joins`() = runTest {
        val sender = TalkSender(repo, media, backgroundScope)
        sender.start("conn")
        advanceTimeBy(Talk.WAIT_FOR_PEER_MS + 1)
        assertEquals(TalkOutcome.NOT_REACHED, sender.state.value.outcome)
        assertTrue("cancel" in repo.calls)
        assertFalse(media.connected)
    }

    @Test
    fun `a busy partner and a stop while speaking`() = runTest {
        repo.startResult = CallResult.Failure(CallFailure.IN_PROGRESS)
        val busy = TalkSender(repo, media, backgroundScope)
        busy.start("conn")
        runCurrent()
        assertEquals(TalkOutcome.BUSY, busy.state.value.outcome)

        val repo2 = FakeCallRepo()
        val media2 = FakeMedia()
        val sender = TalkSender(repo2, media2, backgroundScope)
        sender.start("conn")
        runCurrent()
        media2.events.emit(MediaEvent.RemoteJoined)
        advanceTimeBy(Talk.SETTLE_MS + 2_000)
        assertEquals(TalkPhase.SPEAKING, sender.state.value.phase)
        sender.stop()
        runCurrent()
        assertEquals(TalkOutcome.SENT, sender.state.value.outcome)
        assertTrue("end:hangup" in repo2.calls)
        assertFalse(media2.connected)
    }

    @Test
    fun `the listening phone joins without a microphone, plays on the speaker and leaves with the speaker`() = runTest {
        val listening = launch { listenToTalk(repo, media, "c1") }
        runCurrent()
        assertTrue("accept" in repo.calls)
        assertTrue(media.connected)
        assertEquals(false, media.connectedMic)
        assertTrue("speaker:true" in media.log)

        media.events.emit(MediaEvent.RemoteLeft)
        runCurrent()
        assertTrue(listening.isCompleted)
        assertFalse(media.connected)
    }

    @Test
    fun `the listening phone leaves by itself after the limit`() = runTest {
        val listening = launch { listenToTalk(repo, media, "c1") }
        advanceTimeBy(Talk.LISTEN_LIMIT_MS + 1)
        assertTrue(listening.isCompleted)
        assertFalse(media.connected)
    }
}
