package com.nuvio.app.features.player.autosync

import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.player.AddonSubtitle
import com.nuvio.app.features.player.PlayerSubtitleCueParser
import com.nuvio.app.features.player.SubtitleSyncCue
import com.nuvio.app.features.player.sanitizePlaybackHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AutoSync V2 orchestrator, ported from the desktop fork -- itself a deliberately simplified
 * version of NuvioTV's `AutomaticSubtitleSync.kt` (multi-candidate alternative-subtitle
 * prefetch/ranking, SDH-aware margin relaxation, cross-run caching, a delay-only preflight).
 *
 * It keeps the two things that determine correctness -- the reference-timeline load and the
 * retiming algorithm -- and reduces the orchestration around them to the minimum: load the
 * embedded reference, download and parse the one subtitle the caller asked to sync, retime it.
 */
internal object AutomaticSubtitleSync {
    private const val MIN_TARGET_CUES = 8

    /**
     * Owns its own dispatcher rather than trusting the caller's: the container indexing and the
     * retiming matcher are both seconds of CPU work on a full-length episode, and the natural
     * caller is the player's composition scope, which is the main thread.
     */
    suspend fun run(
        sourceUrl: String,
        sourceHeaders: Map<String, String>,
        subtitle: AddonSubtitle,
        rangeFetcher: AutoSyncRangeFetcher? = platformAutoSyncRangeFetcher,
        fetchSubtitleText: suspend (url: String, headers: Map<String, String>) -> String =
            ::httpGetTextWithHeaders,
    ): AutoSyncRunOutcome? = withContext(Dispatchers.Default) {
        runOffMainThread(sourceUrl, sourceHeaders, subtitle, rangeFetcher, fetchSubtitleText)
    }

    private suspend fun runOffMainThread(
        sourceUrl: String,
        sourceHeaders: Map<String, String>,
        subtitle: AddonSubtitle,
        rangeFetcher: AutoSyncRangeFetcher?,
        fetchSubtitleText: suspend (url: String, headers: Map<String, String>) -> String,
    ): AutoSyncRunOutcome? {
        val timeline = EmbeddedSubtitleTimelineLoader.load(sourceUrl, sourceHeaders, rangeFetcher)
        if (timeline == null || timeline.tracks.isEmpty()) {
            AutoSyncDebugLog.info { "run skipped reason=no-embedded-reference url=${sourceUrl.take(80)}" }
            return null
        }

        val referenceTrack = selectReferenceTrack(timeline.tracks, subtitle.language)
        if (referenceTrack == null) {
            AutoSyncDebugLog.info { "run skipped reason=no-usable-reference-track" }
            return null
        }

        val targetCues = try {
            val body = fetchSubtitleText(subtitle.url, sanitizePlaybackHeaders(sourceHeaders))
            PlayerSubtitleCueParser.parse(body, subtitle.url)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            AutoSyncDebugLog.warn { "run failed reason=target-subtitle-fetch-failed" }
            return null
        }

        if (targetCues.size < MIN_TARGET_CUES) {
            AutoSyncDebugLog.info { "run skipped reason=target-too-short count=${targetCues.size}" }
            return null
        }

        val result = AutoSyncTimelineRetimer.retime(
            reference = referenceTrack.cues,
            target = targetCues,
            coarseScale = 1.0,
            coarseInterceptMs = 0.0,
            discoverAlignment = true,
            referenceEstimatedEndStartsMs = referenceTrack.estimatedEndStartsMs,
        )

        if (result == null) {
            AutoSyncDebugLog.info { "run skipped reason=no-confident-match" }
            return null
        }

        return AutoSyncRunOutcome(result = result, originalCues = targetCues)
    }

    /**
     * NuvioTV ranks candidate reference tracks by SDH-likelihood, cue density, and dialogue-
     * completeness heuristics. Simplified here to: prefer a non-forced track whose language
     * matches the subtitle being synced, otherwise fall back to whichever track has the most
     * indexed cues (a reasonable proxy for "most complete dialogue track").
     */
    private fun selectReferenceTrack(
        tracks: List<ReferenceTrack>,
        targetLanguage: String?,
    ): ReferenceTrack? {
        val usable = tracks.filterNot { it.isForced }.ifEmpty { tracks }
        val languageMatch = targetLanguage
            ?.takeIf { it.isNotBlank() }
            ?.let { language -> usable.firstOrNull { it.language?.startsWith(language, ignoreCase = true) == true } }
        return languageMatch ?: usable.maxByOrNull { it.cues.size }
    }
}

internal data class AutoSyncRunOutcome(
    val result: AutoSyncTimelineRetimeResult,
    val originalCues: List<SubtitleSyncCue>,
)
