package com.nuvio.app.features.servers

import com.nuvio.app.core.storage.DesktopStorage

internal actual object ServerStorage {
    private val store = DesktopStorage.store("server_credentials")
    private const val indexKey = "__keys"

    actual fun read(key: String): String? = store.getString(key)

    actual fun write(key: String, value: String?) {
        val keys = store.getStringSet(indexKey).orEmpty()
        if (value == null) {
            store.remove(key)
            store.putStringSet(indexKey, keys - key)
        } else {
            store.putString(key, value)
            store.putStringSet(indexKey, keys + key)
        }
    }

    actual fun clear() {
        store.removeAll(store.getStringSet(indexKey).orEmpty() + indexKey)
    }
}
