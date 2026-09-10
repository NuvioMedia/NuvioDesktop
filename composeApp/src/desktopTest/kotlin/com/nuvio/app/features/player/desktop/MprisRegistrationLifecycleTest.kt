package com.nuvio.app.features.player.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class MprisRegistrationLifecycleTest {
    @Test
    fun metadataReceivedBeforeRegistrationIsPublishedAfterRegistration() {
        val events = mutableListOf<String>()
        val lifecycle = lifecycle(events)

        lifecycle.updateMetadata("episode-2")

        assertEquals(emptyList(), events)

        lifecycle.register()

        assertEquals(listOf("register", "metadata:episode-2"), events)
    }

    @Test
    fun clearingBeforeRegistrationPreventsStaleMetadataFromBeingPublished() {
        val events = mutableListOf<String>()
        val lifecycle = lifecycle(events)

        lifecycle.updateMetadata("episode-2")
        lifecycle.clear()
        lifecycle.register()

        assertEquals(listOf("register"), events)
    }

    @Test
    fun unregisteringClearsCachedMetadataBeforeASecondRegistration() {
        val events = mutableListOf<String>()
        val lifecycle = lifecycle(events)

        lifecycle.updateMetadata("episode-2")
        lifecycle.register()
        lifecycle.unregister()
        lifecycle.register()

        assertEquals(
            listOf("register", "metadata:episode-2", "unregister", "register"),
            events,
        )
    }

    private fun lifecycle(events: MutableList<String>) = MprisRegistrationLifecycle<String>(
        onRegister = { events += "register" },
        onUnregister = { events += "unregister" },
        onUpdateMetadata = { metadata -> events += "metadata:$metadata" },
        onClear = { events += "clear" },
    )
}
