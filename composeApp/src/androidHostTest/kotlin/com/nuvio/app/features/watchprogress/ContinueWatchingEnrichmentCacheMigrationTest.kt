package com.nuvio.app.features.watchprogress

import android.app.Application
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ContinueWatchingEnrichmentCacheMigrationTest {
    @Test
    fun legacyNextUpDatesAreRefreshedWhileInProgressPlaybackIsRetained() {
        ContinueWatchingEnrichmentStorage.initialize(RuntimeEnvironment.getApplication())
        assertLegacyNextUpMigration()
    }
}
