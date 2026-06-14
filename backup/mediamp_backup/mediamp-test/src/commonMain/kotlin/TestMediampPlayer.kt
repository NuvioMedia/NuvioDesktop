package org.openani.mediamp.test

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.openani.mediamp.AbstractMediampPlayer
import org.openani.mediamp.InternalForInheritanceMediampApi
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.MediampPlayerFactory
import org.openani.mediamp.PlaybackState
import org.openani.mediamp.features.AspectRatioMode
import org.openani.mediamp.features.MediaMetadata
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.features.PlayerFeatures
import org.openani.mediamp.features.VideoAspectRatio
import org.openani.mediamp.features.buildPlayerFeatures
import org.openani.mediamp.metadata.AudioTrack
import org.openani.mediamp.metadata.Chapter
import org.openani.mediamp.metadata.MediaProperties
import org.openani.mediamp.metadata.SubtitleTrack
import org.openani.mediamp.metadata.TrackGroup
import org.openani.mediamp.metadata.emptyTrackGroup
import org.openani.mediamp.source.MediaData
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass


/**
 * For previewing
 */
@OptIn(InternalForInheritanceMediampApi::class, InternalMediampApi::class)
public class TestMediampPlayer(
    // TODO: 2024/12/22 move to preview package
    defaultDispatcher: CoroutineContext = Dispatchers.Default,
) : AbstractMediampPlayer<AbstractMediampPlayer.Data>(defaultDispatcher) {
    override val impl: Any get() = this

    override val mediaProperties: MutableStateFlow<MediaProperties?> = MutableStateFlow(
        MediaProperties(
            title = "Test Video",
            durationMillis = 100_000,
        ),
    )

    override fun getCurrentMediaProperties(): MediaProperties? = mediaProperties.value

    override val currentPositionMillis: MutableStateFlow<Long> = MutableStateFlow(10_000L)
    override fun getCurrentPositionMillis(): Long {
        return currentPositionMillis.value
    }

    override fun getCurrentPlaybackState(): PlaybackState {
        return playbackState.value
    }

    override val features: PlayerFeatures = buildPlayerFeatures {
        add(
            PlaybackSpeed,
            object : PlaybackSpeed {
                override val valueFlow: MutableStateFlow<Float> = MutableStateFlow(1f)
                override val value: Float get() = valueFlow.value
                override fun set(speed: Float) {
                    valueFlow.value = speed
                }
            },
        )
        add(
            MediaMetadata,
            object : MediaMetadata {
                override val audioTracks: TrackGroup<AudioTrack> = emptyTrackGroup()
                override val subtitleTracks: TrackGroup<SubtitleTrack> = emptyTrackGroup()
                override val chapters: Flow<List<Chapter>> = MutableStateFlow(
                    listOf(
                        Chapter("chapter1", durationMillis = 90_000L, 0L),
                        Chapter("chapter2", durationMillis = 5_000L, 90_000L),
                    ),
                )
            },
        )
        add(
            VideoAspectRatio,
            object : VideoAspectRatio {
                override val mode = MutableStateFlow(AspectRatioMode.FIT)
                override fun setMode(mode: AspectRatioMode) {
                    this.mode.value = mode
                }
            },
        )
    }

    override suspend fun setMediaDataImpl(data: MediaData): Data {
        currentPositionMillis.value = 0L
        return Data(data)
    }

    override fun seekTo(positionMillis: Long) {
        this.currentPositionMillis.value = positionMillis
    }

    override fun resumeImpl() {
        playbackState.value = PlaybackState.PLAYING
    }

    override fun pauseImpl() {
        playbackState.value = PlaybackState.PAUSED
    }

    override fun stopPlaybackImpl() {
        currentPositionMillis.value = 0
        mediaProperties.value = null
        playbackState.value = PlaybackState.FINISHED
        // TODO: 2025/1/5 We should encapsulate the mutable states to ensure consistency in flow emissions
    }

    override fun closeImpl() {
        playbackState.value = PlaybackState.DESTROYED
    }

    public object Factory : MediampPlayerFactory<TestMediampPlayer> {
        override val forClass: KClass<TestMediampPlayer> = TestMediampPlayer::class

        override fun create(context: Any, parentCoroutineContext: CoroutineContext): TestMediampPlayer {
            return TestMediampPlayer(parentCoroutineContext)
        }
    }
}