package com.nuvio.app.features.mdblist

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.decodeSyncBoolean
import com.nuvio.app.core.sync.decodeSyncString
import com.nuvio.app.core.sync.encodeSyncBoolean
import com.nuvio.app.core.sync.encodeSyncString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal actual object MdbListSettingsStorage {
    private const val enabledKey = "mdblist_enabled"
    private const val apiKey = "mdblist_api_key"
    private const val useImdbKey = "mdblist_use_imdb"
    private const val useTmdbKey = "mdblist_use_tmdb"
    private const val useTomatoesKey = "mdblist_use_tomatoes"
    private const val useMetacriticKey = "mdblist_use_metacritic"
    private const val useTraktKey = "mdblist_use_trakt"
    private const val useLetterboxdKey = "mdblist_use_letterboxd"
    private const val useAudienceKey = "mdblist_use_audience"
    private const val useMalKey = "mdblist_use_mal"
    private const val showOnHeroKey = "mdblist_show_on_hero"
    private val syncKeys = listOf(
        enabledKey,
        apiKey,
        useImdbKey,
        useTmdbKey,
        useTomatoesKey,
        useMetacriticKey,
        useTraktKey,
        useLetterboxdKey,
        useAudienceKey,
        useMalKey,
        showOnHeroKey,
    )
    private val store = DesktopStorage.store("nuvio_mdblist_settings")

    actual fun loadEnabled(): Boolean? = loadBoolean(enabledKey)
    actual fun saveEnabled(enabled: Boolean) = saveBoolean(enabledKey, enabled)
    actual fun loadApiKey(): String? = loadString(apiKey)
    actual fun saveApiKey(apiKey: String) = saveString(this.apiKey, apiKey)
    actual fun loadUseImdb(): Boolean? = loadBoolean(useImdbKey)
    actual fun saveUseImdb(enabled: Boolean) = saveBoolean(useImdbKey, enabled)
    actual fun loadUseTmdb(): Boolean? = loadBoolean(useTmdbKey)
    actual fun saveUseTmdb(enabled: Boolean) = saveBoolean(useTmdbKey, enabled)
    actual fun loadUseTomatoes(): Boolean? = loadBoolean(useTomatoesKey)
    actual fun saveUseTomatoes(enabled: Boolean) = saveBoolean(useTomatoesKey, enabled)
    actual fun loadUseMetacritic(): Boolean? = loadBoolean(useMetacriticKey)
    actual fun saveUseMetacritic(enabled: Boolean) = saveBoolean(useMetacriticKey, enabled)
    actual fun loadUseTrakt(): Boolean? = loadBoolean(useTraktKey)
    actual fun saveUseTrakt(enabled: Boolean) = saveBoolean(useTraktKey, enabled)
    actual fun loadUseLetterboxd(): Boolean? = loadBoolean(useLetterboxdKey)
    actual fun saveUseLetterboxd(enabled: Boolean) = saveBoolean(useLetterboxdKey, enabled)
    actual fun loadUseAudience(): Boolean? = loadBoolean(useAudienceKey)
    actual fun saveUseAudience(enabled: Boolean) = saveBoolean(useAudienceKey, enabled)
    actual fun loadUseMal(): Boolean? = loadBoolean(useMalKey)
    actual fun saveUseMal(enabled: Boolean) = saveBoolean(useMalKey, enabled)
    actual fun loadShowOnHero(): Boolean? = loadBoolean(showOnHeroKey)
    actual fun saveShowOnHero(enabled: Boolean) = saveBoolean(showOnHeroKey, enabled)

    private fun loadString(key: String): String? = store.getString(ProfileScopedKey.of(key))
    private fun saveString(key: String, value: String) = store.putString(ProfileScopedKey.of(key), value)
    private fun loadBoolean(key: String): Boolean? = store.getBoolean(ProfileScopedKey.of(key))
    private fun saveBoolean(key: String, value: Boolean) = store.putBoolean(ProfileScopedKey.of(key), value)

    actual fun exportToSyncPayload(): JsonObject = buildJsonObject {
        loadEnabled()?.let { put(enabledKey, encodeSyncBoolean(it)) }
        loadApiKey()?.let { put(apiKey, encodeSyncString(it)) }
        loadUseImdb()?.let { put(useImdbKey, encodeSyncBoolean(it)) }
        loadUseTmdb()?.let { put(useTmdbKey, encodeSyncBoolean(it)) }
        loadUseTomatoes()?.let { put(useTomatoesKey, encodeSyncBoolean(it)) }
        loadUseMetacritic()?.let { put(useMetacriticKey, encodeSyncBoolean(it)) }
        loadUseTrakt()?.let { put(useTraktKey, encodeSyncBoolean(it)) }
        loadUseLetterboxd()?.let { put(useLetterboxdKey, encodeSyncBoolean(it)) }
        loadUseAudience()?.let { put(useAudienceKey, encodeSyncBoolean(it)) }
        loadUseMal()?.let { put(useMalKey, encodeSyncBoolean(it)) }
        loadShowOnHero()?.let { put(showOnHeroKey, encodeSyncBoolean(it)) }
    }

    actual fun replaceFromSyncPayload(payload: JsonObject) {
        store.removeAll(syncKeys.map(ProfileScopedKey::of))
        payload.decodeSyncBoolean(enabledKey)?.let(::saveEnabled)
        payload.decodeSyncString(apiKey)?.let(::saveApiKey)
        payload.decodeSyncBoolean(useImdbKey)?.let(::saveUseImdb)
        payload.decodeSyncBoolean(useTmdbKey)?.let(::saveUseTmdb)
        payload.decodeSyncBoolean(useTomatoesKey)?.let(::saveUseTomatoes)
        payload.decodeSyncBoolean(useMetacriticKey)?.let(::saveUseMetacritic)
        payload.decodeSyncBoolean(useTraktKey)?.let(::saveUseTrakt)
        payload.decodeSyncBoolean(useLetterboxdKey)?.let(::saveUseLetterboxd)
        payload.decodeSyncBoolean(useAudienceKey)?.let(::saveUseAudience)
        payload.decodeSyncBoolean(useMalKey)?.let(::saveUseMal)
        payload.decodeSyncBoolean(showOnHeroKey)?.let(::saveShowOnHero)
    }
}
