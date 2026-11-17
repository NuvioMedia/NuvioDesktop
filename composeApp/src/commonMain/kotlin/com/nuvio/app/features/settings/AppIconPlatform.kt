package com.nuvio.app.features.settings

internal expect object AppIconPlatform {
    val isSupported: Boolean
    val requiresCloseConfirmation: Boolean
    fun loadSelectedIconKey(): String?
    fun saveSelectedIconKey(key: String)
    fun currentIconName(): String?
    suspend fun activateIcon(name: String?): Boolean
}
