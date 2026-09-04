package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.IntSize
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.player.desktop.NativePlayerBridge
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
) = Unit

@Composable
actual fun rememberIsInPictureInPicture(): Boolean = false

@Composable
actual fun rememberPlayerGestureController(): PlayerGestureController? = null

private class DesktopKeepAwakeController : AutoCloseable {
    private var caffeinateProcess: Process? = null
    private var windowsDisplaySleepInhibited = false
    private var linuxInhibitCookie: Long? = null
    private var linuxInhibitProcess: Process? = null
    private var linuxInhibitExecutor: ExecutorService? = null

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
            DesktopHostOs.LINUX -> setLinuxInhibitEnabled(enabled)
            DesktopHostOs.UNKNOWN -> Unit
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

    // Subprocess calls below block, so route them off the Compose thread.
    private fun linuxExecutor(): ExecutorService =
        linuxInhibitExecutor ?: Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "nuvio-linux-screensaver-inhibit").apply { isDaemon = true }
        }.also { linuxInhibitExecutor = it }

    // Held together, not as an either/or fallback: testing on KDE Plasma showed a successful
    // D-Bus Inhibit call alone doesn't stop the screen lock, only systemd-inhibit does - other
    // DEs may lean on the D-Bus call instead, so both run.
    private fun setLinuxInhibitEnabled(enabled: Boolean) {
        linuxExecutor().execute {
            if (enabled) {
                if (linuxInhibitCookie == null) tryStartDbusScreenSaverInhibit()
                if (linuxInhibitProcess?.isAlive != true) tryStartSystemdInhibit()
            } else {
                stopDbusScreenSaverInhibit()
                stopSystemdInhibit()
            }
        }
    }

    // Same freedesktop ScreenSaver D-Bus call mpv/VLC/browsers use - KDE, GNOME, and XFCE all honor it.
    private fun tryStartDbusScreenSaverInhibit(): Boolean {
        linuxInhibitCookie = runCatching {
            val (exitCode, output) = runDbusSendBlocking(
                "--print-reply=literal",
                "--dest=org.freedesktop.ScreenSaver",
                "/org/freedesktop/ScreenSaver",
                "org.freedesktop.ScreenSaver.Inhibit",
                "string:Nuvio",
                "string:Media playback",
            )
            // --print-reply=literal still prefixes the value with its D-Bus type name (e.g.
            // "uint32 19346"), it isn't a bare number - take the last token.
            output.trim().substringAfterLast(' ').takeIf { exitCode == 0 && it.isNotEmpty() }?.toLongOrNull()
        }.getOrNull()
        return linuxInhibitCookie != null
    }

    private fun stopDbusScreenSaverInhibit() {
        val cookie = linuxInhibitCookie ?: return
        runCatching {
            runDbusSendBlocking(
                "--dest=org.freedesktop.ScreenSaver",
                "/org/freedesktop/ScreenSaver",
                "org.freedesktop.ScreenSaver.UnInhibit",
                "uint32:$cookie",
            )
        }
        linuxInhibitCookie = null
    }

    // If dbus-daemon never replies, readText() below blocks forever before a waitFor() timeout
    // would even get a chance to fire, so the watchdog thread is the thing that actually kills it.
    private fun runDbusSendBlocking(vararg args: String): Pair<Int, String> {
        val process = ProcessBuilder("dbus-send", "--session", "--type=method_call", *args)
            .redirectErrorStream(true)
            .start()
        Thread {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
        }.apply {
            isDaemon = true
            start()
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        return exitCode to output
    }

    // Holds a systemd-logind idle/sleep lock for as long as this child process stays alive.
    private fun tryStartSystemdInhibit(): Boolean {
        linuxInhibitProcess = runCatching {
            ProcessBuilder(
                "systemd-inhibit",
                "--what=idle:sleep",
                "--who=Nuvio",
                "--why=Media playback",
                "--mode=block",
                "sleep",
                "infinity",
            ).start()
        }.getOrNull()
        return linuxInhibitProcess?.isAlive == true
    }

    private fun stopSystemdInhibit() {
        linuxInhibitProcess
            ?.takeIf(Process::isAlive)
            ?.destroy()
        linuxInhibitProcess = null
    }

    override fun close() {
        stopCaffeinate()
        setWindowsDisplaySleepInhibited(false)
        linuxInhibitExecutor?.let { executor ->
            executor.execute {
                stopDbusScreenSaverInhibit()
                stopSystemdInhibit()
            }
            executor.shutdown()
        }
    }
}
