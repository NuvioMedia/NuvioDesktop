package com.nuvio.app.features.settings

internal actual object DesktopRendererSettings {
    actual val isSupported: Boolean = false
    actual val useOpenGl: Boolean = false
    actual val isExternallyControlled: Boolean = false
    actual val steadyFramePacingEnabled: Boolean = false

    actual fun setUseOpenGl(enabled: Boolean) = Unit
    actual fun setSteadyFramePacingEnabled(enabled: Boolean) = Unit
}
