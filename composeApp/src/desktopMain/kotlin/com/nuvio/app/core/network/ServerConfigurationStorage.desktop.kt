package com.nuvio.app.core.network

import com.nuvio.app.core.storage.DesktopStorage

internal actual object ServerConfigurationStorage {
    private val store = DesktopStorage.store("server_configuration")
    private const val customEnabledKey = "custom_enabled"
    private const val backendUrlKey = "backend_url"
    private const val publishableKey = "publishable_key"
    private const val emailPasswordAuthKey = "email_password_auth"
    private const val tvLoginKey = "tv_login"
    private const val discoveryUrlKey = "discovery_url"

    actual fun loadCustom(): ServerConfiguration? {
        if (store.getBoolean(customEnabledKey) != true) return null
        val backendUrl = store.getString(backendUrlKey)?.trim().orEmpty()
        val key = store.getString(publishableKey)?.trim().orEmpty()
        val emailPasswordAuth = store.getBoolean(emailPasswordAuthKey) ?: false
        val tvLogin = store.getBoolean(tvLoginKey) ?: false
        if (backendUrl.isBlank() || key.isBlank() || !emailPasswordAuth) return null
        return ServerConfiguration(
            backendUrl = backendUrl,
            publishableKey = key,
            capabilities = ServerCapabilities(
                emailPasswordAuth = emailPasswordAuth,
                tvLogin = tvLogin,
            ),
            isCustom = true,
            discoveryUrl = store.getString(discoveryUrlKey),
        )
    }

    actual fun saveCustom(configuration: ServerConfiguration): Boolean {
        store.putBoolean(customEnabledKey, true)
        store.putString(backendUrlKey, configuration.backendUrl)
        store.putString(publishableKey, configuration.publishableKey)
        store.putBoolean(emailPasswordAuthKey, configuration.capabilities.emailPasswordAuth)
        store.putBoolean(tvLoginKey, configuration.capabilities.tvLogin)
        store.putString(discoveryUrlKey, configuration.discoveryUrl)
        return true
    }

    actual fun useOfficial(): Boolean {
        store.removeAll(
            listOf(
                customEnabledKey,
                backendUrlKey,
                publishableKey,
                emailPasswordAuthKey,
                tvLoginKey,
                discoveryUrlKey,
            ),
        )
        return true
    }
}
