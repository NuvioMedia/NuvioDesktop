package com.nuvio.app.features.player.desktop

import com.nuvio.app.desktop.DesktopRuntimeLog
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.win32.StdCallLibrary
import java.util.concurrent.atomic.AtomicInteger

internal object WindowsDisplayWakeLock {
    private const val ES_CONTINUOUS = 0x80000000.toInt()
    private const val ES_SYSTEM_REQUIRED = 0x00000001
    private const val ES_DISPLAY_REQUIRED = 0x00000002

    private val isWindows: Boolean
        get() = System.getProperty("os.name")?.contains("Windows", ignoreCase = true) == true

    private val referenceCount = AtomicInteger(0)
    private val kernel32: Kernel32? by lazy {
        if (!isWindows) {
            null
        } else {
            runCatching { Native.load("kernel32", Kernel32::class.java) }
                .onFailure { DesktopRuntimeLog.error("wakeLock: cannot load kernel32", it) }
                .getOrNull()
        }
    }

    fun acquire(reason: String): Boolean {
        if (!isWindows) return false
        val native = kernel32 ?: run {
            DesktopRuntimeLog.warn("wakeLock: acquire skipped, kernel32 unavailable reason=$reason")
            return false
        }
        val previous = referenceCount.getAndIncrement()
        if (previous > 0) {
            DesktopRuntimeLog.info("wakeLock: acquire nested reason=$reason count=${previous + 1}")
            return true
        }
        val flags = ES_CONTINUOUS or ES_SYSTEM_REQUIRED or ES_DISPLAY_REQUIRED
        return runCatching {
            native.SetThreadExecutionState(flags)
        }.map { result ->
            if (result == 0) {
                referenceCount.set(0)
                DesktopRuntimeLog.warn("wakeLock: acquire failed reason=$reason")
                false
            } else {
                DesktopRuntimeLog.info("wakeLock: acquired reason=$reason")
                true
            }
        }.getOrElse {
            referenceCount.set(0)
            DesktopRuntimeLog.error("wakeLock: acquire threw reason=$reason", it)
            false
        }
    }

    fun release(reason: String) {
        if (!isWindows) return
        while (true) {
            val current = referenceCount.get()
            if (current <= 0) {
                referenceCount.set(0)
                DesktopRuntimeLog.info("wakeLock: release ignored reason=$reason count=0")
                return
            }
            val next = current - 1
            if (!referenceCount.compareAndSet(current, next)) continue
            if (next > 0) {
                DesktopRuntimeLog.info("wakeLock: release nested reason=$reason count=$next")
                return
            }
            val native = kernel32 ?: run {
                DesktopRuntimeLog.warn("wakeLock: release skipped, kernel32 unavailable reason=$reason")
                return
            }
            runCatching {
                native.SetThreadExecutionState(ES_CONTINUOUS)
            }.onSuccess { result ->
                if (result == 0) {
                    DesktopRuntimeLog.warn("wakeLock: release failed reason=$reason")
                } else {
                    DesktopRuntimeLog.info("wakeLock: released reason=$reason")
                }
            }.onFailure {
                DesktopRuntimeLog.error("wakeLock: release threw reason=$reason", it)
            }
            return
        }
    }

    private interface Kernel32 : StdCallLibrary, Library {
        fun SetThreadExecutionState(esFlags: Int): Int
    }
}
