package com.nuvio.app.features.search

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object SearchHistoryStorage {
    private val store = DesktopStorage.store("nuvio_search_history")
    private const val enabledKey = "recent_searches_enabled"

    actual fun loadDirectLinkHistory(profileId: Int): String? =
        store.getString(ProfileScopedKey.of("direct_link_history", profileId))

    actual fun saveDirectLinkHistory(profileId: Int, payload: String) {
        store.putString(ProfileScopedKey.of("direct_link_history", profileId), payload)
    }

    actual fun loadPayload(): String? =
        store.getString(ProfileScopedKey.of("search_history"))

    actual fun savePayload(payload: String) {
        store.putString(ProfileScopedKey.of("search_history"), payload)
    }

    actual fun loadEnabled(): Boolean? = store.getBoolean(ProfileScopedKey.of(enabledKey))

    actual fun saveEnabled(enabled: Boolean) {
        store.putBoolean(ProfileScopedKey.of(enabledKey), enabled)
    }
}
