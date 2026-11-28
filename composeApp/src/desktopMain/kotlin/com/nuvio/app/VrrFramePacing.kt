package com.nuvio.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.awt.ComposeWindow
import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.desktop.AwtNativeViewResolver
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.settings.DesktopRendererSettings
import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.IntByReference
import java.awt.Frame
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private const val VrrProbeIntervalMillis = 2_000L

/**
 * Keeps the window presenting a frame every vsync while it is focused on a display that is running
 * NVIDIA G-SYNC, so the panel's refresh rate stays at its maximum.
 *
 * Skiko only presents when Compose has something new to draw, and with G-SYNC the panel refreshes
 * whenever a frame arrives. An idle screen therefore drops toward the bottom of the VRR range and
 * jumps back to the maximum for every hover, scroll or animation. OLED panels change gamma with the
 * refresh rate, so each of those swings is a visible brightness flicker; LCDs mostly hide it. A
 * steady stream of vsync-paced frames holds the refresh rate constant, the same as a game running
 * at its frame cap.
 *
 * NVIDIA windowed G-SYNC only follows the foreground window, so the pacer stops when the window is
 * inactive or minimised, and it never runs on displays where the driver reports VRR as off.
 */
@Composable
internal fun VrrSteadyFramePacing(window: ComposeWindow) {
    if (DesktopHostOs.current != DesktopHostOs.WINDOWS) return
    val enabled by DesktopRendererSettings.steadyFramePacing.collectAsState()
    if (!enabled) return

    var isWindowActive by remember(window) { mutableStateOf(window.isActive) }
    var isMinimized by remember(window) {
        mutableStateOf(window.extendedState and Frame.ICONIFIED != 0)
    }
    DisposableEffect(window) {
        val listener = object : WindowAdapter() {
            override fun windowActivated(e: WindowEvent) { isWindowActive = true }
            override fun windowDeactivated(e: WindowEvent) { isWindowActive = false }
            override fun windowIconified(e: WindowEvent) { isMinimized = true }
            override fun windowDeiconified(e: WindowEvent) { isMinimized = false }
        }
        window.addWindowListener(listener)
        isWindowActive = window.isActive
        isMinimized = window.extendedState and Frame.ICONIFIED != 0
        onDispose { window.removeWindowListener(listener) }
    }

    val isForeground = isWindowActive && !isMinimized
    var isVrrDisplay by remember(window) { mutableStateOf(false) }
    LaunchedEffect(window, isForeground) {
        if (!isForeground) {
            isVrrDisplay = false
            return@LaunchedEffect
        }
        // Polled so moving the window to another monitor, or toggling G-SYNC in the NVIDIA
        // control panel, is picked up without a restart. Each probe is a few NVAPI calls.
        while (isActive) {
            isVrrDisplay = withContext(Dispatchers.IO) { NvidiaVrrDisplayProbe.isVrrActive(window) }
            delay(VrrProbeIntervalMillis)
        }
    }

    if (shouldPaceFrames(enabled = enabled, isForeground = isForeground, isVrrDisplay = isVrrDisplay)) {
        LaunchedEffect(window) {
            // Each awaited frame makes Compose render and Skiko present with vsync, which paces this
            // loop to the display's refresh rate.
            while (isActive) {
                withFrameNanos { }
            }
        }
    }
}

internal fun shouldPaceFrames(enabled: Boolean, isForeground: Boolean, isVrrDisplay: Boolean): Boolean =
    enabled && isForeground && isVrrDisplay

/** The flags NVAPI's NV_GET_VRR_INFO_V1 packs into its second 32-bit word. */
internal data class NvidiaVrrInfo(
    val isEnabled: Boolean,
    val isPossible: Boolean,
    val isDisplayInVrrMode: Boolean,
) {
    /** True when G-SYNC is switched on for the display, which is what makes the refresh rate follow us. */
    val isOn: Boolean
        get() = isPossible && (isEnabled || isDisplayInVrrMode)

    companion object {
        fun fromBits(bits: Int): NvidiaVrrInfo = NvidiaVrrInfo(
            isEnabled = bits and 0x1 != 0,
            isPossible = bits and 0x2 != 0,
            isDisplayInVrrMode = bits and 0x10 != 0,
        )
    }
}

/**
 * Asks the NVIDIA driver whether the monitor under a window is running VRR. Returns false on
 * non-NVIDIA systems, or when anything about the lookup fails.
 */
internal object NvidiaVrrDisplayProbe {
    private val log = Logger.withTag("VrrFramePacing")

    // Interface ids from NVIDIA's public nvapi_interface.h.
    private const val NvApiInitializeId = 0x0150E828
    private const val NvApiGetDisplayIdByDisplayNameId = 0xAE457190.toInt()
    private const val NvApiDispGetVrrInfoId = 0xDF8FDA57.toInt()
    private const val NvApiOk = 0
    private const val VrrInfoSize = 24
    private const val VrrInfoVersion1 = VrrInfoSize or (1 shl 16)
    private const val MonitorDefaultToNearest = 2

    private class NvApi(
        val getDisplayIdByDisplayName: Function,
        val getVrrInfo: Function,
    )

    private val nvApi: NvApi? by lazy {
        runCatching {
            val library = NativeLibrary.getInstance("nvapi64")
            val queryInterface = library.getFunction("nvapi_QueryInterface")
            fun resolve(id: Int): Function {
                val pointer = queryInterface.invoke(Pointer::class.java, arrayOf<Any>(id)) as Pointer?
                    ?: error("NVAPI interface 0x${id.toUInt().toString(16)} is unavailable")
                return Function.getFunction(pointer, Function.C_CONVENTION)
            }
            val status = resolve(NvApiInitializeId).invokeInt(emptyArray())
            check(status == NvApiOk) { "NvAPI_Initialize returned $status" }
            NvApi(
                getDisplayIdByDisplayName = resolve(NvApiGetDisplayIdByDisplayNameId),
                getVrrInfo = resolve(NvApiDispGetVrrInfoId),
            )
        }.onFailure {
            log.i { "NVAPI unavailable, steady frame pacing stays off: ${it.message}" }
        }.getOrNull()
    }

    @Suppress("FunctionName")
    private interface User32 : Library {
        fun MonitorFromWindow(hwnd: Pointer, flags: Int): Pointer?
        fun GetMonitorInfoW(monitor: Pointer, info: MonitorInfoEx): Boolean
    }

    @Structure.FieldOrder("cbSize", "rcMonitor", "rcWork", "dwFlags", "szDevice")
    class MonitorInfoEx : Structure() {
        @JvmField var cbSize: Int = 0
        @JvmField var rcMonitor: IntArray = IntArray(4)
        @JvmField var rcWork: IntArray = IntArray(4)
        @JvmField var dwFlags: Int = 0
        @JvmField var szDevice: CharArray = CharArray(32)

        init {
            cbSize = size()
        }
    }

    private val user32: User32? by lazy {
        runCatching { Native.load("user32", User32::class.java) }.getOrNull()
    }

    fun isVrrActive(window: ComposeWindow): Boolean = runCatching {
        val api = nvApi ?: return false
        val displayName = monitorDeviceName(window) ?: return false
        val displayId = IntByReference()
        if (api.getDisplayIdByDisplayName.invokeInt(arrayOf(displayName, displayId)) != NvApiOk) {
            // Not an NVIDIA-driven display (for example an iGPU output).
            return false
        }
        val info = Memory(VrrInfoSize.toLong()).apply {
            clear()
            setInt(0, VrrInfoVersion1)
        }
        if (api.getVrrInfo.invokeInt(arrayOf(displayId.value, info)) != NvApiOk) return false
        NvidiaVrrInfo.fromBits(info.getInt(4)).isOn
    }.getOrDefault(false)

    /** The GDI device name, such as `\\.\DISPLAY1`, of the monitor most of the window is on. */
    private fun monitorDeviceName(window: ComposeWindow): String? {
        val library = user32 ?: return null
        val hwnd = Pointer(AwtNativeViewResolver.resolveNativeViewPointer(window))
        val monitor = library.MonitorFromWindow(hwnd, MonitorDefaultToNearest) ?: return null
        val info = MonitorInfoEx()
        if (!library.GetMonitorInfoW(monitor, info)) return null
        return Native.toString(info.szDevice).takeIf { it.isNotEmpty() }
    }
}
