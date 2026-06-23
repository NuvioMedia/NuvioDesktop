package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.IntSize
import com.nuvio.app.core.ui.DesktopBackHandlers
import com.nuvio.app.features.player.desktop.DesktopHostOs

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
    playerSize: IntSize,
) = Unit

@Composable
actual fun rememberPlayerGestureController(): PlayerGestureController? = null

private var lastDesktopBackHandler: (() -> Unit)? = null

actual fun setDesktopBackHandler(handler: (() -> Unit)?) {
    if (lastDesktopBackHandler != null) {
        DesktopBackHandlers.removeBack(lastDesktopBackHandler!!)
    }
    if (handler != null) {
        DesktopBackHandlers.pushBack(handler)
    }
    lastDesktopBackHandler = handler
}

private class DesktopKeepAwakeController : AutoCloseable {
    private var inhibitProcess: Process? = null

    fun setEnabled(enabled: Boolean) {
        if (enabled) {
            startInhibit()
        } else {
            stopInhibit()
        }
    }

    private fun startInhibit() {
        if (inhibitProcess?.isAlive == true) return

        inhibitProcess = when (DesktopHostOs.current) {
            DesktopHostOs.MACOS -> startMacOsInhibit()
            DesktopHostOs.LINUX -> startLinuxInhibit()
            else -> null
        }
    }

    private fun startMacOsInhibit(): Process? {
        val currentPid = ProcessHandle.current().pid().toString()
        return runCatching {
            ProcessBuilder(
                "/usr/bin/caffeinate",
                "-d",
                "-i",
                "-w",
                currentPid,
            ).start()
        }.getOrNull()
    }

    private fun startLinuxInhibit(): Process? {
        return runCatching {
            ProcessBuilder(
                "systemd-inhibit",
                "--what=handle-lid-switch:sleep:idle",
                "--who=Nuvio",
                "--why=Playing video",
                "sleep",
                "infinity",
            ).start()
        }.getOrNull()
    }

    private fun stopInhibit() {
        inhibitProcess
            ?.takeIf(Process::isAlive)
            ?.destroy()
        inhibitProcess = null
    }

    override fun close() {
        stopInhibit()
    }
}
