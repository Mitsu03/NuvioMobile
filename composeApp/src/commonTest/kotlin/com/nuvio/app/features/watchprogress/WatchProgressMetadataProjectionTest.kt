package com.nuvio.app.features.watchprogress

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.tracking.WatchProgressSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchProgressMetadataProjectionTest {
    @Test
    fun `provider metadata enriches display fields without replacing progress ownership`() {
        val raw = entry(source = WatchProgressSourceSimklPlayback)
        val overlay = ProviderProgressMetadataOverlay()
        overlay.put(
            source = WatchProgressSource.SIMKL,
            key = raw.metadataKey(),
            metadata = metadata(),
        )

        val enriched = overlay.project(
            source = WatchProgressSource.SIMKL,
            entries = listOf(raw),
        ).single()

        assertEquals("Addon title", enriched.title)
        assertEquals("addon-poster", enriched.poster)
        assertEquals("addon-background", enriched.background)
        assertEquals("addon-logo", enriched.logo)
        assertEquals("Episode title", enriched.episodeTitle)
        assertEquals("episode-thumbnail", enriched.episodeThumbnail)
        assertEquals("Episode overview", enriched.pauseDescription)
        assertEquals(raw.progressKey, enriched.progressKey)
        assertEquals(raw.progressPercent, enriched.progressPercent)
        assertEquals(raw.lastPositionMs, enriched.lastPositionMs)
        assertEquals(raw.source, enriched.source)
    }

    @Test
    fun `provider metadata never crosses source boundaries`() {
        val raw = entry(source = WatchProgressSourceSimklPlayback)
        val overlay = ProviderProgressMetadataOverlay()
        overlay.put(
            source = WatchProgressSource.SIMKL,
            key = raw.metadataKey(),
            metadata = metadata(),
        )

        val projected = overlay.project(
            source = WatchProgressSource.TRAKT,
            entries = listOf(raw),
        ).single()

        assertEquals(raw, projected)
        assertNull(projected.background)
        assertNull(projected.episodeThumbnail)
    }

    @Test
    fun `seasonless tracker rows resolve the episode by its position in the run`() {
        val raw = entry(source = WatchProgressSourceSimklPlayback).copy(
            seasonNumber = null,
            episodeNumber = 27,
        )

        val enriched = enrichWatchProgressEntry(current = raw, meta = multiSeasonMetadata())

        assertEquals("S2E2", enriched.episodeTitle)
        assertEquals("thumb-2-2", enriched.episodeThumbnail)
    }

    @Test
    fun `season one tracker rows fall back across seasons when the episode overflows`() {
        val raw = entry(source = WatchProgressSourceSimklPlayback).copy(
            seasonNumber = 1,
            episodeNumber = 27,
        )

        val enriched = enrichWatchProgressEntry(current = raw, meta = multiSeasonMetadata())

        assertEquals("S2E2", enriched.episodeTitle)
    }

    @Test
    fun `an exact season and episode match always wins`() {
        val raw = entry(source = WatchProgressSourceSimklPlayback).copy(
            seasonNumber = 1,
            episodeNumber = 2,
        )

        val enriched = enrichWatchProgressEntry(current = raw, meta = multiSeasonMetadata())

        assertEquals("S1E2", enriched.episodeTitle)
    }

    @Test
    fun `later seasons are never guessed by position`() {
        val raw = entry(source = WatchProgressSourceSimklPlayback).copy(
            seasonNumber = 4,
            episodeNumber = 2,
        )

        val enriched = enrichWatchProgressEntry(current = raw, meta = multiSeasonMetadata())

        assertNull(enriched.episodeThumbnail)
    }

    @Test
    fun `specials are skipped when counting the run`() {
        val raw = entry(source = WatchProgressSourceSimklPlayback).copy(
            seasonNumber = null,
            episodeNumber = 1,
        )

        val enriched = enrichWatchProgressEntry(current = raw, meta = multiSeasonMetadata())

        assertEquals("S1E1", enriched.episodeTitle)
    }

    private fun multiSeasonMetadata(): MetaDetails = MetaDetails(
        id = "tt1234567",
        type = "series",
        name = "Addon title",
        videos = buildList {
            add(
                MetaVideo(
                    id = "tt1234567:0:1",
                    title = "Special",
                    thumbnail = "thumb-0-1",
                    season = 0,
                    episode = 1,
                ),
            )
            (1..25).forEach { episode ->
                add(
                    MetaVideo(
                        id = "tt1234567:1:$episode",
                        title = "S1E$episode",
                        thumbnail = "thumb-1-$episode",
                        season = 1,
                        episode = episode,
                    ),
                )
            }
            (1..13).forEach { episode ->
                add(
                    MetaVideo(
                        id = "tt1234567:2:$episode",
                        title = "S2E$episode",
                        thumbnail = "thumb-2-$episode",
                        season = 2,
                        episode = episode,
                    ),
                )
            }
        },
    )

    private fun entry(source: String): WatchProgressEntry = WatchProgressEntry(
        contentType = "series",
        parentMetaId = "tt1234567",
        parentMetaType = "series",
        videoId = "tt1234567:1:2",
        title = "Simkl title",
        poster = "simkl-poster",
        seasonNumber = 1,
        episodeNumber = 2,
        lastPositionMs = 40_000L,
        durationMs = 100_000L,
        lastUpdatedEpochMs = 500L,
        progressPercent = 40f,
        source = source,
        progressKey = "simkl-playback:10",
    )

    private fun metadata(): MetaDetails = MetaDetails(
        id = "tt1234567",
        type = "series",
        name = "Addon title",
        poster = "addon-poster",
        background = "addon-background",
        logo = "addon-logo",
        description = "Show overview",
        videos = listOf(
            MetaVideo(
                id = "tt1234567:1:2",
                title = "Episode title",
                thumbnail = "episode-thumbnail",
                season = 1,
                episode = 2,
                overview = "Episode overview",
            ),
        ),
    )
}
