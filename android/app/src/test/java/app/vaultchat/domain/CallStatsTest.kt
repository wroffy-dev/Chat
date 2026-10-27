package app.vaultchat.domain

import app.vaultchat.domain.call.CallStatsAggregator
import app.vaultchat.domain.call.RawStat
import app.vaultchat.domain.call.StatsSample
import app.vaultchat.domain.call.WebRtcStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class CallStatsTest {
    private val publisher = listOf(
        RawStat("pair1", "candidate-pair", mapOf("state" to "succeeded", "nominated" to true, "currentRoundTripTime" to 0.042, "localCandidateId" to "lc1")),
        RawStat("lc1", "local-candidate", mapOf("candidateType" to "relay", "protocol" to "udp", "address" to "203.0.113.7")),
        RawStat("o1", "outbound-rtp", mapOf("kind" to "video", "frameWidth" to 640L, "frameHeight" to 360L, "framesPerSecond" to 30.0, "bytesSent" to BigInteger.valueOf(1000), "qualityLimitationReason" to "none")),
        RawStat("o2", "outbound-rtp", mapOf("kind" to "video", "frameWidth" to 1280L, "frameHeight" to 720L, "framesPerSecond" to 29.0, "bytesSent" to BigInteger.valueOf(5000), "qualityLimitationReason" to "bandwidth")),
        RawStat("o3", "outbound-rtp", mapOf("kind" to "audio", "bytesSent" to BigInteger.valueOf(500))),
        RawStat("pair2", "candidate-pair", mapOf("state" to "failed", "currentRoundTripTime" to 9.9)),
    )
    private val subscriber = listOf(
        RawStat("i1", "inbound-rtp", mapOf("kind" to "video", "frameWidth" to 960L, "frameHeight" to 540L, "framesPerSecond" to 24.0, "packetsLost" to 3, "packetsReceived" to 97L, "bytesReceived" to BigInteger.valueOf(8000), "jitter" to 0.004, "freezeCount" to 1L)),
        RawStat("i2", "inbound-rtp", mapOf("kind" to "audio", "packetsLost" to 0, "packetsReceived" to 100L, "bytesReceived" to BigInteger.valueOf(2000), "jitter" to 0.012, "concealmentEvents" to 2L)),
    )

    @Test
    fun `reads the standard WebRTC stats, never addresses`() {
        val s = WebRtcStats.sample(publisher, subscriber)
        assertEquals(42.0, s.rttMs!!, 1e-9)
        assertEquals(12.0, s.jitterMs!!, 1e-9)
        assertEquals(3L, s.packetsLost)
        assertEquals(197L, s.packetsReceived)
        assertEquals(6500L, s.bytesSent)
        assertEquals(10000L, s.bytesReceived)
        assertEquals(1280 to 720, s.sendWidth to s.sendHeight) // the largest simulcast layer
        assertEquals("bandwidth", s.qualityLimitation)
        assertEquals(960 to 540, s.recvWidth to s.recvHeight)
        assertEquals(1L, s.freezeCount)
        assertEquals(2L, s.concealmentEvents)
        assertEquals("relay" to "udp", s.candidateType to s.protocol)
        assertTrue(!s.toString().contains("203.0.113.7"))
    }

    @Test
    fun `empty or odd input gives an empty sample, not a crash`() {
        val s = WebRtcStats.sample(emptyList(), listOf(RawStat("x", "inbound-rtp", mapOf("packetsLost" to "n/a"))))
        assertNull(s.rttMs)
        assertEquals(0L, s.packetsLost)
        assertEquals(emptyMap<String, Any?>(), CallStatsAggregator().summary())
    }

    @Test
    fun `summary survives counters restarting after a reconnect`() {
        val a = CallStatsAggregator()
        a.add(StatsSample(atMs = 0, packetsLost = 0, packetsReceived = 0, bytesReceived = 0))
        a.add(StatsSample(atMs = 2_000, packetsLost = 2, packetsReceived = 148, bytesReceived = 300_000))
        // New peer connection: counters start again from zero (each one drops).
        a.add(StatsSample(atMs = 4_000, packetsLost = 0, packetsReceived = 50, bytesReceived = 200_000, qualityLimitation = "cpu"))
        val s = a.summary()
        assertEquals(1.0, s["lossPct"]) // 2 lost of 200
        assertEquals(1000, s["recvKbpsAvg"]) // 500 kB over 4 s
        assertEquals(100.0, s["qualityLimitedPct"])
        assertEquals("cpu", s["qualityLimitation"])
    }
}
