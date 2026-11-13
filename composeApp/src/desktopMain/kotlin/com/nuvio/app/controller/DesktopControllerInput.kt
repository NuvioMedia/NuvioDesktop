/*
 * Controller input for desktop.
 *
 * The canonical event model (XboxButton / ControllerDirection / ControllerEvent) comes from
 * NuvioDeck (https://github.com/kllakm/NuvioDeck, GPLv3); the polling backends are adapted to run
 * on this app's supported desktop platforms:
 *
 * - Windows: XInput 1.4 / 1.3 / 9.1.0 (Xbox and XInput-compatible pads).
 * - Linux / macOS: SDL2 GameController API through JNA (Xbox, DualSense, DualShock, Switch Pro).
 *
 * Both backends normalise their input into the same events and feed [ControllerManager], so the
 * spatial focus engine is platform independent.
 */
package com.nuvio.app.controller

import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

internal object DesktopControllerInput {
    private val log = Logger.withTag("ControllerInput")

    private const val POLL_INTERVAL_MS = 8L
    private const val RESCAN_INTERVAL_MS = 1_000L
    private const val REPEAT_FIRST_DELAY_MS = 320L
    private const val REPEAT_INTERVAL_MS = 105L

    /**
     * Left stick -> focus direction. Two thresholds give the stick hysteresis: a direction starts
     * once the axis crosses [STICK_ENTER_DEADZONE] and only stops below [STICK_EXIT_DEADZONE], so a
     * stick resting near the threshold cannot chatter between two items.
     */
    private const val STICK_ENTER_DEADZONE = 0.50f
    private const val STICK_EXIT_DEADZONE = 0.32f
    private const val ANALOG_SCROLL_DEADZONE = 0.28f

    private val started = AtomicBoolean(false)

    @Volatile
    private var running = false

    private var worker: Thread? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        running = true
        worker = Thread(
            {
                runCatching {
                    when (DesktopHostOs.current) {
                        DesktopHostOs.WINDOWS -> runXInputLoop()
                        DesktopHostOs.LINUX, DesktopHostOs.MACOS -> runSdlLoop()
                        else -> Unit
                    }
                }.onFailure { log.e(it) { "controller poller failed" } }
            },
            "NuvioControllerInput",
        ).apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
        started.set(false)
    }

    // ------------------------------------------------------------------ shared state machine

    /**
     * Edge detection + auto-repeat for a single pad.
     *
     * Each connected pad keeps its own state: merging raw button/direction sets before edge
     * detection would let a held input on one pad mask the same input on another, which made a
     * second controller look dead until the first one was touched.
     */
    private class PadState {
        private val heldDirections = HashMap<ControllerDirection, Long>()
        private val repeatAt = HashMap<ControllerDirection, Long>()
        private val heldButtons = HashSet<XboxButton>()

        fun update(
            directions: Set<ControllerDirection>,
            buttons: Set<XboxButton>,
            now: Long,
        ) {
            for (direction in ControllerDirection.entries) {
                val pressed = direction in directions
                val previous = heldDirections[direction]
                if (pressed) {
                    if (previous == null) {
                        heldDirections[direction] = now
                        repeatAt[direction] = now + REPEAT_FIRST_DELAY_MS
                        ControllerManager.dispatchEvent(
                            ControllerEvent.Navigate(direction, isRepeat = false),
                        )
                    } else if (now >= (repeatAt[direction] ?: Long.MAX_VALUE)) {
                        repeatAt[direction] = now + REPEAT_INTERVAL_MS
                        ControllerManager.dispatchEvent(
                            ControllerEvent.Navigate(direction, isRepeat = true),
                        )
                    }
                } else {
                    heldDirections.remove(direction)
                    repeatAt.remove(direction)
                }
            }

            for (button in XboxButton.entries) {
                val pressed = button in buttons
                if (pressed && heldButtons.add(button)) {
                    ControllerManager.dispatchEvent(ControllerEvent.ButtonPressed(button))
                } else if (!pressed && heldButtons.remove(button)) {
                    ControllerManager.dispatchEvent(ControllerEvent.ButtonReleased(button))
                }
            }
        }

        fun reset() {
            heldDirections.clear()
            repeatAt.clear()
            heldButtons.clear()
        }

        /** Directions currently held from the previous poll, used for stick hysteresis. */
        fun activeDirections(): Set<ControllerDirection> = heldDirections.keys
    }

    /** Right-stick scrolling, shared by all pads (the strongest deflection wins). */
    private class AnalogScrollState {
        private var lastSentAt = 0L

        fun update(analogDx: Float, analogDy: Float, now: Long) {
            if (abs(analogDx) > 0.01f || abs(analogDy) > 0.01f) {
                ControllerManager.dispatchEvent(ControllerEvent.AnalogScroll(analogDx, analogDy))
                lastSentAt = now
            } else if (now - lastSentAt < 120L) {
                ControllerManager.dispatchEvent(ControllerEvent.AnalogScroll(0f, 0f))
            }
        }
    }

    private fun directionsFromAxes(
        x: Float,
        y: Float,
        active: Set<ControllerDirection> = emptySet(),
    ): Set<ControllerDirection> {
        val result = HashSet<ControllerDirection>(2)
        // Held axes get the lower release threshold so the direction does not flicker at the edge.
        val thresholdX = if (ControllerDirection.Left in active || ControllerDirection.Right in active) {
            STICK_EXIT_DEADZONE
        } else {
            STICK_ENTER_DEADZONE
        }
        val thresholdY = if (ControllerDirection.Up in active || ControllerDirection.Down in active) {
            STICK_EXIT_DEADZONE
        } else {
            STICK_ENTER_DEADZONE
        }
        if (y <= -thresholdY) result += ControllerDirection.Up
        if (y >= thresholdY) result += ControllerDirection.Down
        if (x <= -thresholdX) result += ControllerDirection.Left
        if (x >= thresholdX) result += ControllerDirection.Right
        return result
    }

    private fun sleepQuietly() {
        try {
            Thread.sleep(POLL_INTERVAL_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // ------------------------------------------------------------------ Windows: XInput

    @Structure.FieldOrder(
        "wButtons",
        "bLeftTrigger",
        "bRightTrigger",
        "sThumbLX",
        "sThumbLY",
        "sThumbRX",
        "sThumbRY",
    )
    class XInputGamepad : Structure() {
        @JvmField var wButtons: Short = 0
        @JvmField var bLeftTrigger: Byte = 0
        @JvmField var bRightTrigger: Byte = 0
        @JvmField var sThumbLX: Short = 0
        @JvmField var sThumbLY: Short = 0
        @JvmField var sThumbRX: Short = 0
        @JvmField var sThumbRY: Short = 0
    }

    @Structure.FieldOrder("dwPacketNumber", "gamepad")
    class XInputState : Structure() {
        @JvmField var dwPacketNumber: Int = 0
        @JvmField var gamepad: XInputGamepad = XInputGamepad()
    }

    interface XInputLibrary : Library {
        fun XInputGetState(dwUserIndex: Int, state: XInputState): Int
    }

    private const val ERROR_DEVICE_NOT_CONNECTED = 1167
    private const val USER_INDEXES = 4

    private const val DPAD_UP = 0x0001
    private const val DPAD_DOWN = 0x0002
    private const val DPAD_LEFT = 0x0004
    private const val DPAD_RIGHT = 0x0008
    private const val BUTTON_START = 0x0010
    private const val BUTTON_BACK = 0x0020
    private const val BUTTON_LEFT_THUMB = 0x0040
    private const val BUTTON_RIGHT_THUMB = 0x0080
    private const val BUTTON_LB = 0x0100
    private const val BUTTON_RB = 0x0200
    private const val BUTTON_A = 0x1000
    private const val BUTTON_B = 0x2000
    private const val BUTTON_X = 0x4000
    private const val BUTTON_Y = 0x8000

    private fun loadXInput(): XInputLibrary? {
        for (name in listOf("xinput1_4", "xinput1_3", "xinput9_1_0")) {
            runCatching { Native.load(name, XInputLibrary::class.java) }
                .onSuccess {
                    log.i { "XInput loaded ($name)" }
                    return it
                }
        }
        log.w { "no XInput DLL found - controller disabled" }
        return null
    }

    private fun runXInputLoop() {
        val xinput = loadXInput() ?: return
        val state = XInputState()
        val input = PadState()
        val analog = AnalogScrollState()
        var connectedIndex = -1
        var rescanAt = 0L

        while (running && !Thread.currentThread().isInterrupted) {
            val now = System.currentTimeMillis()

            if (connectedIndex == -1) {
                if (now < rescanAt) {
                    sleepQuietly()
                    continue
                }
                rescanAt = now + RESCAN_INTERVAL_MS
                connectedIndex = (0 until USER_INDEXES).firstOrNull {
                    runCatching { xinput.XInputGetState(it, state) }.getOrDefault(1) == 0
                } ?: -1
                if (connectedIndex == -1) continue
                input.reset()
                log.i { "controller connected (xinput $connectedIndex)" }
            }

            val rc = runCatching { xinput.XInputGetState(connectedIndex, state) }
                .getOrDefault(ERROR_DEVICE_NOT_CONNECTED)
            if (rc != 0) {
                if (rc == ERROR_DEVICE_NOT_CONNECTED) {
                    log.i { "controller disconnected" }
                    input.reset()
                    connectedIndex = -1
                }
                sleepQuietly()
                continue
            }

            val pad = state.gamepad
            val buttonsMask = pad.wButtons.toInt() and 0xFFFF
            val thumbLx = pad.sThumbLX / 32768f
            val thumbLy = pad.sThumbLY / 32768f
            val thumbRx = pad.sThumbRX / 32768f
            val thumbRy = pad.sThumbRY / 32768f
            val lt = (pad.bLeftTrigger.toInt() and 0xFF) / 255f
            val rt = (pad.bRightTrigger.toInt() and 0xFF) / 255f

            val directions = HashSet(directionsFromAxes(thumbLx, -thumbLy, input.activeDirections()))
            if (buttonsMask and DPAD_UP != 0) directions += ControllerDirection.Up
            if (buttonsMask and DPAD_DOWN != 0) directions += ControllerDirection.Down
            if (buttonsMask and DPAD_LEFT != 0) directions += ControllerDirection.Left
            if (buttonsMask and DPAD_RIGHT != 0) directions += ControllerDirection.Right

            val buttons = HashSet<XboxButton>()
            if (buttonsMask and BUTTON_A != 0) buttons += XboxButton.A
            if (buttonsMask and BUTTON_B != 0) buttons += XboxButton.B
            if (buttonsMask and BUTTON_X != 0) buttons += XboxButton.X
            if (buttonsMask and BUTTON_Y != 0) buttons += XboxButton.Y
            if (buttonsMask and BUTTON_LB != 0) buttons += XboxButton.LB
            if (buttonsMask and BUTTON_RB != 0) buttons += XboxButton.RB
            if (buttonsMask and BUTTON_START != 0) buttons += XboxButton.Menu
            if (buttonsMask and BUTTON_BACK != 0) buttons += XboxButton.View
            if (buttonsMask and BUTTON_LEFT_THUMB != 0) buttons += XboxButton.LS
            if (buttonsMask and BUTTON_RIGHT_THUMB != 0) buttons += XboxButton.RS
            if (lt >= 0.55f) buttons += XboxButton.LT
            if (rt >= 0.55f) buttons += XboxButton.RT

            // Right stick drives smooth scrolling.
            val ax = thumbRx.takeIf { abs(it) >= ANALOG_SCROLL_DEADZONE } ?: 0f
            val ay = thumbRy.takeIf { abs(it) >= ANALOG_SCROLL_DEADZONE } ?: 0f

            input.update(directions, buttons, now)
            analog.update(ax, -ay, now)
            Thread.sleep(POLL_INTERVAL_MS.coerceAtLeast(1L))
        }
    }

    // ------------------------------------------------------------------ Linux/macOS: SDL2

    interface Sdl2Library : Library {
        fun SDL_Init(flags: Int): Int
        fun SDL_QuitSubSystem(flags: Int)
        fun SDL_PumpEvents()
        fun SDL_NumJoysticks(): Int
        fun SDL_IsGameController(index: Int): Int
        fun SDL_JoystickGetDeviceInstanceID(index: Int): Int
        fun SDL_GameControllerOpen(index: Int): Pointer?
        fun SDL_GameControllerClose(controller: Pointer)
        fun SDL_GameControllerGetAttached(controller: Pointer): Int
        fun SDL_GameControllerUpdate()
        fun SDL_GameControllerGetButton(controller: Pointer, button: Int): Int
        fun SDL_GameControllerGetAxis(controller: Pointer, axis: Int): Short
        fun SDL_GameControllerName(controller: Pointer): String?
    }

    private const val SDL_INIT_JOYSTICK = 0x00000200
    private const val SDL_INIT_GAMECONTROLLER = 0x00002000

    private const val SDL_CONTROLLER_BUTTON_A = 0
    private const val SDL_CONTROLLER_BUTTON_B = 1
    private const val SDL_CONTROLLER_BUTTON_X = 2
    private const val SDL_CONTROLLER_BUTTON_Y = 3
    private const val SDL_CONTROLLER_BUTTON_BACK = 4
    private const val SDL_CONTROLLER_BUTTON_START = 6
    private const val SDL_CONTROLLER_BUTTON_LEFTSTICK = 7
    private const val SDL_CONTROLLER_BUTTON_RIGHTSTICK = 8
    private const val SDL_CONTROLLER_BUTTON_LEFTSHOULDER = 9
    private const val SDL_CONTROLLER_BUTTON_RIGHTSHOULDER = 10
    private const val SDL_CONTROLLER_BUTTON_DPAD_UP = 11
    private const val SDL_CONTROLLER_BUTTON_DPAD_DOWN = 12
    private const val SDL_CONTROLLER_BUTTON_DPAD_LEFT = 13
    private const val SDL_CONTROLLER_BUTTON_DPAD_RIGHT = 14

    private const val SDL_CONTROLLER_AXIS_LEFTX = 0
    private const val SDL_CONTROLLER_AXIS_LEFTY = 1
    private const val SDL_CONTROLLER_AXIS_RIGHTX = 2
    private const val SDL_CONTROLLER_AXIS_RIGHTY = 3
    private const val SDL_CONTROLLER_AXIS_TRIGGERLEFT = 4
    private const val SDL_CONTROLLER_AXIS_TRIGGERRIGHT = 5

    private fun loadSdl2(): Sdl2Library? {
        val candidates = listOf(
            "SDL2",
            "SDL2-2.0.so.0",
            "libSDL2-2.0.so.0",
            "libSDL2.so",
            "SDL2.dll",
            "libSDL2.dylib",
        )
        for (name in candidates) {
            runCatching { Native.load(name, Sdl2Library::class.java) }
                .onSuccess {
                    log.i { "SDL2 loaded ($name)" }
                    return it
                }
        }
        log.w { "SDL2 not found - controller disabled" }
        return null
    }

    private fun runSdlLoop() {
        val sdl = loadSdl2() ?: return
        if (sdl.SDL_Init(SDL_INIT_JOYSTICK or SDL_INIT_GAMECONTROLLER) != 0) {
            log.w { "SDL_Init failed - controller disabled" }
            return
        }

        // Every attached pad is polled with its own edge-detection state, so an Xbox pad and a
        // DualSense can be connected at the same time without one masking the other's inputs.
        val controllers = LinkedHashMap<Int, Pointer>()
        val padStates = HashMap<Int, PadState>()
        val analog = AnalogScrollState()
        var lastScan = 0L

        try {
            while (running && !Thread.currentThread().isInterrupted) {
                val now = System.currentTimeMillis()

                // Pump the SDL queue so hotplug (a second pad connecting after launch) is detected
                // without waiting for input on an already-open controller.
                runCatching { sdl.SDL_PumpEvents() }

                val detached = controllers.entries.filter { (_, pad) ->
                    runCatching { sdl.SDL_GameControllerGetAttached(pad) }.getOrDefault(0) == 0
                }
                if (detached.isNotEmpty()) {
                    detached.forEach { (instanceId, pad) ->
                        runCatching { sdl.SDL_GameControllerClose(pad) }
                        controllers.remove(instanceId)
                        padStates.remove(instanceId)
                        log.i { "controller disconnected (instance $instanceId)" }
                    }
                }

                if (now - lastScan >= RESCAN_INTERVAL_MS) {
                    lastScan = now
                    sdl.SDL_GameControllerUpdate()
                    val count = sdl.SDL_NumJoysticks()
                    for (index in 0 until max(0, count)) {
                        if (sdl.SDL_IsGameController(index) == 0) continue
                        // Key pads by their stable instance id so re-indexing (a pad disconnecting
                        // and the list shifting) never opens a duplicate handle for the same pad.
                        val instanceId = runCatching { sdl.SDL_JoystickGetDeviceInstanceID(index) }
                            .getOrDefault(-1)
                        val key = if (instanceId >= 0) instanceId else index
                        if (controllers.containsKey(key)) continue
                        val opened = runCatching { sdl.SDL_GameControllerOpen(index) }.getOrNull() ?: continue
                        controllers[key] = opened
                        padStates[key] = PadState()
                        val name = runCatching { sdl.SDL_GameControllerName(opened) }.getOrNull()
                        log.i { "controller connected (instance $key): ${name ?: "unknown"}" }
                    }
                }

                if (controllers.isNotEmpty()) {
                    sdl.SDL_GameControllerUpdate()

                    var ax = 0f
                    var ay = 0f

                    controllers.forEach { (instanceId, pad) ->
                        val padState = padStates.getOrPut(instanceId) { PadState() }
                        val directions = HashSet<ControllerDirection>()
                        val buttons = HashSet<XboxButton>()

                        fun pressed(button: Int) = sdl.SDL_GameControllerGetButton(pad, button) != 0
                        fun axis(value: Int) = sdl.SDL_GameControllerGetAxis(pad, value) / 32768f
                        fun trigger(value: Int) =
                            (sdl.SDL_GameControllerGetAxis(pad, value).toInt() + 32768) / 65535f

                        directions += directionsFromAxes(
                            axis(SDL_CONTROLLER_AXIS_LEFTX),
                            axis(SDL_CONTROLLER_AXIS_LEFTY),
                            padState.activeDirections(),
                        )
                        if (pressed(SDL_CONTROLLER_BUTTON_DPAD_UP)) directions += ControllerDirection.Up
                        if (pressed(SDL_CONTROLLER_BUTTON_DPAD_DOWN)) directions += ControllerDirection.Down
                        if (pressed(SDL_CONTROLLER_BUTTON_DPAD_LEFT)) directions += ControllerDirection.Left
                        if (pressed(SDL_CONTROLLER_BUTTON_DPAD_RIGHT)) directions += ControllerDirection.Right

                        if (pressed(SDL_CONTROLLER_BUTTON_A)) buttons += XboxButton.A
                        if (pressed(SDL_CONTROLLER_BUTTON_B)) buttons += XboxButton.B
                        if (pressed(SDL_CONTROLLER_BUTTON_X)) buttons += XboxButton.X
                        if (pressed(SDL_CONTROLLER_BUTTON_Y)) buttons += XboxButton.Y
                        if (pressed(SDL_CONTROLLER_BUTTON_LEFTSHOULDER)) buttons += XboxButton.LB
                        if (pressed(SDL_CONTROLLER_BUTTON_RIGHTSHOULDER)) buttons += XboxButton.RB
                        if (pressed(SDL_CONTROLLER_BUTTON_START)) buttons += XboxButton.Menu
                        if (pressed(SDL_CONTROLLER_BUTTON_BACK)) buttons += XboxButton.View
                        if (pressed(SDL_CONTROLLER_BUTTON_LEFTSTICK)) buttons += XboxButton.LS
                        if (pressed(SDL_CONTROLLER_BUTTON_RIGHTSTICK)) buttons += XboxButton.RS
                        if (trigger(SDL_CONTROLLER_AXIS_TRIGGERLEFT) >= 0.55f) buttons += XboxButton.LT
                        if (trigger(SDL_CONTROLLER_AXIS_TRIGGERRIGHT) >= 0.55f) buttons += XboxButton.RT

                        padState.update(directions, buttons, now)

                        val padAx = axis(SDL_CONTROLLER_AXIS_RIGHTX)
                        val padAy = axis(SDL_CONTROLLER_AXIS_RIGHTY)
                        if (abs(padAx) > abs(ax)) ax = padAx
                        if (abs(padAy) > abs(ay)) ay = padAy
                    }

                    analog.update(
                        ax.takeIf { abs(it) >= ANALOG_SCROLL_DEADZONE } ?: 0f,
                        ay.takeIf { abs(it) >= ANALOG_SCROLL_DEADZONE } ?: 0f,
                        now,
                    )
                }

                Thread.sleep(POLL_INTERVAL_MS.coerceAtLeast(1L))
            }
        } finally {
            controllers.values.forEach { runCatching { sdl.SDL_GameControllerClose(it) } }
            runCatching { sdl.SDL_QuitSubSystem(SDL_INIT_JOYSTICK or SDL_INIT_GAMECONTROLLER) }
        }
    }
}
