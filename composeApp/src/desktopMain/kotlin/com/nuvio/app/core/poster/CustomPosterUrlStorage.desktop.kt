package com.nuvio.app.core.poster

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object CustomPosterUrlStorage {
    private val store = DesktopStorage.store("nuvio_custom_poster_url")

    actual fun loadPattern(): String? =
        store.getString(ProfileScopedKey.of("custom_poster_url_pattern"))

    actual fun savePattern(pattern: String?) {
        store.putString(
            ProfileScopedKey.of("custom_poster_url_pattern"),
            pattern?.trim()?.takeIf(String::isNotBlank),
        )
    }

    actual fun loadEnabledScreens(): Set<String>? =
        store.getStringSet(ProfileScopedKey.of("custom_poster_enabled_screens"))

    actual fun saveEnabledScreens(keys: Set<String>?) {
        val key = ProfileScopedKey.of("custom_poster_enabled_screens")
        if (keys == null) store.remove(key) else store.putStringSet(key, keys)
    }
}
