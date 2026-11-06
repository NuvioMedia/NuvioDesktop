package com.nuvio.app.features.player

import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.features.addons.AddonManifestParser
import com.nuvio.app.features.addons.ManagedAddon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AddonSubtitleLookupTest {
    private val manifestUrl = "https://opensubtitles-v3.strem.io/manifest.json"
    private val addons = listOf(ManagedAddon(
        manifestUrl = manifestUrl,
        manifest = AddonManifestParser.parse(manifestUrl, """
            {"id":"org.stremio.opensubtitlesv3","version":"1.0.0","name":"OpenSubtitles v3",
             "resources":["subtitles"],"types":["movie","series"],"idPrefixes":["tt"]}
        """.trimIndent()),
    ))

    @Test
    fun backendOutageDoesNotPreventOpenSubtitlesLookup() {
        assertEquals(
            assertNotNull(key(NetworkCondition.Online)),
            assertNotNull(key(NetworkCondition.ServersUnreachable)),
        )
    }

    @Test
    fun missingInternetDefersLookupUntilConnectivityReturns() {
        assertNull(key(NetworkCondition.NoInternet))
        assertNotNull(key(NetworkCondition.Online))
    }

    @Test
    fun unconfirmedConnectivityDoesNotSuppressSubtitleLookup() {
        assertNotNull(key(NetworkCondition.Unknown))
        assertNotNull(key(NetworkCondition.Checking))
    }

    private fun key(condition: NetworkCondition) =
        buildAddonSubtitleFetchKey(addons, "movie", "tt22084616", condition)
}
