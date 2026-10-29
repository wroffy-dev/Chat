package app.vaultchat.domain

import app.vaultchat.domain.call.CaptureQuality
import app.vaultchat.domain.call.QualityAdvisor
import app.vaultchat.domain.call.StatsSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QualityAdvisorTest {
    private fun s(bitrate: Double?, rtt: Double? = 40.0, lost: Long = 0, received: Long = 0) =
        StatsSample(availableOutgoingBitrate = bitrate, rttMs = rtt, packetsLost = lost, packetsReceived = received)

    @Test
    fun `follows the measured bandwidth`() {
        assertEquals(CaptureQuality.P1080, QualityAdvisor.recommend(listOf(s(4_000_000.0))))
        assertEquals(CaptureQuality.P720, QualityAdvisor.recommend(listOf(s(1_500_000.0))))
        assertEquals(CaptureQuality.P540, QualityAdvisor.recommend(listOf(s(800_000.0))))
        assertEquals(CaptureQuality.P360, QualityAdvisor.recommend(listOf(s(300_000.0))))
    }

    @Test
    fun `uses the median of recent samples, so one spike does not decide`() {
        val samples = listOf(s(1_500_000.0), s(9_000_000.0), s(1_400_000.0))
        assertEquals(CaptureQuality.P720, QualityAdvisor.recommend(samples))
    }

    @Test
    fun `steps down on heavy loss or long delay`() {
        val lossy = listOf(s(3_000_000.0, lost = 0, received = 0), s(3_000_000.0, lost = 100, received = 900))
        assertEquals(CaptureQuality.P720, QualityAdvisor.recommend(lossy))
        assertEquals(CaptureQuality.P540, QualityAdvisor.recommend(listOf(s(1_500_000.0, rtt = 500.0))))
        assertEquals("never below 360p", CaptureQuality.P360, QualityAdvisor.recommend(listOf(s(100_000.0, rtt = 500.0))))
    }

    @Test
    fun `without a bandwidth estimate judges by delay, and says nothing without data`() {
        assertEquals(CaptureQuality.P720, QualityAdvisor.recommend(listOf(s(null, rtt = 60.0))))
        assertEquals(CaptureQuality.P360, QualityAdvisor.recommend(listOf(s(null, rtt = 300.0))))
        assertNull(QualityAdvisor.recommend(listOf(s(null, rtt = null))))
        assertNull(QualityAdvisor.recommend(emptyList()))
    }
}
