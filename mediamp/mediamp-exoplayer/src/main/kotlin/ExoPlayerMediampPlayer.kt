/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

@file:kotlin.OptIn(InternalMediampApi::class)

package org.openani.mediamp.exoplayer

import android.content.Context
import android.net.Uri
import android.util.Pair
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.annotation.UiThread
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.TrackSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openani.mediamp.AbstractMediampPlayer
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.InternalForInheritanceMediampApi
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.PlaybackState
import org.openani.mediamp.exoplayer.internal.SeekableInputDataSource
import org.openani.mediamp.features.AspectRatioMode
import org.openani.mediamp.features.Buffering
import org.openani.mediamp.features.MediaMetadata
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.features.PlayerFeatures
import org.openani.mediamp.features.VideoAspectRatio
import org.openani.mediamp.features.buildPlayerFeatures
import org.openani.mediamp.internal.MutableTrackGroup
import org.openani.mediamp.io.SeekableInput
import org.openani.mediamp.metadata.AudioTrack
import org.openani.mediamp.metadata.Chapter
import org.openani.mediamp.metadata.MediaProperties
import org.openani.mediamp.metadata.SubtitleTrack
import org.openani.mediamp.metadata.TrackLabel
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.SeekableInputMediaData
import org.openani.mediamp.source.UriMediaData
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds
import androidx.media3.common.Player as Media3Player


/**
 * @see ExoPlayerMediampPlayerFactory
 */
@OptIn(UnstableApi::class)
@kotlin.OptIn(InternalMediampApi::class, InternalForInheritanceMediampApi::class)
class ExoPlayerMediampPlayer @UiThread constructor(
    context: Context,
    parentCoroutineContext: CoroutineContext,
) : AbstractMediampPlayer<ExoPlayerMediampPlayer.ExoPlayerData>(Dispatchers.Default) {
    
    public open class ExoPlayerData(
        mediaData: MediaData,
        val setMedia: () -> Unit, 
    ) : Data(mediaData)

    private val backgroundScope: CoroutineScope = CoroutineScope(
        parentCoroutineContext + SupervisorJob(parentCoroutineContext[Job.Key]),
    ).apply {
        coroutineContext.job.invokeOnCompletion {
            close()
        }
    }
    
    private val trackSelector = object : DefaultTrackSelector(context) {
        override fun selectTextTrack(
            mappedTrackInfo: MappedTrackInfo,
            rendererFormatSupports: Array<out Array<IntArray>>,
            params: Parameters,
            selectedAudioLanguage: String?
        ): Pair<ExoTrackSelection.Definition, Int>? {
            val preferred = mediaMetadataFeature.subtitleTracks.selected.value
                ?: return super.selectTextTrack(
                    mappedTrackInfo,
                    rendererFormatSupports,
                    params,
                    selectedAudioLanguage,
                )

            infix fun SubtitleTrack.matches(group: TrackGroup): Boolean {
                if (this.internalId == group.id) return true

                if (this.labels.isEmpty()) return false
                for (index in 0 until group.length) {
                    val format = group.getFormat(index)
                    if (format.labels.isEmpty()) {
                        continue
                    }
                    if (this.labels.any { it.value == format.labels.first().value }) {
                        return true
                    }
                }
                return false
            }

            // 备注: 这个实现可能并不好, 他只是恰好能跑
            for (rendererIndex in 0 until mappedTrackInfo.rendererCount) {
                if (C.TRACK_TYPE_TEXT != mappedTrackInfo.getRendererType(rendererIndex)) continue

                val groups = mappedTrackInfo.getTrackGroups(rendererIndex)
                for (groupIndex in 0 until groups.length) {
                    val trackGroup = groups[groupIndex]
                    if (preferred matches trackGroup) {
                        return Pair(
                            ExoTrackSelection.Definition(
                                trackGroup,
                                IntArray(trackGroup.length) { it }, // 如果选择所有字幕会闪烁
                                TrackSelection.TYPE_UNSET,
                            ),
                            rendererIndex,
                        )
                    }
                }
            }
            return super.selectTextTrack(
                mappedTrackInfo,
                rendererFormatSupports,
                params,
                selectedAudioLanguage,
            )
        }
    }
    
    private val mediaListener = object : Media3Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            playbackState.value = PlaybackState.READY
            buffering.isBuffering.value = false
        }

        override fun onTracksChanged(tracks: Tracks) {
            val newSubtitleTracks =
                tracks.groups.asSequence()
                    .filter { it.type == C.TRACK_TYPE_TEXT }
                    .flatMapIndexed { groupIndex: Int, group: Tracks.Group ->
                        group.getSubtitleTracks()
                    }
                    .toList()
            // 新的字幕轨道和原来不同时才会更改，同时将 current 设置为新字幕轨道列表的第一个
            if (newSubtitleTracks != mediaMetadataFeature.subtitleTracks.candidates.value) {
                mediaMetadataFeature.subtitleTracks.candidates.value = newSubtitleTracks
                mediaMetadataFeature.subtitleTracks.selected.value = newSubtitleTracks.firstOrNull()
            }

            mediaMetadataFeature.audioTracks.candidates.value =
                tracks.groups.asSequence()
                    .filter { it.type == C.TRACK_TYPE_AUDIO }
                    .flatMapIndexed { groupIndex: Int, group: Tracks.Group ->
                        group.getAudioTracks()
                    }
                    .toList()
        }

        override fun onPlayerError(error: PlaybackException) {
            playbackState.value = PlaybackState.ERROR
            println("ExoPlayer error: ${error.errorCodeName}") // TODO: 2024/12/16 error handling
            error.printStackTrace()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            super.onVideoSizeChanged(videoSize)
            updateVideoProperties()
        }

        @MainThread
        private fun updateVideoProperties(): Boolean {
            val video = exoPlayer.videoFormat ?: return false
            val audio = exoPlayer.audioFormat ?: return false
            val title = exoPlayer.mediaMetadata.title
            val duration = exoPlayer.duration

            // 注意, 要把所有 UI 属性全都读出来然后 captured 到 background -- ExoPlayer 所有属性都需要在主线程
            mediaProperties.value = MediaProperties(
                title = title?.toString(),
                durationMillis = duration,
            )
            return true
        }

        /**
         * STATE_READY 会在当前帧缓冲结束时设置
         *
         * exoplayer 的 STATE_READY 是不符合 [PlaybackState.READY] 预期的，所以不能在这里设置
         *
         * [PlaybackState.READY] 会在 [onMediaItemTransition] 中设置
         */
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Media3Player.STATE_BUFFERING -> {
                    buffering.isBuffering.value = true
                }

                Media3Player.STATE_ENDED -> {
                    this@ExoPlayerMediampPlayer.playbackState.value = PlaybackState.FINISHED
                    buffering.isBuffering.value = false
                }

                Media3Player.STATE_READY -> {
                    this@ExoPlayerMediampPlayer.playbackState.value = PlaybackState.READY
                    buffering.isBuffering.value = false
                }
            }
            updateVideoProperties()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                this@ExoPlayerMediampPlayer.playbackState.value = PlaybackState.PLAYING
                buffering.isBuffering.value = false
            } else {
                this@ExoPlayerMediampPlayer.playbackState.value = PlaybackState.PAUSED
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            super.onPlaybackParametersChanged(playbackParameters)
            playbackSpeed.valueFlow.value = playbackParameters.speed
        }
    }

    private val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        .apply { setTrackSelector(trackSelector) }
        .build()
        .apply {
            playWhenReady = true
            addListener(mediaListener)
        }
    
    override val impl: ExoPlayer get() = exoPlayer

    override val mediaProperties = MutableStateFlow<MediaProperties?>(null)

    private val buffering = ExoPlayerBuffering()
    private val mediaMetadataFeature = ExoPlayerMediaMetadata()
    private val playbackSpeed = PlaybackSpeedImpl(exoPlayer)
    private val videoAspectRatio = ExoPlayerVideoAspectRatio()

    override val currentPositionMillis: MutableStateFlow<Long> = MutableStateFlow(0)

    @kotlin.OptIn(ExperimentalMediampApi::class)
    override val features: PlayerFeatures = buildPlayerFeatures {
        add(PlaybackSpeed, playbackSpeed)
        add(Buffering, buffering)
        add(MediaMetadata, mediaMetadataFeature)
        add(VideoAspectRatio, videoAspectRatio)
    }

    override fun getCurrentMediaProperties(): MediaProperties? {
        return mediaProperties.value
    }

    override fun getCurrentPlaybackState(): PlaybackState {
        return playbackState.value
    }

    init {
        backgroundScope.launch(Dispatchers.Main) {
            while (currentCoroutineContext().isActive) {
                currentPositionMillis.value = exoPlayer.currentPosition
                buffering.bufferedPercentage.value = exoPlayer.bufferedPercentage
                delay(0.1.seconds) // 10 tps
            }
        }
        backgroundScope.launch(Dispatchers.Main) {
            mediaMetadataFeature.subtitleTracks.selected.collect {
                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon().apply {
                    setPreferredTextLanguage(it?.internalId) // dummy value to trigger a select, we have custom selector
                    setTrackTypeDisabled(C.TRACK_TYPE_TEXT, it == null) // disable subtitle track
                }.build()
            }
        }
    }
    
    override suspend fun setMediaDataImpl(data: MediaData): ExoPlayerData = when (data) {
        is UriMediaData -> {
            val headers = data.headers
            val item = MediaItem.Builder().apply {
                setUri(data.uri)
                setSubtitleConfigurations(
                    data.extraFiles.subtitles.map {
                        MediaItem.SubtitleConfiguration.Builder(
                            Uri.parse(it.uri),
                        ).apply {
                            it.label?.let { label -> setLabel(label) }
                            it.mimeType?.let { mimeType -> setMimeType(mimeType) }
                            it.language?.let { language -> setLanguage(language) }
                        }.build()
                    },
                )
            }.build()
            
            ExoPlayerData(
                data,
                setMedia = {
                    exoPlayer.setMediaSource(
                        DefaultMediaSourceFactory(
                            DefaultHttpDataSource.Factory()
                                .setUserAgent(
                                    headers["User-Agent"]
                                        ?: """Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3""",
                                )
                                .setDefaultRequestProperties(headers)
                                .setConnectTimeoutMs(30_000),
                        ).createMediaSource(item),
                    )
                },
            )
        }

        is SeekableInputMediaData -> {
            var file: SeekableInput? = null
            val factory = if (data.uri.startsWith("file://")) {
                DefaultMediaSourceFactory {
                    FileDataSource()
                }
            } else {
                file = withContext(Dispatchers.IO) {
                    data.createInput()
                }
                ProgressiveMediaSource.Factory {
                    SeekableInputDataSource(data, file)
                }
            }
            
            object : ExoPlayerData(data, {
                val mediaItem = MediaItem.fromUri(data.uri)
                exoPlayer.setMediaSource(factory.createMediaSource(mediaItem))
            },) {
                override fun release() {
                    file?.close()
                    super.release()
                }
            }
        }
    }

    override fun resumeImpl() {
        when (playbackState.value) {
            PlaybackState.READY -> {
                val media = openResource.value ?: return // TODO: log
                
                media.setMedia()
                exoPlayer.prepare()
                exoPlayer.play()
            }
            PlaybackState.PAUSED -> {
                exoPlayer.playWhenReady = true
                exoPlayer.play()
            }
            else -> { }
        }
    }

    override fun seekTo(positionMillis: Long) {
        currentPositionMillis.value = positionMillis
        exoPlayer.seekTo(positionMillis)
    }

    override fun getCurrentPositionMillis(): Long = exoPlayer.currentPosition

    override fun pauseImpl() {
        exoPlayer.playWhenReady = false
        exoPlayer.pause()
    }

    override fun stopPlaybackImpl() {
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        currentPositionMillis.value = 0
    }

    override fun closeImpl() {
        exoPlayer.stop()
        exoPlayer.release()
    }

    private fun Tracks.Group.getSubtitleTracks() = sequence {
        repeat(length) { index ->
            val format = getTrackFormat(index)
            val firstLabel = format.labels.firstNotNullOfOrNull { it.value }
            format.metadata
            this.yield(
                SubtitleTrack(
                    "${mediaTrackGroup.id}-$index",
                    mediaTrackGroup.id,
                    firstLabel ?: mediaTrackGroup.id,
                    format.labels.map { TrackLabel(it.language, it.value) },
                ),
            )
        }
    }

    private fun Tracks.Group.getAudioTracks() = sequence {
        repeat(length) { index ->
            val format = getTrackFormat(index)
            val firstLabel = format.labels.firstNotNullOfOrNull { it.value }
            format.metadata
            this.yield(
                AudioTrack(
                    "${mediaTrackGroup.id}-$index",
                    mediaTrackGroup.id,
                    firstLabel ?: mediaTrackGroup.id,
                    format.labels.map { TrackLabel(it.language, it.value) },
                ),
            )
        }
    }
}

@kotlin.OptIn(InternalForInheritanceMediampApi::class)
internal class PlaybackSpeedImpl(
    private val exoPlayer: ExoPlayer,
) : PlaybackSpeed {
    override val valueFlow: MutableStateFlow<Float> = MutableStateFlow(1f)
    override val value: Float get() = valueFlow.value

    override fun set(speed: Float) {
        valueFlow.value = speed.coerceAtLeast(0f)
        exoPlayer.setPlaybackSpeed(speed)
    }
}


@kotlin.OptIn(InternalForInheritanceMediampApi::class)
internal class ExoPlayerMediaMetadata : MediaMetadata {
    override val subtitleTracks: MutableTrackGroup<SubtitleTrack> = MutableTrackGroup()
    override val audioTracks: MutableTrackGroup<AudioTrack> = MutableTrackGroup()

    override val chapters: StateFlow<List<Chapter>> = MutableStateFlow(listOf())
}

@kotlin.OptIn(ExperimentalMediampApi::class, InternalForInheritanceMediampApi::class)
internal class ExoPlayerBuffering : Buffering {
    override val isBuffering: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override val bufferedPercentage = MutableStateFlow(0)
}

@kotlin.OptIn(InternalForInheritanceMediampApi::class)
internal class ExoPlayerVideoAspectRatio : VideoAspectRatio {
    override val mode: MutableStateFlow<AspectRatioMode> = MutableStateFlow(AspectRatioMode.FIT)

    override fun setMode(mode: AspectRatioMode) {
        this.mode.value = mode
    }
}