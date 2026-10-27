package app.vaultchat.domain

import app.vaultchat.domain.realtime.RealtimeSignal
import app.vaultchat.domain.realtime.RealtimeSignals
import app.vaultchat.domain.realtime.pollWithSignals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PollWithSignalsTest {
    private class Live(connected: Boolean) : RealtimeSignals {
        override val signals = MutableSharedFlow<RealtimeSignal>(extraBufferCapacity = 8)
        override val connected = MutableStateFlow(connected)
    }

    private val pairing: (RealtimeSignal) -> Boolean = { it is RealtimeSignal.ConnectionChanged }

    @Test
    fun `checks at once, then on a matching signal without waiting for the poll`() = runTest {
        val live = Live(connected = true)
        var checks = 0
        val job = launch { pollWithSignals(live, pairing, 3_000, 15_000) { checks++; true } }
        runCurrent()
        assertEquals(1, checks)

        live.signals.emit(RealtimeSignal.ConnectionChanged("r1"))
        runCurrent()
        assertEquals("a pairing signal wakes it", 2, checks)

        live.signals.emit(RealtimeSignal.ChatChanged("c1"))
        runCurrent()
        assertEquals("other signals are ignored", 2, checks)

        advanceTimeBy(14_000)
        assertEquals("no 3 s polling while signals are live", 2, checks)
        advanceTimeBy(1_001)
        assertEquals("15 s fallback poll", 3, checks)
        job.cancel()
    }

    @Test
    fun `polls every 3 s while the signal socket is down, and without signals at all`() = runTest {
        val live = Live(connected = false)
        var withLive = 0
        var without = 0
        val a = launch { pollWithSignals(live, pairing, 3_000, 15_000) { withLive++; true } }
        val b = launch { pollWithSignals(null, pairing, 3_000, 15_000) { without++; true } }
        runCurrent()
        advanceTimeBy(9_001)
        assertEquals(4, withLive)
        assertEquals(4, without)
        a.cancel()
        b.cancel()
    }

    @Test
    fun `stops when the check says so, and stops listening`() = runTest {
        val live = Live(connected = true)
        var checks = 0
        val job = launch { pollWithSignals(live, pairing, 3_000, 15_000) { ++checks < 2 } }
        runCurrent()
        live.signals.emit(RealtimeSignal.ConnectionChanged("r1"))
        runCurrent()
        assertEquals(2, checks)
        assertTrue("finished by itself", job.isCompleted)
        assertEquals("no subscriber left", 0, live.signals.subscriptionCount.value)
    }
}
