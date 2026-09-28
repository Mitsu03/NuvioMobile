package com.nuvio.app.features.player.autosync

import com.nuvio.app.features.player.SUBTITLE_DELAY_MAX_MS
import com.nuvio.app.features.player.SUBTITLE_DELAY_MIN_MS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `setSubtitleDelay` coerces into +/-60s, so routing a larger offset through it applies the clamp
 * instead of the offset -- which looks, to the viewer, exactly like a subtitle that never synced.
 * A real run on desktop produced -102300 ms and landed on -60000.
 */
class AutoSyncApplyPathTest {
    @Test
    fun `a constant offset within the delay range uses the delay sink`() {
        assertTrue(AutoSyncApply.fitsDelayOnly(scale = 1.0, offsetMs = 2_500))
        assertTrue(AutoSyncApply.fitsDelayOnly(scale = 1.0, offsetMs = SUBTITLE_DELAY_MIN_MS))
        assertTrue(AutoSyncApply.fitsDelayOnly(scale = 1.0, offsetMs = SUBTITLE_DELAY_MAX_MS))
    }

    @Test
    fun `an offset the delay sink would clamp does not use it`() {
        assertFalse(AutoSyncApply.fitsDelayOnly(scale = 1.0, offsetMs = -102_300))
        assertFalse(AutoSyncApply.fitsDelayOnly(scale = 1.0, offsetMs = SUBTITLE_DELAY_MIN_MS - 1))
        assertFalse(AutoSyncApply.fitsDelayOnly(scale = 1.0, offsetMs = SUBTITLE_DELAY_MAX_MS + 1))
    }

    @Test
    fun `real scale drift never uses the delay sink`() {
        assertFalse(AutoSyncApply.fitsDelayOnly(scale = 1.042, offsetMs = 0))
    }

    // String.format is JVM-only; the port formats by hand, so pin the output down.
    @Test
    fun `srt timestamps are zero padded`() {
        assertEquals("00:00:00,000", AutoSyncApply.formatSrtTimestamp(0L))
        assertEquals("01:02:03,004", AutoSyncApply.formatSrtTimestamp(3_723_004L))
        assertEquals("00:00:00,000", AutoSyncApply.formatSrtTimestamp(-5L))
    }

    @Test
    fun `offset label shows a signed shift in tenths of a second`() {
        assertEquals("-102.3s", AutoSyncCorrection(offsetMs = -102_300, retimedFile = true).offsetLabel())
        assertEquals("+1.5s", AutoSyncCorrection(offsetMs = 1_500, retimedFile = false).offsetLabel())
        assertEquals("+0.0s", AutoSyncCorrection(offsetMs = 0, retimedFile = false).offsetLabel())
        assertEquals("+3.0s", AutoSyncCorrection(offsetMs = 2_987, retimedFile = false).offsetLabel())
    }
}
