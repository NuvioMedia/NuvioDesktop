package com.nuvio.app.features.player.desktop

import com.nuvio.app.desktop.DesktopRuntimeLog
import java.util.concurrent.atomic.AtomicInteger

internal object MacOsDisplayWakeLock {
    private val isMacOs: Boolean
        get() = System.getProperty("os.name")?.lowercase()?.contains("mac") == true ||
            System.getProperty("os.name")?.lowercase()?.contains("darwin") == true

    private val referenceCount = AtomicInteger(0)
    private var caffeinateProcess: Process? = null

    fun acquire(reason: String): Boolean {
        if (!isMacOs) return false
        val previous = referenceCount.getAndIncrement()
        if (previous > 0) {
            DesktopRuntimeLog.info("wakeLock(macOS): acquire nested reason=$reason count=${previous + 1}")
            return true
        }
        return try {
            val pb = ProcessBuilder(
                "caffeinate", "-dimsu", "-t", "86400"
            ).redirectErrorStream(true)
            caffeinateProcess = pb.start()
            DesktopRuntimeLog.info("wakeLock(macOS): acquired reason=$reason")
            true
        } catch (e: Exception) {
            referenceCount.set(0)
            DesktopRuntimeLog.error("wakeLock(macOS): acquire failed reason=$reason", e)
            false
        }
    }

    fun release(reason: String) {
        if (!isMacOs) return
        while (true) {
            val current = referenceCount.get()
            if (current <= 0) {
                referenceCount.set(0)
                DesktopRuntimeLog.info("wakeLock(macOS): release ignored reason=$reason count=0")
                return
            }
            val next = current - 1
            if (!referenceCount.compareAndSet(current, next)) continue
            if (next > 0) {
                DesktopRuntimeLog.info("wakeLock(macOS): release nested reason=$reason count=$next")
                return
            }
            try {
                caffeinateProcess?.destroy()
                caffeinateProcess?.waitFor()
            } catch (e: Exception) {
                DesktopRuntimeLog.error("wakeLock(macOS): release failed reason=$reason", e)
            }
            caffeinateProcess = null
            DesktopRuntimeLog.info("wakeLock(macOS): released reason=$reason")
            return
        }
    }
}
