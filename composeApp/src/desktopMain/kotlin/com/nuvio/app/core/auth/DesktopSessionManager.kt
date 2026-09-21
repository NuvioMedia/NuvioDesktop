package com.nuvio.app.core.auth

import com.nuvio.app.core.storage.DesktopStorage
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class DesktopSessionManager : SessionManager {
    private val store = DesktopStorage.store("nuvio_auth")
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    override suspend fun saveSession(session: UserSession) {
        val sessionJson = json.encodeToString(session)
        store.putString(SESSION_KEY, sessionJson)
    }

    override suspend fun loadSession(): UserSession? {
        val sessionJson = store.getString(SESSION_KEY) ?: return null
        return runCatching {
            json.decodeFromString<UserSession>(sessionJson)
        }.onFailure {
            store.remove(SESSION_KEY)
        }.getOrNull()
    }

    override suspend fun deleteSession() {
        store.remove(SESSION_KEY)
    }

    private companion object {
        private const val SESSION_KEY = "supabase_session"
    }
}
