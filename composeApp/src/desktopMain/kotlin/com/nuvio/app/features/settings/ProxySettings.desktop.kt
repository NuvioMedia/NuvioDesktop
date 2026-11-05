package com.nuvio.app.features.settings

import com.nuvio.app.core.storage.DesktopStorage
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.ProxySelector as JvmProxySelector

internal actual object ProxySettingsPlatform {
    actual val isSupported: Boolean = true
}

internal actual object ProxySettingsStorage {
    private const val modeKey = "mode"
    private const val manualKey = "manual_endpoint"
    private const val detectedKey = "detected_endpoint"
    private const val bypassKey = "bypass"
    private val store by lazy { DesktopStorage.store("nuvio_proxy_settings") }

    actual fun load(): ProxySettings = ProxySettings(
        mode = store.getString(modeKey)
            ?.let { stored -> ProxyMode.entries.firstOrNull { it.name == stored } }
            ?: ProxyMode.Automatic,
        manualEndpoint = store.getString(manualKey).orEmpty(),
        detectedEndpoint = store.getString(detectedKey).orEmpty(),
        bypass = store.getString(bypassKey).orEmpty(),
    )

    actual fun save(settings: ProxySettings) {
        store.putString(modeKey, settings.mode.name)
        store.putString(manualKey, settings.manualEndpoint)
        store.putString(detectedKey, settings.detectedEndpoint)
        store.putString(bypassKey, settings.bypass)
    }
}

/**
 * The proxy the app should use, resolved once and read by every HTTP client it builds.
 *
 * A client is built at class-initialisation time in several places (the addon client, the
 * trailer client, the subtitle loader), so this has to answer without doing I/O. It caches the
 * stored settings and is refreshed when the person saves a change.
 */
internal actual object DesktopProxy {
    private val lock = Any()
    private var cached: ProxySettings = ProxySettings()
    private var loaded = false

    /** Called at startup, before any client is built. */
    actual fun initialize() {
        synchronized(lock) {
            cached = runCatching { ProxySettingsStorage.load() }.getOrDefault(ProxySettings())
            loaded = true
            refreshDetectedEndpointIfNeeded()
        }
    }

    actual fun current(): ProxySettings = synchronized(lock) {
        if (!loaded) initialize()
        cached
    }

    /** Saves a change and makes it visible to clients built from now on. */
    actual fun update(settings: ProxySettings) {
        synchronized(lock) {
            cached = settings
            loaded = true
        }
        runCatching { ProxySettingsStorage.save(settings) }
    }

    /** Runs automatic detection and stores what it found, keeping the person's other choices. */
    actual fun detectNow(): ProxySettings {
        val found = runCatching { ProxyAutoDetector.detect(System.getenv()) }.getOrNull()
            ?: detectFromSystem()
        val settings = synchronized(lock) {
            cached = cached.copy(
                detectedEndpoint = found?.endpoint.orEmpty(),
                bypass = found?.bypass.takeUnless { it.isNullOrBlank() } ?: cached.bypass,
            )
            cached
        }
        runCatching { ProxySettingsStorage.save(settings) }
        return settings
    }

    /** The proxy for OkHttp, or [Proxy.NO_PROXY]. */
    fun okHttpProxy(): Proxy {
        val endpoint = current().activeEndpoint() ?: return Proxy.NO_PROXY
        val type = if (endpoint.scheme == "socks") Proxy.Type.SOCKS else Proxy.Type.HTTP
        return Proxy(type, InetSocketAddress.createUnresolved(endpoint.host, endpoint.port))
    }

    /**
     * The proxy for a Ktor engine's `engine { proxy = ... }`. Ktor takes a bare `java.net.Proxy`
     * and has no per-host bypass, so a bypass list can't be honoured on these clients; the
     * OkHttp clients (addons, trailers, subtitles) do honour it.
     */
    fun ktorProxy(): Proxy = okHttpProxy()

    private fun refreshDetectedEndpointIfNeeded() {
        if (cached.mode != ProxyMode.Automatic) return
        if (cached.detectedEndpoint.isNotBlank()) return
        val found = runCatching { ProxyAutoDetector.detect(System.getenv()) }.getOrNull()
            ?: detectFromSystem()
            ?: return
        cached = cached.copy(
            detectedEndpoint = found.endpoint,
            bypass = cached.bypass.ifBlank { found.bypass },
        )
        runCatching { ProxySettingsStorage.save(cached) }
    }

    /**
     * Asks the JVM for the system proxy. On Linux this reads the GNOME/KDE settings through
     * `java.net.useSystemProxies`; on macOS it reads the network configuration; on Windows it
     * reads the WinINET settings. It only ever answers about `https://example.com`, which is
     * enough to learn the proxy's address.
     */
    private fun detectFromSystem(): ProxyAutoDetector.Detection? = runCatching {
        val previous = System.getProperty("java.net.useSystemProxies")
        System.setProperty("java.net.useSystemProxies", "true")
        try {
            val proxies = JvmProxySelector.getDefault()
                .select(URI("https://example.com"))
                .firstOrNull { it.type() != Proxy.Type.DIRECT }
                ?: return@runCatching null
            val address = proxies.address() as? InetSocketAddress ?: return@runCatching null
            val host = address.hostString
            val port = address.port
            if (host.isNullOrBlank() || port <= 0) return@runCatching null
            val scheme = if (proxies.type() == Proxy.Type.SOCKS) "socks" else "http"
            ProxyAutoDetector.Detection(
                endpoint = "$scheme://$host:$port",
                bypass = "",
                source = "system",
            )
        } finally {
            if (previous == null) {
                System.clearProperty("java.net.useSystemProxies")
            } else {
                System.setProperty("java.net.useSystemProxies", previous)
            }
        }
    }.getOrNull()
}
