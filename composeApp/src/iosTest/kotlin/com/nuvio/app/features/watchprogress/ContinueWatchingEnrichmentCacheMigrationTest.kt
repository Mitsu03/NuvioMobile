package com.nuvio.app.features.watchprogress

import kotlin.test.Test

class ContinueWatchingEnrichmentCacheMigrationTest {
    @Test
    fun legacyNextUpDatesAreRefreshedWhileInProgressPlaybackIsRetained() {
        assertLegacyNextUpMigration()
    }
}
