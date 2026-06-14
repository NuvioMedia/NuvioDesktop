/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

@file:androidx.annotation.OptIn(UnstableApi::class)

package org.openani.mediamp.exoplayer.internal

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.runBlocking
import org.openani.mediamp.io.SeekableInput
import org.openani.mediamp.source.SeekableInputMediaData
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTimedValue

private const val ENABLE_READ_LOG = false
private const val ENABLE_TRACE_LOG = false


/**
 * Wrap of an Ani [org.openani.mediamp.source.MediaData] into a ExoPlayer [androidx.media3.datasource.DataSource].
 *
 * This class will not close [mediaData].
 */
internal class SeekableInputDataSource(
    private val mediaData: SeekableInputMediaData,
    private val file: SeekableInput,
) : BaseDataSource(true) {
    private var uri: Uri? = null

    private var opened = false

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        // 性能提示: 这个函数会被非常频繁调用 (一个 byte 一次), 速度会直接影响视频首帧延迟

        if (length == 0) return 0

        if (ENABLE_READ_LOG) { // const val, optimized out
            log { "VideoDataDataSource read: offset=$offset, length=$length" }
        }

        val bytesRead = if (ENABLE_READ_LOG) {
            val (value, time) = measureTimedValue {
                file.read(buffer, offset, length)
            }
            if (time > 100.milliseconds) {
                log { "VideoDataDataSource slow read: read $offset for length $length took $time" }
            }
            value
        } else {
            file.read(buffer, offset, length)
        }
        if (bytesRead == -1) {
            return C.RESULT_END_OF_INPUT
        }
        bytesTransferred(bytesRead)
        return bytesRead
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        if (ENABLE_TRACE_LOG) log { "Opening dataSpec, offset=${dataSpec.position}, length=${dataSpec.length}, videoData=$mediaData" }

        val uri = dataSpec.uri
        if (opened && dataSpec.uri == this.uri) {
            if (ENABLE_TRACE_LOG) log { "Double open, will not start download." }
        } else {
            this.uri = uri
            transferInitializing(dataSpec)
            opened = true
        }

        val torrentLength = mediaData.fileLength() ?: 0

        if (ENABLE_TRACE_LOG) log { "torrentLength = $torrentLength" }

        if (dataSpec.position >= torrentLength) {
            if (ENABLE_TRACE_LOG) log { "dataSpec.position ${dataSpec.position} > torrentLength $torrentLength" }
        } else {
            if (dataSpec.position != -1L && dataSpec.position != 0L) {
                if (ENABLE_TRACE_LOG) log { "Seeking to ${dataSpec.position}" }
                runBlocking { file.seekTo(dataSpec.position) }
            }

            if (ENABLE_TRACE_LOG) log { "Open done, bytesRemaining = ${file.bytesRemaining}" }
        }

        transferStarted(dataSpec)
        return file.bytesRemaining
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (ENABLE_TRACE_LOG) log { "Closing VideoDataDataSource" }
        uri = null
        if (opened) {
            transferEnded()
        }
    }

    private inline fun log(message: () -> String) {
        if (ENABLE_TRACE_LOG) println(message())
    }
}