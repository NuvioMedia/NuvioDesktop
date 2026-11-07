package com.nuvio.app.features.settings

/**
 * How the desktop app reaches the network when a proxy is in the way.
 *
 * [Automatic] follows what the operating system already knows: the `HTTP_PROXY`,
 * `HTTPS_PROXY` and `NO_PROXY` environment variables when they're set, and the system proxy
 * settings otherwise. [Manual] uses exactly what the person typed. [Off] goes direct.
 */
internal enum class ProxyMode {
    Automatic,
    Manual,
    Off,
}

/** A proxy address, split into the parts every HTTP client needs. */
internal data class ProxyEndpoint(
    val host: String,
    val port: Int,
    val scheme: String = "http",
) {
    /** `host:port`, the form the JVM's proxy selector wants. */
    val hostPort: String get() = "$host:$port"

    /** `http://host:port`, the form OkHttp and Ktor want. */
    val url: String get() = "$scheme://$host:$port"

    companion object {
        /**
         * Reads `host:port`, `http://host:port`, `https://host:port` or a bare host.
         * A bare host takes the scheme's default port. Anything else is rejected, so a typo
         * shows up as an error in the field instead of silently going direct.
         */
        fun parse(raw: String): ProxyEndpoint? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null

            val withScheme = when {
                trimmed.contains("://") -> trimmed
                else -> "http://$trimmed"
            }
            val scheme = withScheme.substringBefore("://").lowercase()
            if (scheme != "http" && scheme != "https" && scheme != "socks") return null

            val authority = withScheme
                .substringAfter("://")
                .substringBefore("/")
                .substringAfterLast("@")
            if (authority.isEmpty()) return null

            val defaultPort = when (scheme) {
                "https" -> 443
                "socks" -> 1080
                else -> 8080
            }

            // An IPv6 literal arrives bracketed: [::1]:8080
            if (authority.startsWith("[")) {
                val close = authority.indexOf(']')
                if (close <= 1) return null
                val host = authority.substring(1, close)
                val port = authority.substring(close + 1).removePrefix(":").toIntOrNull() ?: defaultPort
                if (port !in 1..65535) return null
                return ProxyEndpoint(host, port, scheme)
            }

            val host = authority.substringBefore(":")
            val port = authority.substringAfter(":", "").toIntOrNull() ?: defaultPort
            if (host.isBlank() || port !in 1..65535) return null
            return ProxyEndpoint(host, port, scheme)
        }
    }
}

/** What the person chose, and what was found when they asked for automatic detection. */
internal data class ProxySettings(
    val mode: ProxyMode = ProxyMode.Automatic,
    val manualEndpoint: String = "",
    val detectedEndpoint: String = "",
    val bypass: String = "",
) {
    /** The proxy to use right now, or null for a direct connection. */
    fun activeEndpoint(): ProxyEndpoint? = when (mode) {
        ProxyMode.Off -> null
        ProxyMode.Manual -> ProxyEndpoint.parse(manualEndpoint)
        ProxyMode.Automatic -> ProxyEndpoint.parse(detectedEndpoint)
    }

    /** Hosts that skip the proxy, as `NO_PROXY` lists them. */
    fun bypassList(): List<String> =
        bypass.split(',', ';', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}

internal expect object ProxySettingsPlatform {
    /** True where a proxy can be configured at all (the desktop app today). */
    val isSupported: Boolean
}

internal expect object ProxySettingsStorage {
    fun load(): ProxySettings
    fun save(settings: ProxySettings)
}

/**
 * The proxy the app should use, resolved once and read by every HTTP client it builds.
 *
 * Clients are built at class-initialisation time in several places (the addon client, the
 * trailer client, the subtitle loader), so this answers without doing I/O: it caches the stored
 * settings and is refreshed when the person saves a change.
 */
internal expect object DesktopProxy {
    /** Called at startup, before any client is built. */
    fun initialize()

    fun current(): ProxySettings

    /** Saves a change and makes it visible to clients built from now on. */
    fun update(settings: ProxySettings)

    /** Runs automatic detection and stores what it found, keeping the person's other choices. */
    fun detectNow(): ProxySettings
}

/**
 * Automatic detection reads the environment first, because that is how a proxy reaches a
 * desktop app that was started from a shell, a launcher script or a systemd unit. The system
 * settings are the fallback for a person whose proxy is configured in the desktop environment.
 *
 * Kept free of Android/iOS types so it can be tested on the JVM.
 */
internal object ProxyAutoDetector {
    private val httpVariables = listOf("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy")
    private val bypassVariables = listOf("NO_PROXY", "no_proxy")

    data class Detection(val endpoint: String, val bypass: String, val source: String)

    fun detect(environment: Map<String, String>): Detection? {
        val endpoint = httpVariables
            .firstNotNullOfOrNull { name -> environment[name]?.trim()?.takeIf { it.isNotEmpty() } }
        val bypass = bypassVariables
            .firstNotNullOfOrNull { name -> environment[name]?.trim()?.takeIf { it.isNotEmpty() } }
            .orEmpty()
        if (endpoint == null) return null
        return Detection(endpoint = endpoint, bypass = bypass, source = "environment")
    }
}
