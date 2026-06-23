package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.IntSize
import com.nuvio.app.core.ui.DesktopBackHandlers
import com.nuvio.app.features.player.desktop.DesktopHostOs
import java.awt.KeyboardFocusManager
import java.awt.Window

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
            DesktopHostOs.WINDOWS -> startWindowsInhibit()
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
        val systemdProcess = runCatching {
            ProcessBuilder(
                "systemd-inhibit",
                "--what=handle-lid-switch:sleep:idle",
                "--who=Nuvio",
                "--why=Playing video",
                "sleep",
                "infinity",
            ).start()
        }.getOrNull()
        if (systemdProcess != null) return systemdProcess

        if (DesktopHostOs.isWayland) return null

        val windowId = resolveX11WindowId() ?: return null
        return runCatching {
            ProcessBuilder(
                "xdg-screensaver",
                "suspend",
                windowId.toString(),
            ).start()
        }.getOrNull()
    }

    private fun resolveX11WindowId(): Long? {
        return runCatching {
            val window = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
                ?: Window.getWindows().firstOrNull { it.isVisible && it.isActive }
                ?: return null
            val peerField = java.awt.Component::class.java.getDeclaredField("peer")
            peerField.isAccessible = true
            val peer = peerField.get(window) ?: return null
            val method = peer.javaClass.getDeclaredMethod("getAWTView")
            method.isAccessible = true
            val id = (method.invoke(peer) as Number).toLong()
            if (id > 0) id else null
        }.getOrNull()
    }

    private fun startWindowsInhibit(): Process? {
        return runCatching {
            val script = """
                Add-Type -TypeDefinition '
                    using System;
                    using System.Runtime.InteropServices;
                    public class SleepInhibitor {
                        [DllImport("kernel32.dll", SetLastError = true)]
                        public static extern uint SetThreadExecutionState(uint esFlags);
                        public static void PreventSleep() {
                            SetThreadExecutionState(0x80000002);
                        }
                        public static void AllowSleep() {
                            SetThreadExecutionState(0x80000000);
                        }
                    }
                ';
                [SleepInhibitor]::PreventSleep();
                try { Sleep -Timeout 2147483; } finally { [SleepInhibitor]::AllowSleep(); }
            """.trimIndent()
            ProcessBuilder("powershell", "-NoProfile", "-Command", script).start()
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
