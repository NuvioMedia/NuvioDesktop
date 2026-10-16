package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object DownloadsSettingsStorage {
    private const val downloadDirectoryKey = "download_directory"
    private const val seedRatioKey = "seed_ratio"
    private const val openDownloadsOnStartKey = "open_downloads_on_start"
    private val store = DesktopStorage.store("download_settings")

    actual fun loadDownloadDirectory(): String? =
        store.getString(ProfileScopedKey.of(downloadDirectoryKey))?.takeIf { it.isNotBlank() }

    actual fun saveDownloadDirectory(path: String?) {
        if (path.isNullOrBlank()) {
            store.remove(ProfileScopedKey.of(downloadDirectoryKey))
        } else {
            store.putString(ProfileScopedKey.of(downloadDirectoryKey), path)
        }
    }

    actual fun loadSeedRatio(): Double? =
        store.getString(ProfileScopedKey.of(seedRatioKey))?.toDoubleOrNull()

    actual fun saveSeedRatio(ratio: Double) =
        store.putString(ProfileScopedKey.of(seedRatioKey), ratio.toString())

    actual fun loadOpenDownloadsOnStart(): Boolean? =
        store.getBoolean(ProfileScopedKey.of(openDownloadsOnStartKey))

    actual fun saveOpenDownloadsOnStart(enabled: Boolean) =
        store.putBoolean(ProfileScopedKey.of(openDownloadsOnStartKey), enabled)
}
