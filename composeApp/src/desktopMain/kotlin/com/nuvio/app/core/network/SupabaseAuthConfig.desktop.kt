package com.nuvio.app.core.network

import com.nuvio.app.core.auth.DesktopSessionManager
import io.github.jan.supabase.auth.AuthConfig

internal actual fun AuthConfig.configurePlatformAuth() {
    sessionManager = DesktopSessionManager()
}
