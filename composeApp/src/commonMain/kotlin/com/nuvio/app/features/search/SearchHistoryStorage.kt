package com.nuvio.app.features.search

internal expect object SearchHistoryStorage {
    fun loadDirectLinkHistory(profileId: Int): String?
    fun saveDirectLinkHistory(profileId: Int, payload: String)
    fun loadPayload(): String?
    fun savePayload(payload: String)
    fun loadEnabled(): Boolean?
    fun saveEnabled(enabled: Boolean)
}
