package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.IntSize
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.player.desktop.NativePlayerBridge
import com.nuvio.app.features.player.desktop.DesktopPlayerPictureInPicture

@Composable
actual fun LockPlayerToLandscape() = Unit

@Composable
actual fun EnterImmersivePlayerMode(keepScreenAwake: Boolean) {
    val keepAwakeController = remember { DesktopKeepAwakeController() }

    SideEffect {
        keepAwakeController.setEnabled(keepScreenAwake)
    }

    DisposableEffect(keepAwakeController) {
        onDispose {
            keepAwakeController.close()
        }
    }
}

@Composable
actual fun ManagePlayerPictureInPicture(
    isPlaying: Boolean,
    videoSize: IntSize,
) {
    SideEffect {
        DesktopPlayerPictureInPicture.update(isPlaying = isPlaying, videoSize = videoSize)
    }

    DisposableEffect(Unit) {
        onDispose {
            DesktopPlayerPictureInPicture.clear()
        }
    }
}

@Composable
actual fun rememberIsInPictureInPicture(): Boolean {
    val version = DesktopPlayerPictureInPicture.changes.collectAsState()
    version.value
    return DesktopPlayerPictureInPicture.isEnabled
}

actual fun togglePlayerPictureInPicture() {
    DesktopPlayerPictureInPicture.toggle()
}

@Composable
actual fun rememberPlayerGestureController(): PlayerGestureController? = null

private class DesktopKeepAwakeController : AutoCloseable {
    private var caffeinateProcess: Process? = null
    private var windowsDisplaySleepInhibited = false

    fun setEnabled(enabled: Boolean) {
        when (DesktopHostOs.current) {
            DesktopHostOs.MACOS -> {
                if (enabled) {
                    startCaffeinate()
                } else {
                    stopCaffeinate()
                }
            }

            DesktopHostOs.WINDOWS -> setWindowsDisplaySleepInhibited(enabled)
            DesktopHostOs.LINUX, DesktopHostOs.UNKNOWN -> Unit
        }
    }

    private fun startCaffeinate() {
        if (caffeinateProcess?.isAlive == true) return

        val currentPid = ProcessHandle.current().pid().toString()
        caffeinateProcess = runCatching {
            ProcessBuilder(
                "/usr/bin/caffeinate",
                "-d",
                "-i",
                "-w",
                currentPid,
            ).start()
        }.getOrNull()
    }

    private fun stopCaffeinate() {
        caffeinateProcess
            ?.takeIf(Process::isAlive)
            ?.destroy()
        caffeinateProcess = null
    }

    private fun setWindowsDisplaySleepInhibited(inhibited: Boolean) {
        if (windowsDisplaySleepInhibited == inhibited) return

        val applied = runCatching {
            NativePlayerBridge.setWindowsDisplaySleepInhibited(inhibited)
        }.getOrDefault(false)
        if (applied) {
            windowsDisplaySleepInhibited = inhibited
        }
    }

    override fun close() {
        stopCaffeinate()
        setWindowsDisplaySleepInhibited(false)
    }
}
