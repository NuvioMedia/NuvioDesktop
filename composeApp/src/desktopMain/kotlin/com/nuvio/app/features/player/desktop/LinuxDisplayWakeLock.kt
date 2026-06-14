package com.nuvio.app.features.player.desktop

import com.nuvio.app.desktop.DesktopRuntimeLog
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal object LinuxDisplayWakeLock {
    private val isLinux: Boolean
        get() = System.getProperty("os.name")?.lowercase()?.contains("nux") == true

    private val referenceCount = AtomicInteger(0)
    private val cookie = AtomicLong(-1L)
    private var pending: Thread? = null

    fun acquire(reason: String): Boolean {
        if (!isLinux) return false
        val previous = referenceCount.getAndIncrement()
        if (previous > 0) {
            DesktopRuntimeLog.info("wakeLock(Linux): acquire nested reason=$reason count=${previous + 1}")
            return true
        }
        pending = Thread {
            val result = inhibitScreensaver(reason)
            if (result != null) {
                cookie.set(result)
                DesktopRuntimeLog.info("wakeLock(Linux): acquired cookie=$result reason=$reason")
            } else {
                referenceCount.set(0)
                DesktopRuntimeLog.warn("wakeLock(Linux): acquire failed reason=$reason")
            }
        }.apply { isDaemon = true; start() }
        return true
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
            val c = cookie.getAndSet(-1L)
            if (c >= 0) {
                unInhibitScreensaver(c)
                DesktopRuntimeLog.info("wakeLock(Linux): released cookie=$c reason=$reason")
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
            process.destroyForcibly()
            return null
        }
        try {
            val ok = process.waitFor(5, TimeUnit.SECONDS)
            if (!ok) {
                process.destroyForcibly()
                DesktopRuntimeLog.warn("wakeLock(Linux): dbus-send timed out")
                return null
            }
            if (process.exitValue() != 0) {
                DesktopRuntimeLog.warn("wakeLock(Linux): dbus-send exit=${process.exitValue()} output=$output")
                return null
            }
        } catch (e: Exception) {
            DesktopRuntimeLog.error("wakeLock(Linux): waitFor failed", e)
            return null
        }
        val c = COOKIE_REGEX.find(output)?.groupValues?.get(1)?.toLongOrNull()
        if (c == null) {
            DesktopRuntimeLog.warn("wakeLock(Linux): could not parse cookie from output=$output")
        }
        return c
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
            process.waitFor(3, TimeUnit.SECONDS)
        } catch (e: Exception) {
            DesktopRuntimeLog.error("wakeLock(Linux): unInhibit failed", e)
        }
    }

    private val COOKIE_REGEX = Regex("uint32\\s+(\\d+)")
}
