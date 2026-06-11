package com.nuvio.app.features.player

import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class KeybindEntry(
    val action: String,
    val keyCode: Int,
    val modifiers: Int = 0,
)

@Serializable
data class KeybindsConfig(
    val binds: List<KeybindEntry> = defaultKeybinds(),
) {
    companion object {
        fun defaultKeybinds(): List<KeybindEntry> = listOf(
            KeybindEntry("toggle_fullscreen", java.awt.event.KeyEvent.VK_F),
            KeybindEntry("toggle_app_fullscreen", java.awt.event.KeyEvent.VK_F11),
            KeybindEntry("exit_fullscreen", java.awt.event.KeyEvent.VK_ESCAPE),
            KeybindEntry("play_pause", java.awt.event.KeyEvent.VK_SPACE),
            KeybindEntry("seek_forward_10s", java.awt.event.KeyEvent.VK_RIGHT),
            KeybindEntry("seek_backward_10s", java.awt.event.KeyEvent.VK_LEFT),
            KeybindEntry("volume_up", java.awt.event.KeyEvent.VK_UP),
            KeybindEntry("volume_down", java.awt.event.KeyEvent.VK_DOWN),
            KeybindEntry("mute", java.awt.event.KeyEvent.VK_M),
            KeybindEntry("cycle_speed", java.awt.event.KeyEvent.VK_R),
            KeybindEntry("next_episode", java.awt.event.KeyEvent.VK_N),
            KeybindEntry("skip_intro", java.awt.event.KeyEvent.VK_S),
        )
    }
}

object KeybindsStorage {
    private const val storeName = "nuvio_keybinds"
    private const val configKey = "keybinds_v1"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val store by lazy { DesktopStorage.store(storeName) }

    fun load(): KeybindsConfig {
        val raw = store.getString(configKey) ?: return KeybindsConfig()
        return runCatching { json.decodeFromString<KeybindsConfig>(raw) }
            .getOrDefault(KeybindsConfig())
            .withDefaultActions()
    }

    fun save(config: KeybindsConfig) {
        val raw = json.encodeToString(config)
        store.putString(configKey, raw)
    }

    fun getKeyCode(action: String): Int? = load().binds
        .firstOrNull { it.action == action }?.keyCode

    fun actionForKeyCode(keyCode: Int, modifiers: Int = 0): String? {
        val disallowedModifiers = java.awt.event.KeyEvent.CTRL_DOWN_MASK or
            java.awt.event.KeyEvent.ALT_DOWN_MASK or
            java.awt.event.KeyEvent.META_DOWN_MASK or
            java.awt.event.KeyEvent.ALT_GRAPH_DOWN_MASK
        return load().binds.firstOrNull { bind ->
            bind.keyCode == keyCode &&
                if (bind.modifiers == 0) {
                    modifiers and disallowedModifiers == 0
                } else {
                    bind.modifiers == modifiers
                }
        }?.action
    }

    private fun KeybindsConfig.withDefaultActions(): KeybindsConfig {
        val savedByAction = binds.associateBy { it.action }
        val normalized = KeybindsConfig.defaultKeybinds().map { defaultEntry ->
            val saved = savedByAction[defaultEntry.action] ?: return@map defaultEntry
            if (saved.action == "cycle_speed" && saved.keyCode == java.awt.event.KeyEvent.VK_CLOSE_BRACKET) {
                defaultEntry
            } else {
                saved
            }
        }
        return copy(binds = normalized)
    }
}
