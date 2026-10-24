package app.vaultchat.domain

import app.vaultchat.domain.call.CaptureQuality
import app.vaultchat.domain.call.VideoQualityPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoQualityPolicyTest {
    @Test
    fun `1080p only on a capable, cool phone without power saving`() {
        assertEquals(CaptureQuality.P1080, VideoQualityPolicy.choose(true, VideoQualityPolicy.THERMAL_NONE, false))
        assertEquals(CaptureQuality.P1080, VideoQualityPolicy.choose(true, VideoQualityPolicy.THERMAL_LIGHT, false))
        assertEquals(CaptureQuality.P720, VideoQualityPolicy.choose(false, VideoQualityPolicy.THERMAL_NONE, false))
        assertEquals(CaptureQuality.P720, VideoQualityPolicy.choose(true, VideoQualityPolicy.THERMAL_NONE, true))
        assertEquals(CaptureQuality.P720, VideoQualityPolicy.choose(true, VideoQualityPolicy.THERMAL_MODERATE, false))
        assertEquals(CaptureQuality.P540, VideoQualityPolicy.choose(true, VideoQualityPolicy.THERMAL_SEVERE, false))
    }
}
