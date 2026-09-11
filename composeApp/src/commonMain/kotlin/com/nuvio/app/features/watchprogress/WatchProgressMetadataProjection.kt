package com.nuvio.app.features.watchprogress

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.watching.domain.normalizeSeasonNumber
import com.nuvio.app.features.tracking.WatchProgressSource
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

internal data class WatchProgressMetadataKey(
    val metaId: String,
    val metaType: String,
)

internal fun WatchProgressEntry.metadataKey(): WatchProgressMetadataKey = WatchProgressMetadataKey(
    metaId = parentMetaId,
    metaType = parentMetaType.ifBlank { contentType },
)

internal fun enrichWatchProgressEntry(
    current: WatchProgressEntry,
    meta: MetaDetails,
): WatchProgressEntry {
    val episodeVideo = meta.findEpisodeVideo(
        seasonNumber = current.seasonNumber,
        episodeNumber = current.episodeNumber,
    )
    return current.copy(
        videoId = if (current.source != WatchProgressSourceLocal && episodeVideo != null) {
            episodeVideo.id.takeIf(String::isNotBlank) ?: current.videoId
        } else {
            current.videoId
        },
        title = meta.name.takeIf(String::isNotBlank) ?: current.title,
        poster = meta.poster?.takeIf(String::isNotBlank) ?: current.poster,
        background = meta.background?.takeIf(String::isNotBlank) ?: current.background,
        logo = meta.logo?.takeIf(String::isNotBlank) ?: current.logo,
        episodeTitle = episodeVideo?.title?.takeIf(String::isNotBlank) ?: current.episodeTitle,
        episodeThumbnail = episodeVideo?.thumbnail?.takeIf(String::isNotBlank) ?: current.episodeThumbnail,
        pauseDescription = episodeVideo?.overview?.takeIf(String::isNotBlank)
            ?: meta.description?.takeIf(String::isNotBlank)
            ?: current.pauseDescription,
    )
}

/**
 * Finds the addon episode a progress row refers to.
 *
 * Trackers count anime in one continuous run — seasonless for Simkl, season 1 for Trakt — while
 * meta addons split the same run into seasons. Falling back to the episode's position across the
 * addon's main seasons is what keeps those rows showing an episode title and still image instead
 * of the show's poster. It mirrors the same fallback Next Up already applies when it looks for the
 * episode following a tracker seed.
 */
private fun MetaDetails.findEpisodeVideo(
    seasonNumber: Int?,
    episodeNumber: Int?,
): MetaVideo? {
    if (episodeNumber == null || episodeNumber <= 0) return null
    if (seasonNumber != null) {
        videos.firstOrNull { video ->
            video.season == seasonNumber && video.episode == episodeNumber
        }?.let { return it }
        if (seasonNumber != 1) return null
    }

    val mainEpisodes = videos
        .filter { video -> normalizeSeasonNumber(video.season) > 0 && video.episode != null }
        .sortedWith(
            compareBy(
                { video -> normalizeSeasonNumber(video.season) },
                { video -> video.episode ?: 0 },
            ),
        )
    val spansMultipleSeasons = mainEpisodes
        .mapTo(mutableSetOf()) { video -> normalizeSeasonNumber(video.season) }
        .size > 1
    if (seasonNumber != null && !spansMultipleSeasons) return null
    return mainEpisodes.getOrNull(episodeNumber - 1)
}

internal fun WatchProgressEntry.needsRemoteMetadataEnrichment(): Boolean =
    title.isBlank() ||
        title.equals(parentMetaId, ignoreCase = true) ||
        poster.isNullOrBlank() ||
        background.isNullOrBlank()

internal class ProviderProgressMetadataOverlay {
    private val lock = SynchronizedObject()
    private var source: WatchProgressSource? = null
    private val metadataByKey = mutableMapOf<WatchProgressMetadataKey, MetaDetails>()

    fun clear() {
        synchronized(lock) {
            source = null
            metadataByKey.clear()
        }
    }

    fun put(
        source: WatchProgressSource,
        key: WatchProgressMetadataKey,
        metadata: MetaDetails,
    ): Boolean = synchronized(lock) {
        if (this.source != source) {
            this.source = source
            metadataByKey.clear()
        }
        val previous = metadataByKey.put(key, metadata)
        previous != metadata
    }

    fun project(
        source: WatchProgressSource,
        entries: Collection<WatchProgressEntry>,
    ): List<WatchProgressEntry> {
        val metadata = synchronized(lock) {
            if (this.source == source) metadataByKey.toMap() else emptyMap()
        }
        if (metadata.isEmpty()) return entries.toList()
        return entries.map { entry ->
            metadata[entry.metadataKey()]
                ?.let { meta -> enrichWatchProgressEntry(current = entry, meta = meta) }
                ?: entry
        }
    }
}
