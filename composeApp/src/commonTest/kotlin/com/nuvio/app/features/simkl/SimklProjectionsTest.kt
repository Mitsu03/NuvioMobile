package com.nuvio.app.features.simkl

import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMembershipRemovalImpact
import com.nuvio.app.features.watchprogress.WatchProgressSourceSimklPlayback
import com.nuvio.app.features.watchprogress.shouldTreatAsInProgressForContinueWatching
import com.nuvio.app.features.watchprogress.shouldUseAsCompletedSeedForContinueWatching
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimklProjectionsTest {
    @Test
    fun `library presentation uses status names without a provider prefix`() {
        assertEquals(
            listOf("Watching", "Plan to Watch", "On Hold", "Completed", "Dropped"),
            simklLibraryStatusDefinitions.map { definition -> definition.title },
        )
    }

    @Test
    fun `library projection exposes every populated status with attribution`() {
        val plan = entry(
            type = SimklMediaType.MOVIES,
            status = SimklListStatus.PLAN_TO_WATCH,
            id = 53536,
            imdb = "tt0181852",
            slug = "terminator-3-rise-of-the-machines",
            addedAt = "2023-11-14T22:13:20Z",
        )
        val completed = entry(
            type = SimklMediaType.MOVIES,
            status = SimklListStatus.COMPLETED,
            id = 53434,
            imdb = "tt0068646",
        )
        val watching = entry(
            type = SimklMediaType.SHOWS,
            status = SimklListStatus.WATCHING,
            id = 2090,
            imdb = "tt1520211",
        )

        val projection = SimklSyncSnapshot(entries = listOf(plan, completed, watching)).toSimklLibraryProjection()
        val watchingDefinition = simklLibraryStatusDefinitions.single { definition ->
            definition.status == SimklListStatus.WATCHING
        }
        val planDefinition = simklLibraryStatusDefinitions.single { definition ->
            definition.status == SimklListStatus.PLAN_TO_WATCH
        }
        val completedDefinition = simklLibraryStatusDefinitions.single { definition ->
            definition.status == SimklListStatus.COMPLETED
        }

        assertEquals(
            listOf(watchingDefinition.key, planDefinition.key, completedDefinition.key),
            projection.sections.map { section -> section.type },
        )
        assertEquals(3, projection.items.size)
        val item = projection.items.single { candidate -> candidate.id == "tt0181852" }
        assertEquals("tt0181852", item.id)
        assertEquals(setOf(planDefinition.key), item.listKeys)
        assertEquals("simkl", item.trackingProviderId)
        assertEquals("simkl:53536", item.trackingProviderItemId)
        assertEquals(
            "https://simkl.com/movies/53536/terminator-3-rise-of-the-machines",
            item.trackingSourceUrl,
        )
        assertTrue(item.poster.orEmpty().contains("simkl.in/posters/12/poster_m.webp"))
        assertFalse(item.poster.orEmpty().contains("_w.webp"))
        assertEquals(1_700_000_000_000L, item.savedAtEpochMs)
        assertEquals(
            setOf(completedDefinition.key),
            projection.items.single { candidate -> candidate.id == "tt0068646" }.listKeys,
        )
        assertEquals(
            setOf(watchingDefinition.key),
            projection.items.single { candidate -> candidate.id == "tt1520211" }.listKeys,
        )
        assertTrue(watchingDefinition.isMembershipDestination)
        assertTrue(planDefinition.isMembershipDestination)
        assertFalse(completedDefinition.isMembershipDestination)
    }

    @Test
    fun `watched projection includes movie events rich episodes and completed series marker`() {
        val movie = entry(
            type = SimklMediaType.MOVIES,
            status = SimklListStatus.COMPLETED,
            id = 53536,
            imdb = "tt0181852",
            lastWatchedAt = "2023-11-14T22:13:20Z",
        )
        val richShow = entry(
            type = SimklMediaType.SHOWS,
            status = SimklListStatus.WATCHING,
            id = 2090,
            imdb = "tt1520211",
            seasons = listOf(
                SimklSeason(
                    number = 1,
                    episodes = listOf(
                        SimklEpisode(number = 1, watchedAt = "2023-11-14T23:13:20Z"),
                        SimklEpisode(number = 2, watchedAt = null),
                    ),
                ),
            ),
        )
        val summaryOnlyCompletedShow = entry(
            type = SimklMediaType.ANIME,
            status = SimklListStatus.COMPLETED,
            id = 39687,
            imdb = "tt2560140",
            lastWatchedAt = "2023-11-15T00:13:20Z",
        )

        val projection = SimklSyncSnapshot(
            entries = listOf(movie, richShow, summaryOnlyCompletedShow),
        ).toSimklWatchedProjection()

        assertEquals(3, projection.items.size)
        val movieEvent = assertNotNull(projection.items.singleOrNull { it.type == "movie" })
        assertEquals("simkl", movieEvent.trackingProviderId)
        assertEquals("simkl:53536", movieEvent.trackingProviderItemId)
        assertEquals("https://simkl.com/movies/53536", movieEvent.trackingSourceUrl)
        val episode = projection.items.single { it.season == 1 && it.episode == 1 }
        assertEquals("tt1520211", episode.id)
        assertFalse(projection.items.any { it.episode == 2 })
        assertTrue(projection.items.any { it.id == "tt2560140" && it.season == null })
        assertTrue(projection.fullyWatchedSeriesKeys.any { "tt2560140" in it })
    }

    @Test
    fun `summary counters do not fabricate exact episode markers`() {
        val summary = entry(
            type = SimklMediaType.SHOWS,
            status = SimklListStatus.WATCHING,
            id = 2090,
            imdb = "tt1520211",
            lastWatchedAt = "2023-11-14T23:13:20Z",
        ).copy(
            lastWatched = "S01E03",
            nextToWatch = "S01E04",
            watchedEpisodesCount = 3,
            totalEpisodesCount = 6,
        )

        val projection = SimklSyncSnapshot(entries = listOf(summary)).toSimklWatchedProjection()

        assertTrue(projection.items.isEmpty())
        assertTrue(projection.fullyWatchedSeriesKeys.isEmpty())
    }

    @Test
    fun `anime watched projection prefers mapped tvdb coordinates`() {
        val anime = entry(
            type = SimklMediaType.ANIME,
            status = SimklListStatus.WATCHING,
            id = 439744,
            imdb = "tt2560140",
            seasons = listOf(
                SimklSeason(
                    number = 1,
                    episodes = listOf(
                        SimklEpisode(
                            number = 4,
                            watchedAt = "2023-11-14T23:13:20Z",
                            tvdb = SimklEpisodeMapping(season = 2, episode = 4),
                        ),
                    ),
                ),
            ),
        )

        val watched = SimklSyncSnapshot(entries = listOf(anime))
            .toSimklWatchedProjection()
            .items
            .single()

        assertEquals(2, watched.season)
        assertEquals(4, watched.episode)
    }

    @Test
    fun `playback projection preserves Simkl session identity and percentage`() {
        val session = SimklPlaybackSession(
            id = 12345,
            progress = 42.2,
            pausedAt = "2024-04-30T22:13:00.250Z",
            type = "episode",
            episode = SimklPlaybackEpisode(
                season = 1,
                number = 3,
                title = "Chapter Three",
            ),
            show = media(id = 39687, imdb = "tt4574334", runtime = 50),
        )

        val entry = SimklSyncSnapshot(playback = listOf(session)).toSimklProgressEntries().single()

        assertEquals("tt4574334", entry.parentMetaId)
        assertEquals(1, entry.seasonNumber)
        assertEquals(3, entry.episodeNumber)
        assertEquals(42.2f, entry.progressPercent)
        assertEquals(3_000_000L, entry.durationMs)
        assertEquals(1_266_000L, entry.lastPositionMs)
        assertEquals("simkl-playback:12345", entry.progressKey)
        assertEquals(WatchProgressSourceSimklPlayback, entry.source)
        assertEquals("simkl", entry.trackingProviderId)
        assertEquals("simkl:39687", entry.trackingProviderItemId)
        assertEquals("https://simkl.com/tv/39687", entry.trackingSourceUrl)
        assertTrue(entry.poster.orEmpty().contains("simkl.in/posters/12/poster_m.webp"))
        assertFalse(entry.isCompleted)
        assertEquals(1_714_515_180_250L, entry.lastUpdatedEpochMs)
    }

    @Test
    fun `media reference retains anime catalog and all accepted ids`() {
        val anime = entry(
            type = SimklMediaType.ANIME,
            status = SimklListStatus.WATCHING,
            id = 39687,
            imdb = "tt2560140",
            mal = 16498,
        )
        val snapshot = SimklSyncSnapshot(entries = listOf(anime))

        val reference = snapshot.mediaReference(
            contentId = "tt2560140",
            contentType = "series",
            season = 2,
            episode = 4,
            posterUrl = "https://catalog.example/anime.webp",
        )

        assertEquals(TrackingMediaKind.ANIME, reference.kind)
        assertEquals(39687L, reference.ids.simkl)
        assertEquals(16498L, reference.ids.mal)
        assertEquals(2, reference.episode?.season)
        assertEquals(4, reference.episode?.number)
        assertEquals("https://catalog.example/anime.webp", reference.posterUrl)
    }

    @Test
    fun `clean plan to watch removal needs no destructive confirmation`() {
        val plan = entry(
            type = SimklMediaType.MOVIES,
            status = SimklListStatus.PLAN_TO_WATCH,
            id = 53536,
            imdb = "tt0181852",
        )

        val confirmation = SimklSyncSnapshot(entries = listOf(plan))
            .membershipRemovalConfirmation("tt0181852")

        assertNull(confirmation)
    }

    @Test
    fun `watched or rated Simkl removal requires destructive confirmation`() {
        val watchedPlan = entry(
            type = SimklMediaType.MOVIES,
            status = SimklListStatus.PLAN_TO_WATCH,
            id = 53536,
            imdb = "tt0181852",
            lastWatchedAt = "2023-11-14T22:13:20Z",
        )
        val ratedPlan = entry(
            type = SimklMediaType.MOVIES,
            status = SimklListStatus.PLAN_TO_WATCH,
            id = 53434,
            imdb = "tt0068646",
        ).copy(userRating = 9)
        val episodePlan = entry(
            type = SimklMediaType.SHOWS,
            status = SimklListStatus.PLAN_TO_WATCH,
            id = 2090,
            imdb = "tt1520211",
            seasons = listOf(
                SimklSeason(
                    number = 1,
                    episodes = listOf(
                        SimklEpisode(number = 1, watchedAt = "2023-11-14T22:13:20Z"),
                    ),
                ),
            ),
        )

        val confirmation = assertNotNull(
            SimklSyncSnapshot(entries = listOf(watchedPlan, ratedPlan, episodePlan))
                .membershipRemovalConfirmation("tt0181852"),
        )
        assertNotNull(
            SimklSyncSnapshot(entries = listOf(watchedPlan, ratedPlan, episodePlan))
                .membershipRemovalConfirmation("tt0068646"),
        )
        assertNotNull(
            SimklSyncSnapshot(entries = listOf(watchedPlan, ratedPlan, episodePlan))
                .membershipRemovalConfirmation("tt1520211"),
        )

        assertEquals(
            setOf(
                TrackingMembershipRemovalImpact.WATCHED_HISTORY,
                TrackingMembershipRemovalImpact.RATING,
            ),
            confirmation.impacts,
        )
    }

    @Test
    fun `Simkl default membership toggles only its mutually exclusive status`() {
        val planKey = simklLibraryStatusDefinitions.single { definition ->
            definition.status == SimklListStatus.PLAN_TO_WATCH
        }.key
        val watchingKey = simklLibraryStatusDefinitions.single { definition ->
            definition.status == SimklListStatus.WATCHING
        }.key
        val emptyMembership = simklLibraryStatusDefinitions.associate { definition ->
            definition.key to false
        }

        val added = SimklTrackingLibraryProvider.toggledDefaultMembership(emptyMembership)
        val removed = SimklTrackingLibraryProvider.toggledDefaultMembership(
            emptyMembership + (watchingKey to true),
        )

        assertTrue(added[planKey] == true)
        assertTrue(added.filterKeys { key -> key != planKey }.values.none { it })
        assertTrue(removed.values.none { it })
    }

    @Test
    fun `timestamp parser accepts UTC fractions and rejects invalid calendar values`() {
        assertEquals(0L, parseSimklUtcEpochMs("1970-01-01T00:00:00Z"))
        assertEquals(951_782_400_123L, parseSimklUtcEpochMs("2000-02-29T00:00:00.123Z"))
        assertNull(parseSimklUtcEpochMs("2023-02-29T00:00:00Z"))
        assertNull(parseSimklUtcEpochMs("2024-01-01T00:00:00+01:00"))
    }

    private fun entry(
        type: SimklMediaType,
        status: SimklListStatus,
        id: Long,
        imdb: String? = null,
        mal: Long? = null,
        slug: String? = null,
        addedAt: String? = null,
        lastWatchedAt: String? = null,
        seasons: List<SimklSeason> = emptyList(),
    ): SimklLibraryEntry = SimklLibraryEntry(
        mediaType = type,
        status = status,
        addedToWatchlistAt = addedAt,
        lastWatchedAt = lastWatchedAt,
        seasons = seasons,
        movie = if (type == SimklMediaType.MOVIES) media(id, imdb, mal, slug = slug) else null,
        show = if (type != SimklMediaType.MOVIES) media(id, imdb, mal, slug = slug) else null,
    )

    // ──────────────────────────────────────────────────────────────────────────
    // Summary markers: Simkl reports progress without per-episode history.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `episode marker parses seasoned and flat anime coordinates`() {
        assertEquals(SimklEpisodeMarker(season = 2, episode = 13), parseSimklEpisodeMarker("S2E13"))
        assertEquals(SimklEpisodeMarker(season = 1, episode = 3), parseSimklEpisodeMarker("S01E03"))
        assertEquals(SimklEpisodeMarker(season = null, episode = 13), parseSimklEpisodeMarker("E13"))
        assertEquals(SimklEpisodeMarker(season = 2, episode = 13), parseSimklEpisodeMarker(" s2e13 "))
        assertNull(parseSimklEpisodeMarker("S2"))
        assertNull(parseSimklEpisodeMarker("2x13"))
        assertNull(parseSimklEpisodeMarker(null))
    }

    @Test
    fun `summary marker seeds next up when episode history is missing`() {
        val snapshot = SimklSyncSnapshot(
            entries = listOf(
                summaryEntry(
                    id = 1063491,
                    imdb = "tt5607616",
                    watchedEpisodes = 13,
                    lastWatched = "S2E13",
                ),
            ),
        )

        val seed = snapshot.toSimklProgressEntries().single()

        assertEquals("tt5607616", seed.parentMetaId)
        assertEquals(2, seed.seasonNumber)
        assertEquals(13, seed.episodeNumber)
        assertTrue(seed.isCompleted)
        assertTrue(seed.shouldUseAsCompletedSeedForContinueWatching())
        assertFalse(seed.shouldTreatAsInProgressForContinueWatching())
    }

    @Test
    fun `summary seeds never reach watch history`() {
        val snapshot = SimklSyncSnapshot(
            entries = listOf(
                summaryEntry(
                    id = 1063491,
                    imdb = "tt5607616",
                    watchedEpisodes = 13,
                    lastWatched = "S2E13",
                ),
            ),
        )

        assertTrue(snapshot.toSimklWatchedProjection().items.isEmpty())
    }

    @Test
    fun `flat anime marker without a season lands in the entry's only season`() {
        val snapshot = SimklSyncSnapshot(
            entries = listOf(
                summaryEntry(
                    id = 2125704,
                    imdb = "tt5607616",
                    watchedEpisodes = 6,
                    lastWatched = "E6",
                    seasons = listOf(SimklSeason(3, emptyList())),
                ),
            ),
        )

        val seed = snapshot.toSimklProgressEntries().single()

        assertEquals(3, seed.seasonNumber)
        assertEquals(6, seed.episodeNumber)
    }

    @Test
    fun `exact episode history keeps the summary seed out`() {
        val snapshot = SimklSyncSnapshot(
            entries = listOf(
                summaryEntry(
                    id = 509292,
                    imdb = "tt5607616",
                    watchedEpisodes = 25,
                    lastWatched = "S1E25",
                    seasons = listOf(
                        SimklSeason(
                            1,
                            listOf(SimklEpisode(1, "2023-11-14T23:00:00Z", SimklEpisodeMapping(1, 1))),
                        ),
                    ),
                ),
            ),
        )

        assertTrue(snapshot.toSimklProgressEntries().isEmpty())
        assertEquals(1, snapshot.toSimklWatchedProjection().items.single().episode)
    }

    @Test
    fun `episode rows Simkl returns without coordinates still leave a summary seed`() {
        val snapshot = SimklSyncSnapshot(
            entries = listOf(
                summaryEntry(
                    id = 2743422,
                    imdb = "tt5607616",
                    watchedEpisodes = 5,
                    lastWatched = "E5",
                    seasons = listOf(
                        SimklSeason(
                            number = null,
                            episodes = (1..5).map { episode ->
                                SimklEpisode(episode, "2023-12-01T20:0$episode:00Z")
                            },
                        ),
                    ),
                ),
            ),
        )

        assertTrue(snapshot.toSimklWatchedProjection().items.isEmpty())

        val seed = snapshot.toSimklProgressEntries().single()

        assertEquals(1, seed.seasonNumber)
        assertEquals(5, seed.episodeNumber)
    }

    @Test
    fun `entries hidden from continue watching are not seeded`() {
        listOf(
            SimklListStatus.COMPLETED,
            SimklListStatus.ON_HOLD,
            SimklListStatus.DROPPED,
        ).forEach { status ->
            val snapshot = SimklSyncSnapshot(
                entries = listOf(
                    summaryEntry(
                        id = 1063491,
                        imdb = "tt5607616",
                        watchedEpisodes = 13,
                        lastWatched = "S2E13",
                        status = status,
                    ),
                ),
            )

            assertTrue(
                snapshot.toSimklProgressEntries().isEmpty(),
                "status $status must not seed Next Up",
            )
        }
    }

    @Test
    fun `entries without reported progress are not seeded`() {
        val snapshot = SimklSyncSnapshot(
            entries = listOf(
                summaryEntry(
                    id = 1063491,
                    imdb = "tt5607616",
                    watchedEpisodes = 0,
                    lastWatched = null,
                ),
            ),
        )

        assertTrue(snapshot.toSimklProgressEntries().isEmpty())
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Alternate ids: the airing cour carries an id addons do not serve.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `the airing cour borrows the ids its sibling entries are known under`() {
        val snapshot = SimklSyncSnapshot(entries = reZeroEntries())

        val alternates = snapshot.alternateContentIdsFor("tt36501927")

        assertEquals("tt5607616", alternates.first())
        assertTrue(alternates.contains("kitsu:49746"))
        assertFalse(alternates.contains("tt36501927"))
    }

    @Test
    fun `unknown content has no alternates`() {
        val snapshot = SimklSyncSnapshot(entries = reZeroEntries())

        assertTrue(snapshot.alternateContentIdsFor("tt0000000").isEmpty())
        assertTrue(snapshot.alternateContentIdsFor("  ").isEmpty())
    }

    @Test
    fun `entries of unrelated shows are never offered as alternates`() {
        val unrelated = SimklLibraryEntry(
            mediaType = SimklMediaType.ANIME,
            status = SimklListStatus.WATCHING,
            show = SimklMedia(
                title = "Another show",
                ids = buildJsonObject {
                    put("simkl", 99999)
                    put("imdb", "tt9999999")
                    put("tvdb", "999999")
                },
            ),
        )
        val snapshot = SimklSyncSnapshot(entries = reZeroEntries() + unrelated)

        val alternates = snapshot.alternateContentIdsFor("tt36501927")

        assertFalse(alternates.contains("tt9999999"))
    }

    /** The shape Simkl really returns: one entry per cour, all sharing one TVDB id. */
    private fun reZeroEntries(): List<SimklLibraryEntry> = listOf(
        animeCour(simklId = 509292, imdb = "tt5607616", kitsu = 11209, status = SimklListStatus.COMPLETED),
        animeCour(simklId = 2125704, imdb = "tt5607616", kitsu = 47235, status = SimklListStatus.COMPLETED),
        animeCour(simklId = 2743422, imdb = "tt36501927", kitsu = 49746, status = SimklListStatus.WATCHING),
    )

    private fun animeCour(
        simklId: Long,
        imdb: String,
        kitsu: Long,
        status: SimklListStatus,
    ): SimklLibraryEntry = SimklLibraryEntry(
        mediaType = SimklMediaType.ANIME,
        status = status,
        lastWatchedAt = "2026-08-24T14:44:37Z",
        show = SimklMedia(
            title = "Re:Zero",
            ids = buildJsonObject {
                put("simkl", simklId)
                put("imdb", imdb)
                put("kitsu", kitsu)
                put("tvdb", "305089")
            },
        ),
    )

    private fun summaryEntry(
        id: Long,
        imdb: String,
        watchedEpisodes: Int,
        lastWatched: String?,
        status: SimklListStatus = SimklListStatus.WATCHING,
        seasons: List<SimklSeason> = emptyList(),
    ): SimklLibraryEntry = SimklLibraryEntry(
        mediaType = SimklMediaType.ANIME,
        status = status,
        lastWatchedAt = "2023-12-01T20:00:00Z",
        lastWatched = lastWatched,
        watchedEpisodesCount = watchedEpisodes,
        totalEpisodesCount = 25,
        seasons = seasons,
        show = media(id, imdb),
    )

    private fun media(
        id: Long,
        imdb: String? = null,
        mal: Long? = null,
        runtime: Int? = null,
        slug: String? = null,
    ): SimklMedia = SimklMedia(
        title = "Title $id",
        poster = "12/poster",
        year = 2020,
        runtime = runtime,
        ids = buildJsonObject {
            put("simkl", id)
            imdb?.let { put("imdb", it) }
            mal?.let { put("mal", it) }
            slug?.let { put("slug", it) }
        },
    )
}
