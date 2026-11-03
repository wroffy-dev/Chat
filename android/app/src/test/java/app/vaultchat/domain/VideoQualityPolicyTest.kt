package app.vaultchat.domain

import app.vaultchat.domain.call.CaptureQuality
import app.vaultchat.domain.call.VideoQualityPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoQualityPolicyTest {
    @Test
    fun `Auto sends at most 720p, and 540p on a very hot phone`() {
        // Capped at 720p even on a phone that could capture 1080p30 (owner, 2026-11-03).
        assertEquals(CaptureQuality.P720, VideoQualityPolicy.choose(true, VideoQualityPolicy.THERMAL_NONE, false))
        assertEquals(CaptureQuality.P720, VideoQualityPolicy.choose(true, VideoQualityPolicy.THERMAL_LIGHT, false))
        assertEquals(CaptureQuality.P720, VideoQualityPolicy.choose(false, VideoQualityPolicy.THERMAL_NONE, false))
        assertEquals(CaptureQuality.P720, VideoQualityPolicy.choose(true, VideoQualityPolicy.THERMAL_NONE, true))
        assertEquals(CaptureQuality.P720, VideoQualityPolicy.choose(true, VideoQualityPolicy.THERMAL_MODERATE, false))
        assertEquals(CaptureQuality.P540, VideoQualityPolicy.choose(true, VideoQualityPolicy.THERMAL_SEVERE, false))
    }
}
