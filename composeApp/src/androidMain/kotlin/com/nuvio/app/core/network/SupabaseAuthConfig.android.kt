package com.nuvio.app.core.network

import io.github.jan.supabase.auth.AuthConfig

internal actual fun AuthConfig.configurePlatformAuth() {
    // Platform default SessionManager works correctly (Android SharedPreferences)
}
