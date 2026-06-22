package com.nuvio.app.features.addons

import kotlin.test.Test
import kotlin.test.assertEquals

class AddonTransportUrlsTest {

    @Test
    fun `unsafe characters in manifest path are encoded for resource urls`() {
        val manifestUrl = "https://torrentio.strem.fun/realdebrid=abc|premiumize=xyz/manifest.json"

        val streamUrl = buildAddonResourceUrl(
            manifestUrl = manifestUrl,
            resource = "stream",
            type = "movie",
            id = "tt0111161",
        )

        assertEquals(
            "https://torrentio.strem.fun/realdebrid=abc%7Cpremiumize=xyz/stream/movie/tt0111161.json",
            streamUrl,
        )
    }

    @Test
    fun `unsafe url encoding does not double encode existing escapes`() {
        val manifestUrl = "https://torrentio.strem.fun/realdebrid=abc%7Cpremiumize=xyz/manifest.json"

        assertEquals(
            manifestUrl,
            manifestUrl.encodeUnsafeHttpUrlCharacters(),
        )
    }

    @Test
    fun `unsafe characters in manifest query are encoded`() {
        val manifestUrl = "https://example.test/addon/manifest.json?config=one|two"

        val catalogUrl = buildAddonResourceUrl(
            manifestUrl = manifestUrl,
            resource = "catalog",
            type = "movie",
            id = "popular",
            extraPathSegment = "skip=0",
        )

        assertEquals(
            "https://example.test/addon/catalog/movie/popular/skip=0.json?config=one%7Ctwo",
            catalogUrl,
        )
    }
}
