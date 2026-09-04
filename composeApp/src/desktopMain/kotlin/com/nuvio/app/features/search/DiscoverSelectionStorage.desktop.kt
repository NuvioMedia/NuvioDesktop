package com.nuvio.app.features.search

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object DiscoverSelectionStorage {
    private val store = DesktopStorage.store("nuvio_discover_selection")

    actual fun loadCatalogKey(): String? =
        store.getString(ProfileScopedKey.of("discover_catalog_key"))

    actual fun saveCatalogKey(catalogKey: String) {
        store.putString(ProfileScopedKey.of("discover_catalog_key"), catalogKey)
    }
}
