package com.nuvio.app.features.player.desktop

import com.nuvio.app.desktop.DesktopRuntimeLog
import java.util.concurrent.atomic.AtomicInteger

internal object LinuxDisplayWakeLock {
    private val isLinux: Boolean
        get() = System.getProperty("os.name")?.lowercase()?.contains("nux") == true

    private val referenceCount = AtomicInteger(0)
    private var cookie: Long = -1L

    fun acquire(reason: String): Boolean {
        if (!isLinux) return false
        val previous = referenceCount.getAndIncrement()
        if (previous > 0) {
            DesktopRuntimeLog.info("wakeLock(Linux): acquire nested reason=$reason count=${previous + 1}")
            return true
        }
        val result = inhibitScreensaver(reason)
        return if (result != null) {
            cookie = result
            DesktopRuntimeLog.info("wakeLock(Linux): acquired cookie=$cookie reason=$reason")
            true
        } else {
            referenceCount.set(0)
            DesktopRuntimeLog.warn("wakeLock(Linux): acquire failed reason=$reason")
            false
        }
    }

    fun release(reason: String) {
        if (!isLinux) return
        while (true) {
            val current = referenceCount.get()
            if (current <= 0) {
                referenceCount.set(0)
                DesktopRuntimeLog.info("wakeLock(Linux): release ignored reason=$reason count=0")
                return
            }
            val next = current - 1
            if (!referenceCount.compareAndSet(current, next)) continue
            if (next > 0) {
                DesktopRuntimeLog.info("wakeLock(Linux): release nested reason=$reason count=$next")
                return
            }
            if (cookie >= 0) {
                unInhibitScreensaver(cookie)
                DesktopRuntimeLog.info("wakeLock(Linux): released cookie=$cookie reason=$reason")
                cookie = -1L
            }
            return
        }
    }

    private fun inhibitScreensaver(reason: String): Long? {
        val process = try {
            ProcessBuilder(
                "dbus-send", "--session",
                "--dest=org.freedesktop.ScreenSaver",
                "--type=method_call", "--print-reply",
                "/org/freedesktop/ScreenSaver",
                "org.freedesktop.ScreenSaver.Inhibit",
                "string:Nuvio Desktop", "string:$reason"
            ).redirectErrorStream(true).start()
        } catch (e: Exception) {
            DesktopRuntimeLog.error("wakeLock(Linux): dbus-send not found", e)
            return null
        }
        val output = try {
            process.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            DesktopRuntimeLog.error("wakeLock(Linux): read dbus output failed", e)
            return null
        }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            DesktopRuntimeLog.warn("wakeLock(Linux): dbus-send exit=$exitCode output=$output")
            return null
        }
        val cookie = COOKIE_REGEX.find(output)?.groupValues?.get(1)?.toLongOrNull()
        if (cookie == null) {
            DesktopRuntimeLog.warn("wakeLock(Linux): could not parse cookie from output=$output")
        }
        return cookie
    }

    private fun unInhibitScreensaver(cookie: Long) {
        try {
            val process = ProcessBuilder(
                "dbus-send", "--session",
                "--dest=org.freedesktop.ScreenSaver",
                "--type=method_call",
                "/org/freedesktop/ScreenSaver",
                "org.freedesktop.ScreenSaver.UnInhibit",
                "uint32:$cookie"
            ).redirectErrorStream(true).start()
            process.waitFor()
        } catch (e: Exception) {
            DesktopRuntimeLog.error("wakeLock(Linux): unInhibit failed", e)
        }
    }

    private val COOKIE_REGEX = Regex("uint32\\s+(\\d+)")
}
