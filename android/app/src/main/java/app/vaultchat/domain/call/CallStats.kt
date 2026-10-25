package app.vaultchat.domain.call

import kotlin.math.roundToInt

/** One WebRTC stats entry, copied out of the SDK so the parsing below runs on a plain JVM. */
data class RawStat(val id: String, val type: String, val members: Map<String, Any?>)

/** What one stats poll says about the call (docs/08-calling.md §11). Counters are cumulative. */
data class StatsSample(
    val atMs: Long = 0,
    val rttMs: Double? = null,
    val jitterMs: Double? = null,
    val packetsLost: Long = 0,
    val packetsReceived: Long = 0,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val sendWidth: Int? = null,
    val sendHeight: Int? = null,
    val sendFps: Double? = null,
    val recvWidth: Int? = null,
    val recvHeight: Int? = null,
    val recvFps: Double? = null,
    /** Why the sent video was limited: "bandwidth", "cpu", "other" or "none". */
    val qualityLimitation: String? = null,
    val freezeCount: Long = 0,
    val concealmentEvents: Long = 0,
    /** "host", "srflx", "prflx" or "relay"; never an address. */
    val candidateType: String? = null,
    val protocol: String? = null,
)

/** Reads the standard WebRTC stats types (inbound-rtp, outbound-rtp, candidate-pair, local-candidate). */
object WebRtcStats {
    fun sample(publisher: List<RawStat>, subscriber: List<RawStat>): StatsSample {
        val all = publisher + subscriber
        val byId = all.associateBy { it.id }

        // The pair in use: nominated and succeeded. RTT from the publisher side when available.
        val pairs = all.filter { it.type == "candidate-pair" && it.str("state") == "succeeded" && it.members["nominated"] != false }
        val pair = pairs.firstOrNull { it.num("currentRoundTripTime") != null } ?: pairs.firstOrNull()
        val local = pair?.str("localCandidateId")?.let { byId[it] }

        val inbound = subscriber.filter { it.type == "inbound-rtp" }
        val inVideo = inbound.filter { it.kind() == "video" }.maxByOrNull { it.num("frameWidth") ?: 0.0 }
        val inAudio = inbound.filter { it.kind() == "audio" }

        // Simulcast sends several layers: describe the largest one.
        val outVideo = publisher.filter { it.type == "outbound-rtp" && it.kind() == "video" }.maxByOrNull { it.num("frameWidth") ?: 0.0 }

        return StatsSample(
            rttMs = pair?.num("currentRoundTripTime")?.times(1000),
            jitterMs = inbound.mapNotNull { it.num("jitter") }.maxOrNull()?.times(1000),
            packetsLost = inbound.sumOf { it.long("packetsLost") },
            packetsReceived = inbound.sumOf { it.long("packetsReceived") },
            bytesSent = publisher.filter { it.type == "outbound-rtp" }.sumOf { it.long("bytesSent") },
            bytesReceived = inbound.sumOf { it.long("bytesReceived") },
            sendWidth = outVideo?.num("frameWidth")?.toInt(),
            sendHeight = outVideo?.num("frameHeight")?.toInt(),
            sendFps = outVideo?.num("framesPerSecond"),
            recvWidth = inVideo?.num("frameWidth")?.toInt(),
            recvHeight = inVideo?.num("frameHeight")?.toInt(),
            recvFps = inVideo?.num("framesPerSecond"),
            qualityLimitation = outVideo?.str("qualityLimitationReason"),
            freezeCount = inbound.sumOf { it.long("freezeCount") },
            concealmentEvents = inAudio.sumOf { it.long("concealmentEvents") },
            candidateType = local?.str("candidateType"),
            protocol = local?.str("protocol"),
        )
    }

    private fun RawStat.num(key: String): Double? = (members[key] as? Number)?.toDouble()
    private fun RawStat.long(key: String): Long = (members[key] as? Number)?.toLong() ?: 0
    private fun RawStat.str(key: String): String? = members[key]?.toString()
    private fun RawStat.kind(): String? = str("kind") ?: str("mediaType")
}

/**
 * Turns the samples of one call into the end-of-call summary: min/avg/max and totals, not a time
 * series (docs/08 §11). Counters that restart (a new connection after a reconnect) are handled by
 * summing only increases. Keys and values fit the server's diagnostics limits (≤ 40 keys).
 */
class CallStatsAggregator {
    private val samples = mutableListOf<StatsSample>()

    fun add(sample: StatsSample) {
        samples += sample
    }

    fun summary(): Map<String, Any?> {
        if (samples.isEmpty()) return emptyMap()
        val rtt = samples.mapNotNull { it.rttMs }
        val jitter = samples.mapNotNull { it.jitterMs }
        val lost = increase { it.packetsLost }
        val received = increase { it.packetsReceived }
        val spanMs = (samples.last().atMs - samples.first().atMs).coerceAtLeast(0)
        val limited = samples.mapNotNull { it.qualityLimitation }
        val last = samples.last()
        return linkedMapOf(
            "statsSamples" to samples.size,
            "rttMsMin" to rtt.minOrNull()?.roundToInt(),
            "rttMsAvg" to rtt.averageOrNull()?.roundToInt(),
            "rttMsMax" to rtt.maxOrNull()?.roundToInt(),
            "jitterMsAvg" to jitter.averageOrNull()?.roundToInt(),
            "jitterMsMax" to jitter.maxOrNull()?.roundToInt(),
            "lossPct" to if (lost + received > 0) round1(100.0 * lost / (lost + received)) else null,
            // Rates count only what was sent between the first and last sample.
            "sendKbpsAvg" to kbps(increase(fromFirstSample = true) { it.bytesSent }, spanMs),
            "recvKbpsAvg" to kbps(increase(fromFirstSample = true) { it.bytesReceived }, spanMs),
            "sendResMax" to resolution(samples.mapNotNull { s -> s.sendWidth?.let { it to (s.sendHeight ?: 0) } }),
            "recvResMax" to resolution(samples.mapNotNull { s -> s.recvWidth?.let { it to (s.recvHeight ?: 0) } }),
            "sendFpsAvg" to samples.mapNotNull { it.sendFps }.averageOrNull()?.let(::round1),
            "recvFpsAvg" to samples.mapNotNull { it.recvFps }.averageOrNull()?.let(::round1),
            "qualityLimitedPct" to if (limited.isEmpty()) null else round1(100.0 * limited.count { it != "none" } / limited.size),
            "qualityLimitation" to limited.filter { it != "none" }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key,
            "videoFreezes" to increase { it.freezeCount },
            "audioConcealmentEvents" to increase { it.concealmentEvents },
            "path" to listOfNotNull(last.candidateType, last.protocol).joinToString("/").ifEmpty { null },
        )
    }

    /**
     * Total growth of a cumulative counter; a drop means it restarted, so the new value is all growth.
     * Counted from zero (the call's start) unless [fromFirstSample].
     */
    private fun increase(fromFirstSample: Boolean = false, counter: (StatsSample) -> Long): Long {
        var total = 0L
        var previous = if (fromFirstSample) counter(samples.first()) else 0L
        for (s in if (fromFirstSample) samples.drop(1) else samples) {
            val v = counter(s)
            total += if (v >= previous) v - previous else v
            previous = v
        }
        return total
    }

    private fun kbps(bytes: Long, spanMs: Long): Int? = if (spanMs <= 0) null else (bytes * 8.0 / spanMs).roundToInt()

    private fun resolution(sizes: List<Pair<Int, Int>>): String? = sizes.maxByOrNull { it.first * it.second }?.let { "${it.first}x${it.second}" }

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()

    private fun round1(v: Double): Double = (v * 10).roundToInt() / 10.0
}
