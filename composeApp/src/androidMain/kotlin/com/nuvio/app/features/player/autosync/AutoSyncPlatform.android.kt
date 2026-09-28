package com.nuvio.app.features.player.autosync

// Automatic subtitle sync is wired into the iOS player only; see AutoSyncRangeFetcher.android.kt.
internal actual object AutoSyncPreferencesStorage {
    actual fun loadEnabled(): Boolean? = null

    actual fun saveEnabled(enabled: Boolean) = Unit
}

internal actual fun writeAutoSyncSubtitleFile(fileName: String, contents: String): String? = null
