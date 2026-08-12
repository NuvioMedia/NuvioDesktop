package com.nuvio.app.features.player.desktop

import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent

private const val VK_VOLUME_MUTE_CODE = 0xAD
private const val VK_VOLUME_DOWN_CODE = 0xAE
private const val VK_VOLUME_UP_CODE = 0xAF
private const val VK_MEDIA_NEXT_TRACK_CODE = 0xB0
private const val VK_MEDIA_PREV_TRACK_CODE = 0xB1
private const val VK_MEDIA_STOP_CODE = 0xB2
private const val VK_MEDIA_PLAY_PAUSE_CODE = 0xB3
private const val VK_PLAY_CODE = 0xFA
private const val VK_PAUSE_CODE = 0x13

internal object DesktopPlayerMediaKeyDispatcher {
    private val dispatcher = KeyEventDispatcher { event ->
        handle(event)
    }

    @Volatile
    private var activeController: NativePlayerController? = null
    @Volatile
    private var installed = false

    fun register(controller: NativePlayerController) {
        activeController = controller
        installIfNeeded()
    }

    fun unregister(controller: NativePlayerController) {
        if (activeController === controller) {
            activeController = null
        }
        uninstallIfPossible()
    }

    private fun installIfNeeded() {
        if (installed) return
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
        installed = true
    }

    private fun uninstallIfPossible() {
        if (activeController != null || !installed) return
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher)
        installed = false
    }

    private fun handle(event: KeyEvent): Boolean {
        if (event.id != KeyEvent.KEY_PRESSED) return false
        val controller = activeController ?: return false
        val keyCode = if (event.keyCode != KeyEvent.VK_UNDEFINED) event.keyCode else event.extendedKeyCode
        return when (keyCode) {
            VK_MEDIA_PLAY_PAUSE_CODE,
            VK_PLAY_CODE -> {
                controller.togglePlaybackFromShortcut()
                true
            }
            VK_MEDIA_STOP_CODE,
            VK_PAUSE_CODE -> {
                controller.pause()
                true
            }
            VK_MEDIA_NEXT_TRACK_CODE -> {
                controller.seekByShortcut(10_000L)
                true
            }
            VK_MEDIA_PREV_TRACK_CODE -> {
                controller.seekByShortcut(-10_000L)
                true
            }
            VK_VOLUME_UP_CODE -> {
                controller.adjustVolumeByShortcut(5f)
                true
            }
            VK_VOLUME_DOWN_CODE -> {
                controller.adjustVolumeByShortcut(-5f)
                true
            }
            VK_VOLUME_MUTE_CODE -> {
                controller.toggleMuteFromShortcut()
                true
            }
            else -> false
        }
    }
}