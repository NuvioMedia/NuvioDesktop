/*
 * Ported from NuvioDeck (https://github.com/kllakm/NuvioDeck), GPLv3.
 */
package com.nuvio.app.controller.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User preference for controller navigation sounds. */
object ControllerSoundSettings {
    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun isEnabled(): Boolean = _enabled.value

    fun setEnabled(value: Boolean) {
        _enabled.value = value
    }
}

/**
 * Navigation feedback sounds.
 *
 * [backend] stays null on platforms without audio support (or when audio initialisation fails),
 * which keeps every call a no-op there.
 */
object ControllerSoundPlayer {
    interface Backend {
        fun playNavigate()
        fun playSelect()
        fun playBack()
    }

    @Volatile
    var backend: Backend? = null

    fun playNavigate() {
        backend?.playNavigate()
    }

    fun playSelect() {
        backend?.playSelect()
    }

    fun playBack() {
        backend?.playBack()
    }
}
