package com.nuvio.app.features.player

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

/** The cookie belongs to this connection; closing a one-shot dbus-send releases it immediately. */
internal class LinuxScreenSaverInhibitor private constructor(
    private val dbus: ScreenSaverDbus,
    private val connection: Pointer,
    private val cookie: Int,
) : AutoCloseable {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        try {
            call(dbus, connection, "UnInhibit") { message ->
                // Preserve all 32 bits of the unsigned D-Bus cookie, including zero.
                check(dbus.dbus_message_append_args(message, TYPE_UINT32, IntByReference(cookie), TYPE_INVALID) != 0)
            }.also(dbus::dbus_message_unref)
        } finally {
            // Disconnect also releases the cookie if UnInhibit fails or times out.
            dbus.dbus_connection_close(connection)
            dbus.dbus_connection_unref(connection)
        }
    }

    companion object {
        private const val SERVICE = "org.freedesktop.ScreenSaver"
        private const val PATH = "/org/freedesktop/ScreenSaver"
        private const val TYPE_STRING = 115 // 's'
        private const val TYPE_UINT32 = 117 // 'u'
        private const val TYPE_INVALID = 0
        private const val CALL_TIMEOUT_MS = 3000

        // SONAME works on installed runtimes without the development-package symlink.
        private val dbus by lazy {
            Native.load("libdbus-1.so.3", ScreenSaverDbus::class.java).also {
                check(it.dbus_threads_init_default() != 0)
            }
        }

        fun acquire(): LinuxScreenSaverInhibitor {
            val api = dbus
            val connection = checkNotNull(api.dbus_bus_get_private(0, null)) { "Cannot connect to session D-Bus" }
            api.dbus_connection_set_exit_on_disconnect(connection, 0)
            try {
                val reply = call(api, connection, "Inhibit") { message ->
                    Memory(6).use { application ->
                        Memory(15).use { reason ->
                            application.setString(0, "Nuvio", "UTF-8")
                            reason.setString(0, "Media playback", "UTF-8")
                            check(api.dbus_message_append_args(
                                message, TYPE_STRING, PointerByReference(application),
                                TYPE_STRING, PointerByReference(reason), TYPE_INVALID,
                            ) != 0)
                        }
                    }
                }
                val cookie = IntByReference()
                try {
                    check(api.dbus_message_get_args(reply, null, TYPE_UINT32, cookie, TYPE_INVALID) != 0) {
                        "ScreenSaver.Inhibit did not return a uint32 cookie"
                    }
                } finally {
                    api.dbus_message_unref(reply)
                }
                return LinuxScreenSaverInhibitor(api, connection, cookie.value)
            } catch (error: Throwable) {
                api.dbus_connection_close(connection)
                api.dbus_connection_unref(connection)
                throw error
            }
        }

        private fun call(
            api: ScreenSaverDbus,
            connection: Pointer,
            method: String,
            append: (Pointer) -> Unit,
        ): Pointer {
            val message = checkNotNull(api.dbus_message_new_method_call(SERVICE, PATH, SERVICE, method))
            try {
                append(message)
                return checkNotNull(api.dbus_connection_send_with_reply_and_block(connection, message, CALL_TIMEOUT_MS, null)) {
                    "ScreenSaver.$method failed or timed out"
                }
            } finally {
                api.dbus_message_unref(message)
            }
        }
    }
}

/** Minimal libdbus mapping using the project's existing JNA dependency. */
internal interface ScreenSaverDbus : Library {
    fun dbus_threads_init_default(): Int
    fun dbus_bus_get_private(type: Int, error: Pointer?): Pointer?
    fun dbus_connection_set_exit_on_disconnect(connection: Pointer, enabled: Int)
    fun dbus_connection_close(connection: Pointer)
    fun dbus_connection_unref(connection: Pointer)
    fun dbus_message_new_method_call(destination: String, path: String, iface: String, method: String): Pointer?
    fun dbus_message_append_args(message: Pointer, firstType: Int, vararg args: Any): Int
    fun dbus_message_get_args(message: Pointer, error: Pointer?, firstType: Int, vararg args: Any): Int
    fun dbus_connection_send_with_reply_and_block(connection: Pointer, message: Pointer, timeout: Int, error: Pointer?): Pointer?
    fun dbus_message_unref(message: Pointer)
}
