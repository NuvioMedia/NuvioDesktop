package com.nuvio.app.features.player

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import org.junit.Assume.assumeTrue
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises the JNA varargs ABI against real libdbus without requiring a session/socket. */
class LinuxScreenSaverDbusMarshallingTest {
    private lateinit var dbus: ScreenSaverDbus

    @Before
    fun loadLinuxDbus() {
        assumeTrue(System.getProperty("os.name").contains("linux", ignoreCase = true))
        dbus = Native.load("libdbus-1.so.3", ScreenSaverDbus::class.java)
        assertTrue(dbus.dbus_threads_init_default() != 0)
    }

    @Test
    fun unsignedCookiesSurviveJnaVarargsInBothDirections() {
        for (cookie in listOf(0, -1, Int.MAX_VALUE, Int.MIN_VALUE)) {
            val message = checkNotNull(dbus.dbus_message_new_method_call(
                "org.freedesktop.ScreenSaver", "/org/freedesktop/ScreenSaver",
                "org.freedesktop.ScreenSaver", "UnInhibit",
            ))
            try {
                assertTrue(dbus.dbus_message_append_args(message, 117, IntByReference(cookie), 0) != 0)
                val decoded = IntByReference()
                assertTrue(dbus.dbus_message_get_args(message, null, 117, decoded, 0) != 0)
                assertEquals(cookie, decoded.value)
            } finally {
                dbus.dbus_message_unref(message)
            }
        }
    }

    @Test
    fun stringArgumentsArePassedAsPointersToPointersAndCopiedIntoTheMessage() {
        val message = checkNotNull(dbus.dbus_message_new_method_call(
            "org.freedesktop.ScreenSaver", "/org/freedesktop/ScreenSaver",
            "org.freedesktop.ScreenSaver", "Inhibit",
        ))
        try {
            Memory(6).use { application ->
                Memory(15).use { reason ->
                    application.setString(0, "Nuvio", "UTF-8")
                    reason.setString(0, "Media playback", "UTF-8")
                    assertTrue(dbus.dbus_message_append_args(
                        message, 115, PointerByReference(application), 115, PointerByReference(reason), 0,
                    ) != 0)
                }
            }
            val application = PointerByReference()
            val reason = PointerByReference()
            assertTrue(dbus.dbus_message_get_args(message, null, 115, application, 115, reason, 0) != 0)
            assertEquals("Nuvio", application.value.getString(0, "UTF-8"))
            assertEquals("Media playback", reason.value.getString(0, "UTF-8"))
            val wrongType = IntByReference()
            assertEquals(0, dbus.dbus_message_get_args(message, null, 117, wrongType, 0))
        } finally {
            dbus.dbus_message_unref(message)
        }
    }
}
