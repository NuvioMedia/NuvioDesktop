package com.nuvio.app.features.player.desktop

import com.nuvio.app.core.storage.DesktopStorage

/**
 * Per-machine UI render-backend preference (Direct3D vs OpenGL). Kept separate from the synced
 * player settings so it never travels between devices. Read once at startup in `main()` before
 * Compose initializes, so it must not depend on any Compose state.
 */
internal object DesktopRenderSettings {
    private const val useOpenGlKey = "use_opengl_renderer"
    private val store by lazy { DesktopStorage.store("nuvio_desktop_render") }

    fun isOpenGlEnabled(): Boolean = store.getBoolean(useOpenGlKey) ?: false

    fun setOpenGlEnabled(enabled: Boolean) = store.putBoolean(useOpenGlKey, enabled)
}
