package com.nuvio.app.core.ui

import co.touchlab.kermit.Logger
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Structure
import java.awt.Robot
import java.awt.event.KeyEvent

/**
 * Gamepad navigation for desktop (PR #180 companion).
 *
 * Maps a connected gamepad (XInput, e.g. an Xbox controller) onto the same synthetic keyboard
 * events that [PlatformKeyboardNavigationHost]'s AWT dispatcher already consumes, so every
 * screen stays keyboard-reachable with no extra focus logic:
 *
 * - D-pad (or left stick) -> arrow keys: move focus between items.
 * - A (bottom face button) -> Enter: activate the focused item.
 * - B (right face button)  -> Escape: back navigation, including from the player.
 *
 * Windows only. On other desktop platforms, or when no XInput DLL is found, the poller
 * disables itself silently and the app keeps pure keyboard behaviour.
 */
internal object DesktopGamepadNavigation {
    private val gamepadLog = Logger.withTag("GamepadNav")
    private const val POLL_INTERVAL_MS = 8L
    private const val ERROR_DEVICE_NOT_CONNECTED = 1167
    private const val USER_INDEXES = 4

    // XINPUT_GAMEPAD button masks
    private const val DPAD_UP = 0x0001
    private const val DPAD_DOWN = 0x0002
    private const val DPAD_LEFT = 0x0004
    private const val DPAD_RIGHT = 0x0008
    private const val BUTTON_A = 0x1000
    private const val BUTTON_B = 0x2000

    // Holding an arrow maps to repeated key presses: e.g. 400 ms delay, then every 60 ms.
    private const val REPEAT_FIRST_DELAY_MS = 400L
    private const val REPEAT_INTERVAL_MS = 60L

    // Left-stick deadzone (0..1); below it the stick does not move focus.
    private const val STICK_DEADZONE = 0.55

    private val started = java.util.concurrent.atomic.AtomicBoolean(false)
    private var worker: Thread? = null

    @Volatile
    private var running = false

    fun start() {
        if (!started.compareAndSet(false, true)) return
        running = true
        worker = Thread({ runLoop() }, "NuvioGamepadNavigation").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
            setUncaughtExceptionHandler { _, throwable ->
                gamepadLog.e(throwable) { "gamepad poller crashed" }
            }
            start()
        }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
        started.set(false)
    }

    /** XInput GAMEPAD word layout, via JNA. Public so JNA reflection can instantiate it. */
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

    /** XINPUT_STATE layout (packet number + gamepad), via JNA. */
    @Structure.FieldOrder("dwPacketNumber", "gamepad")
    class XInputState : Structure() {
        @JvmField var dwPacketNumber: Int = 0
        @JvmField var gamepad: XInputGamepad = XInputGamepad()
    }

    interface XInputLibrary : Library {
        fun XInputGetState(dwUserIndex: Int, state: XInputState): Int
    }

    private fun loadLibrary(): XInputLibrary? = try {
        Native.load("xinput1_4", XInputLibrary::class.java).also {
            gamepadLog.i { "XInput library loaded (xinput1_4)" }
        }
    } catch (_: UnsatisfiedLinkError) {
        try {
            Native.load("xinput1_3", XInputLibrary::class.java).also {
                gamepadLog.i { "XInput library loaded (xinput1_3)" }
            }
        } catch (_: UnsatisfiedLinkError) {
            try {
                Native.load("xinput9_1_0", XInputLibrary::class.java).also {
                    gamepadLog.i { "XInput library loaded (xinput9_1_0)" }
                }
            } catch (_: UnsatisfiedLinkError) {
                gamepadLog.w { "no XInput DLL found - gamepad navigation disabled" }
                null
            }
        }
    }

    private fun runLoop() {
        val xinput = loadLibrary() ?: return
        val robot = try {
            Robot()
        } catch (_: Throwable) {
            return
        }

        // keyCode -> keep to repeat while held
        data class Held(val keyCode: Int, val sinceMs: Long, var lastRepeatMs: Long)

        val held = HashMap<Int, Held>()
        var aHeld = false
        var bHeld = false
        var loggedConnected = false
        val state = XInputState()
        var connectedIndex = -1
        var rescanAtMs = 0L

        fun press(keyCode: Int) {
            try {
                robot.keyPress(keyCode)
                robot.keyRelease(keyCode)
            } catch (_: Throwable) {
            }
        }

        fun sleepQuietly() {
            try {
                Thread.sleep(100L)
            } catch (_: InterruptedException) {
                // app is closing - stop polling
                running = false
                Thread.currentThread().interrupt()
            }
        }

        while (running && !Thread.currentThread().isInterrupted) {
            val now = System.currentTimeMillis()

            if (connectedIndex == -1) {
                if (now < rescanAtMs) {
                    sleepQuietly()
                    continue
                }
                rescanAtMs = now + 2_000L
                for (index in 0 until USER_INDEXES) {
                    if (xinput.XInputGetState(index, state) == 0) {
                        connectedIndex = index
                        gamepadLog.i { "gamepad connected (player $index)" }
                        loggedConnected = true
                        break
                    }
                }
                if (connectedIndex == -1) continue
            }

            val rc = try {
                xinput.XInputGetState(connectedIndex, state)
            } catch (_: Throwable) {
                connectedIndex = -1
                continue
            }
            if (rc != 0) {
                if (rc == ERROR_DEVICE_NOT_CONNECTED) {
                    if (loggedConnected) {
                        gamepadLog.i { "gamepad disconnected (player $connectedIndex)" }
                        loggedConnected = false
                    }
                    held.clear()
                    connectedIndex = -1
                }
                sleepQuietly()
                continue
            }

            val buttons = state.gamepad.wButtons.toInt() and 0xFFFF
            val thumbLx = state.gamepad.sThumbLX.toDouble() / 32768.0
            val thumbLy = state.gamepad.sThumbLY.toDouble() / 32768.0

            // D-pad is digital; the left stick adds analogue movement above the deadzone.
            val want = HashMap<Int, Boolean>()
            want[KeyEvent.VK_UP] = (buttons and DPAD_UP) != 0 || thumbLy >= STICK_DEADZONE
            want[KeyEvent.VK_DOWN] = (buttons and DPAD_DOWN) != 0 || thumbLy <= -STICK_DEADZONE
            want[KeyEvent.VK_LEFT] = (buttons and DPAD_LEFT) != 0 || thumbLx <= -STICK_DEADZONE
            want[KeyEvent.VK_RIGHT] = (buttons and DPAD_RIGHT) != 0 || thumbLx >= STICK_DEADZONE

            for ((keyCode, isHeld) in want) {
                val previous = held[keyCode]
                if (isHeld) {
                    if (previous == null) {
                        press(keyCode)
                        held[keyCode] = Held(keyCode, now, now)
                    } else {
                        val ageMs = now - previous.sinceMs
                        val delayMs =
                            if (ageMs < REPEAT_FIRST_DELAY_MS) {
                                REPEAT_FIRST_DELAY_MS - ageMs
                            } else if (now - previous.lastRepeatMs < REPEAT_INTERVAL_MS) {
                                REPEAT_INTERVAL_MS - (now - previous.lastRepeatMs)
                            } else {
                                0L
                            }
                        if (delayMs <= 0L) {
                            press(keyCode)
                            previous.lastRepeatMs = now
                        }
                    }
                } else {
                    held.remove(keyCode)
                }
            }

            if ((buttons and BUTTON_A) != 0 && !aHeld) {
                press(KeyEvent.VK_ENTER)
                aHeld = true
            } else if ((buttons and BUTTON_A) == 0) {
                aHeld = false
            }

            if ((buttons and BUTTON_B) != 0 && !bHeld) {
                press(KeyEvent.VK_ESCAPE)
                bHeld = true
            } else if ((buttons and BUTTON_B) == 0) {
                bHeld = false
            }

            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                // app is closing - exit quietly
                break
            }
        }
    }
}
