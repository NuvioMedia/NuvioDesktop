package com.nuvio.app.features.mdblist

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.features.profiles.MAX_PROFILES

internal actual object PlatformMdbListAuthPersistence : MdbListAuthPersistence {
    private const val payloadKey = "mdblist_auth_payload"
    private val store = DesktopStorage.store("nuvio_mdblist_auth")

    actual override fun read(profileId: Int): String? =
        store.getString(ProfileScopedKey.of(payloadKey, profileId))

    actual override fun write(profileId: Int, value: String?) {
        store.putString(ProfileScopedKey.of(payloadKey, profileId), value)
    }

    actual override fun clear() {
        store.removeAll((1..MAX_PROFILES).map { ProfileScopedKey.of(payloadKey, it) })
    }
}
