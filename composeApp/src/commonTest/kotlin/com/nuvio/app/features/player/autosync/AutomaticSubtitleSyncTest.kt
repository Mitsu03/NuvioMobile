package com.nuvio.app.features.player.autosync

import com.nuvio.app.features.player.AddonSubtitle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * End-to-end test of the full AutoSync V2 pipeline -- [AutomaticSubtitleSync.run] -- short of the
 * final mpv apply step (which needs a live [com.nuvio.app.features.player.PlayerScreenRuntime] /
 * native player and can't run in a unit test). The video is served by [InMemoryRangeFetcher] and
 * the subtitle by an in-memory text fetch, standing in for the HTTP the app uses. This is the strongest correctness signal available
 * without a real running app: it proves the loader, the target-subtitle download+parse, and the
 * retiming algorithm are wired together correctly and recover the exact injected offset.
 *
 * Scenario: a synthetic MKV whose embedded subtitle Cues are the "true" timeline, and an external
 * .srt whose own timestamps are a constant 3000ms earlier than the embedded truth (i.e. the
 * subtitle as authored displays each line 3s too early and needs a +3000ms delay to correct).
 */
class AutomaticSubtitleSyncTest {

    @Test
    fun recoversAConstantOffsetFromEmbeddedReference() = runBlocking {
        val cueCount = 60
        val trueOffsetMs = 3000L

        // Irregular spacing on purpose: a perfectly uniform cadence is the one case the matcher
        // deliberately treats as ambiguous (a delay of D is indistinguishable from D + k*spacing),
        // so it correctly refuses to commit. Real dialogue timing is irregular; this mirrors the
        // same pseudo-irregular generator AutoSyncTimelineRetimeTest.kt uses (irregularTimeline).
        val referenceStartTimesMs = irregularStartTimes(cueCount, seedMs = trueOffsetMs + 30_000L)
        val mkvBytes = MatroskaFixture.build(
            trackNumber = 1,
            language = "eng",
            cueStartTimesMs = referenceStartTimesMs,
        )

        // Target subtitle as authored: each line trueOffsetMs earlier than the embedded truth.
        val targetStartTimesMs = referenceStartTimesMs.map { it - trueOffsetMs }
        val srtText = buildSrt(targetStartTimesMs, cueDurationMs = 1500L)

        val subtitleUrl = "https://example.test/pipeline.srt"
        val outcome = AutomaticSubtitleSync.run(
            sourceUrl = "https://example.test/pipeline.mkv",
            sourceHeaders = emptyMap(),
            subtitle = AddonSubtitle(
                id = "test-subtitle",
                url = subtitleUrl,
                language = "eng",
                display = "English",
            ),
            rangeFetcher = InMemoryRangeFetcher(mkvBytes),
            fetchSubtitleText = { url, _ ->
                check(url == subtitleUrl) { "unexpected subtitle url $url" }
                srtText
            },
        )

        val result = assertNotNull(outcome, "pipeline returned null for a well-formed scenario")
            .result
        assertTrue(result.confident, "expected a confident match")
        assertEquals(cueCount, result.cues.size)
        assertApproximately(
            expected = trueOffsetMs.toDouble(),
            actual = result.alignmentInterceptMs,
            tolerance = 50.0,
        )
        assertTrue(
            kotlin.math.abs(result.alignmentScale - 1.0) < 0.01,
            "expected near-unity scale, got ${result.alignmentScale}",
        )
    }

    private fun irregularStartTimes(count: Int, seedMs: Long): List<Long> {
        var start = seedMs
        return (0 until count).map { index ->
            if (index > 0) start += 1_400L + ((index * 977L) % 4_300L)
            start
        }
    }

    private fun assertApproximately(expected: Double, actual: Double, tolerance: Double) {
        assertTrue(
            kotlin.math.abs(expected - actual) <= tolerance,
            "expected $expected +/- $tolerance, got $actual",
        )
    }

    private fun buildSrt(startTimesMs: List<Long>, cueDurationMs: Long): String = buildString {
        startTimesMs.forEachIndexed { index, startMs ->
            append(index + 1)
            append('\n')
            append(formatSrtTimestamp(startMs))
            append(" --> ")
            append(formatSrtTimestamp(startMs + cueDurationMs))
            append('\n')
            append("Line ${index + 1}")
            append("\n\n")
        }
    }

    private fun formatSrtTimestamp(ms: Long): String {
        val hours = ms / 3_600_000L
        val minutes = (ms / 60_000L) % 60L
        val seconds = (ms / 1_000L) % 60L
        val millis = ms % 1_000L
        return "${hours.pad(2)}:${minutes.pad(2)}:${seconds.pad(2)},${millis.pad(3)}"
    }

    private fun Long.pad(width: Int): String = toString().padStart(width, '0')
}
