package com.nuvio.app.features.settings

internal expect object DesktopRendererSettings {
    val isSupported: Boolean
    val useOpenGl: Boolean
    val isExternallyControlled: Boolean
    val steadyFramePacingEnabled: Boolean

    fun setUseOpenGl(enabled: Boolean)
    fun setSteadyFramePacingEnabled(enabled: Boolean)
}
