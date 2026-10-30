package com.nuvio.app.features.home

import com.nuvio.app.features.addons.AddonCatalog
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonResource
import com.nuvio.app.features.addons.ManagedAddon
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeRepositoryLoadingTest {
    @Test
    fun healthyCatalogsRenderAndLaterCatalogsStartBeforeAStalledCatalogFinishes() = runBlocking {
        val releaseSlow = CountDownLatch(1)
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/") { exchange ->
            val active = inFlight.incrementAndGet()
            maxInFlight.accumulateAndGet(active, ::maxOf)
            val id = exchange.requestURI.path.substringAfterLast('/').removeSuffix(".json")
            try {
                if (id == "c0") check(releaseSlow.await(10, TimeUnit.SECONDS))
                val body = """{"metas":[{"id":"$id","type":"movie","name":"$id"}]}""".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } finally {
                exchange.close()
                inFlight.decrementAndGet()
            }
        }
        server.start()
        try {
            val manifestUrl = "http://127.0.0.1:${server.address.port}/manifest.json"
            val addon = ManagedAddon(
                manifestUrl = manifestUrl,
                manifest = AddonManifest(
                    id = "home-loading-test",
                    name = "Catalog loading test",
                    description = "",
                    version = "1",
                    resources = listOf(AddonResource("catalog", listOf("movie"))),
                    types = listOf("movie"),
                    catalogs = (0..5).map { AddonCatalog("movie", "c$it", "Catalog $it") },
                    transportUrl = manifestUrl,
                ),
            )
            HomeRepository.clear()
            HomeRepository.refresh(listOf(addon), force = true)

            val partial = withTimeout(5_000) {
                HomeRepository.uiState.first { it.sections.size == 5 }
            }
            assertEquals(
                (1..5).map { "home-loading-test:movie:c$it" },
                partial.sections.map { it.key },
            )
            assertTrue(partial.isLoading, "The stalled catalog must still be pending")
            assertEquals(1L, releaseSlow.count)

            releaseSlow.countDown()
            val complete = withTimeout(5_000) {
                HomeRepository.uiState.first { !it.isLoading }
            }
            assertEquals(
                (0..5).map { "home-loading-test:movie:c$it" },
                complete.sections.map { it.key },
                "Completion order must not change the configured shelf order",
            )
            assertTrue(maxInFlight.get() <= 4, "Keep the existing catalog concurrency limit")
        } finally {
            releaseSlow.countDown()
            HomeRepository.clear()
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
