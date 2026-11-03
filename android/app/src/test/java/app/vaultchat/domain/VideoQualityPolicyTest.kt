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

    @Test
    fun `the admin's setting caps what Auto sends, never raises it`() {
        assertEquals(CaptureQuality.P720, VideoQualityPolicy.cap(CaptureQuality.P1080, CaptureQuality.P720))
        assertEquals(CaptureQuality.P1080, VideoQualityPolicy.cap(CaptureQuality.P1080, CaptureQuality.P1080))
        assertEquals(CaptureQuality.P540, VideoQualityPolicy.cap(CaptureQuality.P540, CaptureQuality.P1080))
        assertEquals(CaptureQuality.P720, VideoQualityPolicy.parse("720p"))
        assertEquals(null, VideoQualityPolicy.parse("4k"))
    }
}
