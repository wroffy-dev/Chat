package app.vaultchat.domain.call

enum class CaptureQuality { P1080, P720, P540, P360 }

/** The sent video size the user picked during a call. AUTO keeps the size the phone chose. */
enum class VideoQualityChoice(val capture: CaptureQuality?) {
    AUTO(null),
    P1080(CaptureQuality.P1080),
    P720(CaptureQuality.P720),
    P540(CaptureQuality.P540),
    P360(CaptureQuality.P360),
}

/**
 * Chooses the camera capture size (docs/08-calling.md §8, §12) from what the phone can do, capped by the
 * admin's setting ([cap]). LiveKit's
 * congestion control still lowers the sent resolution on weak networks; the user can also pick a
 * size during the call (QualityAdvisor recommends one).
 */
object VideoQualityPolicy {
    /** Android PowerManager thermal status values (THERMAL_STATUS_*). */
    const val THERMAL_NONE = 0
    const val THERMAL_LIGHT = 1
    const val THERMAL_MODERATE = 2
    const val THERMAL_SEVERE = 3

    fun choose(cameraSupports1080p30: Boolean, thermalStatus: Int, powerSaveMode: Boolean): CaptureQuality = when {
        thermalStatus >= THERMAL_SEVERE -> CaptureQuality.P540
        thermalStatus >= THERMAL_MODERATE || powerSaveMode || !cameraSupports1080p30 -> CaptureQuality.P720
        else -> CaptureQuality.P1080
    }

    /**
     * What Auto sends: what the phone can do, but never above the admin's "highest video size" setting
     * (Admin Console → Settings, `call.max_video_quality`; 720p by default since 2026-11-03).
     */
    fun cap(capable: CaptureQuality, max: CaptureQuality): CaptureQuality =
        // Lower ordinal = larger size.
        if (capable.ordinal < max.ordinal) max else capable

    /** "1080p" / "720p" / "540p" / "360p" from the server; null for anything else. */
    fun parse(value: String?): CaptureQuality? = when (value) {
        "1080p" -> CaptureQuality.P1080
        "720p" -> CaptureQuality.P720
        "540p" -> CaptureQuality.P540
        "360p" -> CaptureQuality.P360
        else -> null
    }
}
