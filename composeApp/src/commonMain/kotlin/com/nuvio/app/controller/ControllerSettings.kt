package com.nuvio.app.controller

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Controller preferences (glyph style, Steam on-screen keyboard, background input).
 *
 * Only platforms with gamepad support install a [Storage]; the settings UI is shown when
 * [isSupported] is true.
 */
object ControllerSettings {
    interface Storage {
        fun getString(key: String): String?
        fun putString(key: String, value: String)
        fun getBoolean(key: String): Boolean?
        fun putBoolean(key: String, value: Boolean)
    }

    /** Read-only description of the runtime environment, filled in by the platform. */
    data class Environment(
        val steamDetected: Boolean = false,
        val steamDeck: Boolean = false,
        val steamKeyboardAvailable: Boolean = false,
    )

    private const val KEY_GLYPHS = "glyph_style"
    private const val KEY_STEAM_KEYBOARD = "steam_keyboard"
    private const val KEY_IGNORE_BACKGROUND = "ignore_background_input"

    @Volatile
    private var storage: Storage? = null

    val isSupported: Boolean
        get() = storage != null

    private val _environment = MutableStateFlow(Environment())
    val environment: StateFlow<Environment> = _environment.asStateFlow()

    private val _steamKeyboardEnabled = MutableStateFlow(true)
    val steamKeyboardEnabled: StateFlow<Boolean> = _steamKeyboardEnabled.asStateFlow()

    private val _ignoreBackgroundInput = MutableStateFlow(false)
    val ignoreBackgroundInput: StateFlow<Boolean> = _ignoreBackgroundInput.asStateFlow()

    val glyphPreference: StateFlow<ControllerGlyphPreference>
        get() = ControllerManager.glyphPreference

    fun install(storage: Storage, environment: Environment) {
        this.storage = storage
        _environment.value = environment
        ControllerManager.setGlyphPreference(
            ControllerGlyphPreference.fromStorage(storage.getString(KEY_GLYPHS)),
        )
        _steamKeyboardEnabled.value = storage.getBoolean(KEY_STEAM_KEYBOARD) ?: true
        _ignoreBackgroundInput.value = storage.getBoolean(KEY_IGNORE_BACKGROUND) ?: false
    }

    fun setGlyphPreference(preference: ControllerGlyphPreference) {
        ControllerManager.setGlyphPreference(preference)
        storage?.putString(KEY_GLYPHS, preference.storageValue)
    }

    fun setSteamKeyboardEnabled(enabled: Boolean) {
        _steamKeyboardEnabled.value = enabled
        storage?.putBoolean(KEY_STEAM_KEYBOARD, enabled)
    }

    fun setIgnoreBackgroundInput(enabled: Boolean) {
        _ignoreBackgroundInput.value = enabled
        storage?.putBoolean(KEY_IGNORE_BACKGROUND, enabled)
    }
}
