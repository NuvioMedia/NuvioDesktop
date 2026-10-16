package com.nuvio.app.features.downloads

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object DownloadsSettingsStorage {
    private const val preferencesName = "download_settings"
    private const val downloadDirectoryKey = "download_directory"
    private const val seedRatioKey = "seed_ratio"
    private const val openDownloadsOnStartKey = "open_downloads_on_start"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadDownloadDirectory(): String? =
        preferences?.getString(ProfileScopedKey.of(downloadDirectoryKey), null)
            ?.takeIf { it.isNotBlank() }

    actual fun saveDownloadDirectory(path: String?) {
        val editor = preferences?.edit() ?: return
        if (path.isNullOrBlank()) {
            editor.remove(ProfileScopedKey.of(downloadDirectoryKey))
        } else {
            editor.putString(ProfileScopedKey.of(downloadDirectoryKey), path)
        }
        editor.apply()
    }

    actual fun loadSeedRatio(): Double? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(seedRatioKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getString(key, null)?.toDoubleOrNull()
            } else {
                null
            }
        }

    actual fun saveSeedRatio(ratio: Double) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(seedRatioKey), ratio.toString())
            ?.apply()
    }

    actual fun loadOpenDownloadsOnStart(): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(openDownloadsOnStartKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getBoolean(key, true)
            } else {
                null
            }
        }

    actual fun saveOpenDownloadsOnStart(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(openDownloadsOnStartKey), enabled)
            ?.apply()
    }
}
