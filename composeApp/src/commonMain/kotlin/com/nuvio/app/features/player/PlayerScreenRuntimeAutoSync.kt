package com.nuvio.app.features.player

import com.nuvio.app.features.player.autosync.AutoSyncApply
import com.nuvio.app.features.player.autosync.AutoSyncPreferences
import com.nuvio.app.features.player.autosync.AutomaticSubtitleSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_auto_sync_applied
import nuvio.composeapp.generated.resources.player_auto_sync_failed
import nuvio.composeapp.generated.resources.player_auto_sync_running

// Long enough to outlast the run itself; the result replaces it as soon as there is one.
private const val AUTO_SYNC_RUNNING_MESSAGE_MS = 15_000L
private const val AUTO_SYNC_RESULT_MESSAGE_MS = 3_500L

/**
 * Automatic (algorithmic) subtitle sync -- AutoSync V2 -- as opposed to the manual capture-a-line
 * tool in the subtitle panel. Called from [refreshTracks] and from the addon-subtitle selection
 * handler: refreshTracks alone fires when the subtitle panel is opened, which is before the user
 * has picked anything, so a fresh selection would otherwise never be synced. Safe to call
 * repeatedly because [AutoSyncPreferences.claimStartupRun] dedupes on (player session, subtitle).
 *
 * Every path reports to the viewer: the run is invisible otherwise, and "it did nothing" and "it
 * could not run" look identical on screen.
 */
internal fun PlayerScreenRuntime.maybeRunAutomaticSubtitleSync() {
    if (!AutoSyncPreferences.isEnabled()) return

    val subtitle = selectedAddonSubtitle ?: return
    val sourceUrl = playerControllerSourceUrl?.takeIf { it.isNotBlank() } ?: return

    val playbackKey = "${playbackSession.videoId}|${subtitle.id}|${subtitle.url}"
    if (!AutoSyncPreferences.claimStartupRun(hashCode(), playbackKey)) return

    scope.launch {
        showAutoSyncMessage(
            GestureFeedbackState(messageRes = Res.string.player_auto_sync_running),
            durationMs = AUTO_SYNC_RUNNING_MESSAGE_MS,
        )
        val correction = try {
            AutomaticSubtitleSync.run(
                sourceUrl = sourceUrl,
                sourceHeaders = activeSourceHeaders,
                subtitle = subtitle,
            )?.let { outcome ->
                AutoSyncApply.apply(
                    runtime = this@maybeRunAutomaticSubtitleSync,
                    result = outcome.result,
                    originalCues = outcome.originalCues,
                )
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        }

        showAutoSyncMessage(
            if (correction == null) {
                GestureFeedbackState(messageRes = Res.string.player_auto_sync_failed, isDanger = true)
            } else {
                GestureFeedbackState(
                    messageRes = Res.string.player_auto_sync_applied,
                    messageArgs = listOf(correction.offsetLabel()),
                )
            },
            durationMs = AUTO_SYNC_RESULT_MESSAGE_MS,
        )
    }
}

private fun PlayerScreenRuntime.showAutoSyncMessage(feedback: GestureFeedbackState, durationMs: Long) {
    showGestureFeedback(feedback.copy(icon = GestureFeedbackIcon.Subtitles), durationMs)
}
