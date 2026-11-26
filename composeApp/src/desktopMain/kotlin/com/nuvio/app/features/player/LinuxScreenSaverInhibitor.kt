package com.nuvio.app.features.player

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.util.concurrent.TimeUnit

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
        private const val BUS_SERVICE = "org.freedesktop.DBus"
        private const val BUS_PATH = "/org/freedesktop/DBus"
        private const val TYPE_STRING = 115 // 's'
        private const val TYPE_UINT32 = 117 // 'u'
        private const val TYPE_INVALID = 0
        private const val CALL_TIMEOUT_MS = 3000
        private const val IO_POLL_MS = 50
        private const val METHOD_RETURN = 2

        // SONAME works on installed runtimes without the development-package symlink.
        private val dbus by lazy {
            Native.load("libdbus-1.so.3", ScreenSaverDbus::class.java).also {
                check(it.dbus_threads_init_default() != 0)
            }
        }

        fun acquire(address: String = sessionBusAddress()): LinuxScreenSaverInhibitor {
            val api = dbus
            // Opening does not authenticate/register. dbus_bus_get_private() blocks during
            // authentication before its reply timeout starts, so drive Hello ourselves.
            val connection = checkNotNull(api.dbus_connection_open_private(address, null)) { "Cannot connect to session D-Bus" }
            api.dbus_connection_set_exit_on_disconnect(connection, 0)
            try {
                val hello = call(api, connection, "Hello", BUS_SERVICE, BUS_PATH)
                try {
                    val uniqueName = PointerByReference()
                    check(api.dbus_message_get_args(hello, null, TYPE_STRING, uniqueName, TYPE_INVALID) != 0)
                    check(api.dbus_bus_set_unique_name(connection, uniqueName.value.getString(0, "UTF-8")) != 0)
                } finally {
                    api.dbus_message_unref(hello)
                }
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

        private fun sessionBusAddress(): String {
            System.getenv("DBUS_SESSION_BUS_ADDRESS")?.takeIf { it.isNotBlank() }?.let { return it }
            // Standard user-bus fallback without launching another bus via dbus-launch.
            val runtimeDir = checkNotNull(System.getenv("XDG_RUNTIME_DIR")) { "Cannot locate session D-Bus" }
            val escaped = checkNotNull(dbus.dbus_address_escape_value("$runtimeDir/bus"))
            return try {
                "unix:path=${escaped.getString(0, "UTF-8")}"
            } finally {
                dbus.dbus_free(escaped)
            }
        }

        private fun call(
            api: ScreenSaverDbus,
            connection: Pointer,
            method: String,
            service: String = SERVICE,
            path: String = PATH,
            append: (Pointer) -> Unit = {},
        ): Pointer {
            val message = checkNotNull(api.dbus_message_new_method_call(service, path, service, method))
            try {
                append(message)
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CALL_TIMEOUT_MS.toLong())
                val pendingReference = PointerByReference()
                check(api.dbus_connection_send_with_reply(connection, message, pendingReference, CALL_TIMEOUT_MS) != 0)
                val pending = checkNotNull(pendingReference.value) { "$service.$method disconnected" }
                try {
                    // Never flush/block a pending call: libdbus can wait indefinitely for
                    // authentication or a writable socket before starting its reply timeout.
                    while (api.dbus_pending_call_get_completed(pending) == 0) {
                        check(!Thread.currentThread().isInterrupted) { "$service.$method cancelled" }
                        val remaining = deadline - System.nanoTime()
                        check(remaining > 0) { "$service.$method timed out" }
                        val pollMs = TimeUnit.NANOSECONDS.toMillis(remaining).coerceIn(1, IO_POLL_MS.toLong()).toInt()
                        check(api.dbus_connection_read_write_dispatch(connection, pollMs) != 0) { "$service.$method disconnected" }
                    }
                    val reply = checkNotNull(api.dbus_pending_call_steal_reply(pending)) { "$service.$method failed" }
                    if (api.dbus_message_get_type(reply) != METHOD_RETURN) {
                        api.dbus_message_unref(reply)
                        error("$service.$method failed")
                    }
                    return reply
                } finally {
                    api.dbus_pending_call_cancel(pending)
                    api.dbus_pending_call_unref(pending)
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
    fun dbus_address_escape_value(value: String): Pointer?
    fun dbus_free(memory: Pointer)
    fun dbus_connection_open_private(address: String, error: Pointer?): Pointer?
    fun dbus_bus_set_unique_name(connection: Pointer, name: String): Int
    fun dbus_connection_set_exit_on_disconnect(connection: Pointer, enabled: Int)
    fun dbus_connection_close(connection: Pointer)
    fun dbus_connection_unref(connection: Pointer)
    fun dbus_message_new_method_call(destination: String, path: String, iface: String, method: String): Pointer?
    fun dbus_message_append_args(message: Pointer, firstType: Int, vararg args: Any): Int
    fun dbus_message_get_args(message: Pointer, error: Pointer?, firstType: Int, vararg args: Any): Int
    fun dbus_connection_send_with_reply(connection: Pointer, message: Pointer, pending: PointerByReference, timeout: Int): Int
    fun dbus_connection_read_write_dispatch(connection: Pointer, timeout: Int): Int
    fun dbus_pending_call_get_completed(pending: Pointer): Int
    fun dbus_pending_call_steal_reply(pending: Pointer): Pointer?
    fun dbus_pending_call_cancel(pending: Pointer)
    fun dbus_pending_call_unref(pending: Pointer)
    fun dbus_message_get_type(message: Pointer): Int
    fun dbus_message_unref(message: Pointer)
}
