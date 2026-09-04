package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerExitOrderingTest {
    @Test
    fun systemBackDoesNotPopPlayerUntilGuardedReleaseCompletes() {
        val events = mutableListOf<String>()
        var completeRelease: (() -> Unit)? = null
        val playerBack = {
            releasePlayerBeforeNavigation(
                releasePlayer = { onReleased, _ ->
                    events += "release-started"
                    completeRelease = onReleased
                },
                navigateBack = { events += "pop" },
            )
        }

        dispatchNavigationBack(
            isPlayerRoute = true,
            playerBack = playerBack,
            pop = { events += "direct-pop" },
        )

        assertEquals(listOf("release-started"), events)
        completeRelease?.invoke()
        assertEquals(listOf("release-started", "pop"), events)
    }

    @Test
    fun systemBackFailsClosedDuringPlayerHandlerRegistrationGap() {
        val events = mutableListOf<String>()

        dispatchNavigationBack(
            isPlayerRoute = true,
            playerBack = null,
            pop = { events += "direct-pop" },
        )

        assertEquals(emptyList(), events)
    }

    @Test
    fun retainedControllerBarriersBackDuringActiveControllerGap() {
        val events = mutableListOf<String>()
        var complete: (() -> Unit)? = null
        val retained = testController { onReleased ->
            events += "release-started"
            complete = onReleased
        }

        releaseRetainedPlayerBeforeNavigation(
            controller = retained,
            navigateBack = { events += "navigate" },
        )

        assertEquals(listOf("release-started"), events)
        complete?.invoke()
        assertEquals(listOf("release-started", "navigate"), events)
    }

    @Test
    fun waitsForPlayerReleaseBeforeLeavingRoute() {
        val events = mutableListOf<String>()
        var completeRelease: (() -> Unit)? = null

        releasePlayerBeforeNavigation(
            releasePlayer = { onReleased, _ ->
                events += "release-started"
                completeRelease = onReleased
            },
            navigateBack = { events += "navigate" },
        )

        assertEquals(listOf("release-started"), events)
        completeRelease?.invoke()
        assertEquals(listOf("release-started", "navigate"), events)
    }

    private fun testController(release: (() -> Unit) -> Unit) = object : PlayerEngineController {
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun seekBy(offsetMs: Long) = Unit
        override fun retry() = Unit
        override fun setPlaybackSpeed(speed: Float) = Unit
        override fun getAudioTracks() = emptyList<AudioTrack>()
        override fun getSubtitleTracks() = emptyList<SubtitleTrack>()
        override fun selectAudioTrack(index: Int) = Unit
        override fun selectSubtitleTrack(index: Int) = Unit
        override fun setSubtitleUri(url: String) = Unit
        override fun clearExternalSubtitle() = Unit
        override fun clearExternalSubtitleAndSelect(trackIndex: Int) = Unit
        override fun releaseBeforeNavigation(
            onReleased: () -> Unit,
            onReleaseFailed: (String) -> Unit,
        ) = release(onReleased)
    }
}
