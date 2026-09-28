package com.nuvio.app.features.player.autosync

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal expect object AutoSyncPreferencesStorage {
    fun loadEnabled(): Boolean?
    fun saveEnabled(enabled: Boolean)
}

/**
 * AutoSync-owned preferences, ported from the desktop fork's AutoSync V2.
 *
 * Kept outside the player settings store and profile sync on purpose, as the desktop fork does:
 * this is a fork feature that needs no settings migration. It is opt-in, and only offered where
 * the platform has a [platformAutoSyncRangeFetcher] to read the video's container with.
 */
internal object AutoSyncPreferences {
    val isSupported: Boolean
        get() = platformAutoSyncRangeFetcher != null

    private val _enabled = MutableStateFlow(AutoSyncPreferencesStorage.loadEnabled() ?: false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val lock = SynchronizedObject()
    private var lastStartupSessionKey: Int? = null
    private var lastStartupPlaybackKey: String? = null

    fun isEnabled(): Boolean = isSupported && _enabled.value

    fun setEnabled(enabled: Boolean) {
        if (_enabled.value == enabled) return
        _enabled.value = enabled
        AutoSyncPreferencesStorage.saveEnabled(enabled)
    }

    /**
     * A run should fire at most once per (player session, playback) pair: the trigger is called on
     * every track refresh. In-memory only -- this is runtime dedupe state, not a stored preference.
     */
    fun claimStartupRun(sessionKey: Int, playbackKey: String): Boolean = synchronized(lock) {
        if (!isEnabled()) return@synchronized false
        if (lastStartupSessionKey == sessionKey && lastStartupPlaybackKey == playbackKey) {
            return@synchronized false
        }
        lastStartupSessionKey = sessionKey
        lastStartupPlaybackKey = playbackKey
        true
    }
}
