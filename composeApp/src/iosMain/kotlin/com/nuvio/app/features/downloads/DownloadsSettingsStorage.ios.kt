package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSUserDefaults

internal actual object DownloadsSettingsStorage {
    private const val downloadDirectoryKey = "download_directory"
    private const val seedRatioKey = "seed_ratio"
    private const val openDownloadsOnStartKey = "open_downloads_on_start"

    actual fun loadDownloadDirectory(): String? =
        loadString(downloadDirectoryKey)?.takeIf { it.isNotBlank() }

    actual fun saveDownloadDirectory(path: String?) {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(downloadDirectoryKey)
        if (path.isNullOrBlank()) {
            defaults.removeObjectForKey(key)
        } else {
            defaults.setObject(path, forKey = key)
        }
    }

    actual fun loadSeedRatio(): Double? =
        loadString(seedRatioKey)?.toDoubleOrNull()

    actual fun saveSeedRatio(ratio: Double) {
        saveString(seedRatioKey, ratio.toString())
    }

    actual fun loadOpenDownloadsOnStart(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(openDownloadsOnStartKey)
        return if (defaults.objectForKey(key) != null) defaults.boolForKey(key) else null
    }

    actual fun saveOpenDownloadsOnStart(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(openDownloadsOnStartKey))
    }

    private fun loadString(keyBase: String): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(keyBase))

    private fun saveString(keyBase: String, value: String) {
        NSUserDefaults.standardUserDefaults.setObject(value, forKey = ProfileScopedKey.of(keyBase))
    }
}
