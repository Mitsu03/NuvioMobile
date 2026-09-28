package com.nuvio.app.features.player.autosync

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToFile

private const val ENABLED_KEY = "autosync_v2_enabled"

internal actual object AutoSyncPreferencesStorage {
    actual fun loadEnabled(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        return if (defaults.objectForKey(ENABLED_KEY) != null) defaults.boolForKey(ENABLED_KEY) else null
    }

    actual fun saveEnabled(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ENABLED_KEY)
    }
}

@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
internal actual fun writeAutoSyncSubtitleFile(fileName: String, contents: String): String? {
    val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
        .firstOrNull() as? String ?: return null
    val directory = "$caches/autosync"
    if (!NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)) return null
    val path = "$directory/$fileName"
    if (!NSString.create(string = contents).writeToFile(path, true, NSUTF8StringEncoding, null)) return null
    return NSURL.fileURLWithPath(path).absoluteString
}
