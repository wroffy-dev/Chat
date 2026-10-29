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
 * Chooses the camera capture size (docs/08-calling.md §8, §12). 1080p30 only when the camera
 * supports it, the phone is not warm and power saving is off; otherwise step down. LiveKit's
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
}
