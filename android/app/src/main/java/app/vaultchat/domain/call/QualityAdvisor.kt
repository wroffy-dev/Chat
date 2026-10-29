package app.vaultchat.domain.call

/**
 * Recommends a video size from what this call's connection actually does: the sender's bandwidth
 * estimate (WebRTC `availableOutgoingBitrate`), round-trip time and packet loss over the last five
 * samples (about 10 s). One step lower when loss or delay is high. Null until there is enough to go on.
 */
object QualityAdvisor {
    /** Rough bitrate each size needs to look good at 30 fps (H.264/VP8, simulcast top layer). */
    private const val NEED_1080 = 2_500_000.0
    private const val NEED_720 = 1_200_000.0
    private const val NEED_540 = 600_000.0

    fun recommend(samples: List<StatsSample>): CaptureQuality? {
        val recent = samples.takeLast(5)
        if (recent.isEmpty()) return null
        val rtt = median(recent.mapNotNull { it.rttMs })
        val bitrate = median(recent.mapNotNull { it.availableOutgoingBitrate })
        var q = when {
            bitrate != null -> when {
                bitrate >= NEED_1080 -> CaptureQuality.P1080
                bitrate >= NEED_720 -> CaptureQuality.P720
                bitrate >= NEED_540 -> CaptureQuality.P540
                else -> CaptureQuality.P360
            }
            // Without a bandwidth estimate, judge by delay alone and stay modest.
            rtt != null -> when {
                rtt < 120 -> CaptureQuality.P720
                rtt < 250 -> CaptureQuality.P540
                else -> CaptureQuality.P360
            }
            else -> return null
        }
        val loss = lossFraction(recent.first(), recent.last())
        if ((loss ?: 0.0) > 0.05 || (rtt ?: 0.0) > 350) q = stepDown(q)
        return q
    }

    private fun stepDown(q: CaptureQuality) = CaptureQuality.entries.getOrElse(q.ordinal + 1) { q }

    private fun lossFraction(first: StatsSample, last: StatsSample): Double? {
        val lost = last.packetsLost - first.packetsLost
        val received = last.packetsReceived - first.packetsReceived
        if (lost < 0 || received <= 0) return null
        return lost.toDouble() / (lost + received)
    }

    private fun median(values: List<Double>): Double? = values.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
}
