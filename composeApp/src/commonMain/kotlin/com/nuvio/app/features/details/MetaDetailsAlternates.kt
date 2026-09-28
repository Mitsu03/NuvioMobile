package com.nuvio.app.features.details

import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.CancellationException

/**
 * Metadata from the first ids the tracker knows a show under, when the show's own id falls short.
 *
 * An answer is not always a usable one: for a show that has just premiered, Cinemeta often lists
 * only the first episode, while an anime addon answering for the tracker's MAL id lists them all.
 * Home and the player both need the episode after the one being watched, so both look here.
 */
internal suspend fun fetchAlternateMeta(
    type: String,
    id: String,
    accept: (MetaDetails) -> Boolean,
): MetaDetails? {
    for (alternateId in WatchProgressRepository.alternateContentIdsForMetadata(id)) {
        val alternate = fetchMetaQuietly(type, alternateId) ?: continue
        if (accept(alternate)) return alternate
    }
    return null
}

internal suspend fun fetchMetaQuietly(type: String, id: String): MetaDetails? = try {
    MetaDetailsRepository.fetch(type = type, id = id)
} catch (error: Throwable) {
    if (error is CancellationException) throw error
    null
}

internal fun MetaDetails.hasEpisodeAfter(seasonNumber: Int, episodeNumber: Int): Boolean =
    videos.hasEpisodeAfter(seasonNumber, episodeNumber)

internal fun List<MetaVideo>.hasEpisodeAfter(seasonNumber: Int, episodeNumber: Int): Boolean =
    any { video ->
        val season = video.season ?: return@any false
        val episode = video.episode ?: return@any false
        season > seasonNumber || (season == seasonNumber && episode > episodeNumber)
    }
