package com.nuvio.app.features.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProxyEndpointTest {
    @Test
    fun readsHostAndPort() {
        val endpoint = ProxyEndpoint.parse("http://127.0.0.1:8080")
        assertNotNull(endpoint)
        assertEquals("127.0.0.1", endpoint.host)
        assertEquals(8080, endpoint.port)
        assertEquals("http", endpoint.scheme)
        assertEquals("127.0.0.1:8080", endpoint.hostPort)
        assertEquals("http://127.0.0.1:8080", endpoint.url)
    }

    @Test
    fun acceptsABareHostWithTheDefaultPort() {
        val endpoint = ProxyEndpoint.parse("proxy.local")
        assertNotNull(endpoint)
        assertEquals("proxy.local", endpoint.host)
        assertEquals(8080, endpoint.port)
    }

    @Test
    fun keepsTheSchemeItWasGiven() {
        assertEquals("https", ProxyEndpoint.parse("https://proxy.local:3128")?.scheme)
        assertEquals("socks", ProxyEndpoint.parse("socks://proxy.local:1080")?.scheme)
        assertEquals(443, ProxyEndpoint.parse("https://proxy.local")?.port)
        assertEquals(1080, ProxyEndpoint.parse("socks://proxy.local")?.port)
    }

    @Test
    fun readsAnIpv6Literal() {
        val endpoint = ProxyEndpoint.parse("http://[::1]:8080")
        assertNotNull(endpoint)
        assertEquals("::1", endpoint.host)
        assertEquals(8080, endpoint.port)
    }

    @Test
    fun rejectsWhatIsNotAProxy() {
        assertNull(ProxyEndpoint.parse(""))
        assertNull(ProxyEndpoint.parse("   "))
        assertNull(ProxyEndpoint.parse("ftp://proxy.local:21"))
        assertNull(ProxyEndpoint.parse("http://proxy.local:0"))
        assertNull(ProxyEndpoint.parse("http://proxy.local:70000"))
    }

    @Test
    fun ignoresCredentialsInTheAuthority() {
        val endpoint = ProxyEndpoint.parse("http://user:pass@proxy.local:8080")
        assertNotNull(endpoint)
        assertEquals("proxy.local", endpoint.host)
        assertEquals(8080, endpoint.port)
    }
}

class ProxySettingsTest {
    @Test
    fun offNeverUsesAProxy() {
        val settings = ProxySettings(mode = ProxyMode.Off, manualEndpoint = "http://127.0.0.1:8080")
        assertNull(settings.activeEndpoint())
    }

    @Test
    fun manualUsesWhatThePersonTyped() {
        val settings = ProxySettings(mode = ProxyMode.Manual, manualEndpoint = "http://10.0.0.2:3128")
        assertEquals("10.0.0.2:3128", settings.activeEndpoint()?.hostPort)
    }

    @Test
    fun manualWithAnInvalidAddressFallsBackToDirect() {
        val settings = ProxySettings(mode = ProxyMode.Manual, manualEndpoint = "not a proxy")
        assertNull(settings.activeEndpoint())
    }

    @Test
    fun automaticUsesWhatDetectionFound() {
        val settings = ProxySettings(
            mode = ProxyMode.Automatic,
            manualEndpoint = "http://ignored:1",
            detectedEndpoint = "http://127.0.0.1:7890",
        )
        assertEquals("127.0.0.1:7890", settings.activeEndpoint()?.hostPort)
    }

    @Test
    fun automaticWithNothingDetectedGoesDirect() {
        val settings = ProxySettings(mode = ProxyMode.Automatic, detectedEndpoint = "")
        assertNull(settings.activeEndpoint())
    }

    @Test
    fun bypassIsSplitOnEverySeparator() {
        val settings = ProxySettings(bypass = "localhost, 127.0.0.1;.local  example.com")
        assertEquals(listOf("localhost", "127.0.0.1", ".local", "example.com"), settings.bypassList())
    }

    @Test
    fun anEmptyBypassListIsEmpty() {
        assertEquals(emptyList(), ProxySettings(bypass = "  ,  ; ").bypassList())
    }
}

class ProxyAutoDetectorTest {
    @Test
    fun readsTheUppercaseHttpsVariableFirst() {
        val found = ProxyAutoDetector.detect(
            mapOf(
                "HTTPS_PROXY" to "http://127.0.0.1:7890",
                "HTTP_PROXY" to "http://127.0.0.1:1080",
            ),
        )
        assertEquals("http://127.0.0.1:7890", found?.endpoint)
        assertEquals("environment", found?.source)
    }

    @Test
    fun readsTheLowercaseVariablesToo() {
        val found = ProxyAutoDetector.detect(mapOf("http_proxy" to "http://10.0.0.2:3128"))
        assertEquals("http://10.0.0.2:3128", found?.endpoint)
    }

    @Test
    fun picksUpTheBypassList() {
        val found = ProxyAutoDetector.detect(
            mapOf(
                "HTTPS_PROXY" to "http://127.0.0.1:7890",
                "NO_PROXY" to "localhost,127.0.0.1",
            ),
        )
        assertEquals("localhost,127.0.0.1", found?.bypass)
    }

    @Test
    fun findsNothingWithoutAProxy() {
        assertNull(ProxyAutoDetector.detect(emptyMap()))
        assertNull(ProxyAutoDetector.detect(mapOf("NO_PROXY" to "localhost")))
    }

    @Test
    fun ignoresBlankValues() {
        assertNull(ProxyAutoDetector.detect(mapOf("HTTPS_PROXY" to "   ")))
    }
}

/**
 * A direct connection must never reach a Ktor engine as `Proxy.NO_PROXY`.
 *
 * Ktor's CIO engine maps `Proxy.Type.DIRECT` to `ProxyType.UNKNOWN` and throws in its
 * constructor ("CIO engine does not currently support UNKNOWN proxies"), which crashed the
 * desktop app on startup for everyone, proxy or not. The clients therefore set `proxy` only
 * when there is one, and this checks the value they would set.
 */
class KtorProxySelectionTest {
    @Test
    fun aDirectConnectionYieldsNoProxyValue() {
        val settings = ProxySettings(mode = ProxyMode.Off)
        assertNull(settings.activeEndpoint())
    }

    @Test
    fun automaticWithNothingDetectedYieldsNoProxyValue() {
        val settings = ProxySettings(mode = ProxyMode.Automatic, detectedEndpoint = "")
        assertNull(settings.activeEndpoint())
    }

    @Test
    fun aConfiguredProxyYieldsAnEndpointToSet() {
        val settings = ProxySettings(mode = ProxyMode.Manual, manualEndpoint = "http://127.0.0.1:8080")
        assertNotNull(settings.activeEndpoint())
    }
}
