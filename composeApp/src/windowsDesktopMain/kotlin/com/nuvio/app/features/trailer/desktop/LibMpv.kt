package com.nuvio.app.features.trailer.desktop

import com.nuvio.app.features.player.desktop.NativePlayerBridge
import com.nuvio.app.features.trailer.TrailerExtractionPlatform
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.io.File

/**
 * The subset of the libmpv client and render API used for inline trailer playback.
 * The bundled libmpv-2.dll is the same runtime the main player bridge loads.
 */
@Suppress("FunctionName")
internal interface LibMpv : Library {
    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_terminate_destroy(ctx: Pointer)
    fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_set_property_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
    fun mpv_free(data: Pointer)
    fun mpv_command(ctx: Pointer, args: Array<String>): Int
    fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer?
    fun mpv_error_string(error: Int): String?
    fun mpv_render_context_create(result: PointerByReference, mpv: Pointer, params: Pointer): Int
    fun mpv_render_context_update(ctx: Pointer): Long
    fun mpv_render_context_render(ctx: Pointer, params: Pointer): Int
    fun mpv_render_context_free(ctx: Pointer)

    companion object {
        const val EVENT_NONE = 0
        const val EVENT_SHUTDOWN = 1
        const val EVENT_END_FILE = 7
        const val EVENT_FILE_LOADED = 8

        const val END_FILE_REASON_ERROR = 4

        const val RENDER_PARAM_INVALID = 0
        const val RENDER_PARAM_API_TYPE = 1
        const val RENDER_PARAM_SW_SIZE = 17
        const val RENDER_PARAM_SW_FORMAT = 18
        const val RENDER_PARAM_SW_STRIDE = 19
        const val RENDER_PARAM_SW_POINTER = 20

        const val RENDER_UPDATE_FRAME = 1L

        /** Null when libmpv cannot be loaded; trailers then report an error and stay hidden. */
        val instance: LibMpv? by lazy(::load)

        private fun load(): LibMpv? {
            val bundled = runCatching { NativePlayerBridge.nativeLibraryDirectory }
                .getOrNull()
                ?.resolve("libmpv-2.dll")
                ?.takeIf(File::isFile)
                ?.absolutePath
            val candidates = listOfNotNull(
                System.getenv("NUVIO_LIBMPV_PATH")?.takeIf(String::isNotBlank),
                bundled,
                "libmpv-2",
            )
            val options = mapOf(Library.OPTION_STRING_ENCODING to "UTF-8")
            for (candidate in candidates) {
                val loaded = runCatching { Native.load(candidate, LibMpv::class.java, options) }
                    .onFailure { error ->
                        TrailerExtractionPlatform.diagnostic("libmpv load failed candidate=$candidate detail=${error.message}")
                    }
                    .getOrNull()
                if (loaded != null) return loaded
            }
            return null
        }
    }
}
