package com.nuvio.app.features.settings

internal actual object ProxySettingsPlatform {
    actual val isSupported: Boolean = false
}

internal actual object ProxySettingsStorage {
    actual fun load(): ProxySettings = ProxySettings(mode = ProxyMode.Off)
    actual fun save(settings: ProxySettings) = Unit
}

package com.nuvio.app.features.settings

internal actual object DesktopProxy {
    actual fun initialize() = Unit
    actual fun current(): ProxySettings = ProxySettings(mode = ProxyMode.Off)
    actual fun update(settings: ProxySettings) = Unit
    actual fun detectNow(): ProxySettings = ProxySettings(mode = ProxyMode.Off)
}
