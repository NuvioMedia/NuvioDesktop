package com.nuvio.app.features.streams

import com.nuvio.app.features.servers.ServerStreamSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class StreamSearchRefreshTest {
    @Test
    fun refreshKeepsExistingSubscribersSearchAlive() = runBlocking {
        StreamSearchSessions.clear()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        fun targets(url: String, wait: Boolean) = StreamSearchTargets(
            type = "series",
            videoId = "refresh-test",
            season = 1,
            episode = 1,
            streamAddons = emptyList(),
            serverSources = listOf(ServerStreamSource("server:test", "Server", false) {
                if (wait) {
                    started.complete(Unit)
                    release.await()
                }
                listOf(StreamItem(name = "1080p", url = url, addonName = "Server", addonId = "server:test"))
            }),
            pluginProviderGroups = emptyList(),
        )

        val original = async { StreamSearchSessions.search(1, targets("https://old.test/video", true), false).toList() }
        started.await()
        val refreshed = StreamSearchSessions.search(1, targets("https://new.test/video", false), true).toList()
        release.complete(Unit)

        assertEquals("https://old.test/video", (original.await().single() as StreamLoadCompletion.Addon).group.streams.single().url)
        assertEquals("https://new.test/video", (refreshed.single() as StreamLoadCompletion.Addon).group.streams.single().url)
        StreamSearchSessions.clear()
    }
}
