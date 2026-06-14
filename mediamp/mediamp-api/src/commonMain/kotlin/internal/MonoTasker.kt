/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext


internal interface MonoTasker {
    val isRunning: StateFlow<Boolean>

    fun launch(
        context: CoroutineContext = EmptyCoroutineContext,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> Unit
    ): Job

    fun <R> async(
        context: CoroutineContext = EmptyCoroutineContext,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> R,
    ): Deferred<R>

    /**
     * 等待上一个任务完成后再执行
     */
    fun launchNext(
        context: CoroutineContext = EmptyCoroutineContext,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> Unit
    )

    fun cancel(cause: CancellationException? = null)

    suspend fun cancelAndJoin()

    suspend fun join()
}

internal fun MonoTasker(
    scope: CoroutineScope
): MonoTasker = object : MonoTasker {
    var job: Job? = null

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    override fun launch(
        context: CoroutineContext,
        start: CoroutineStart,
        block: suspend CoroutineScope.() -> Unit
    ): Job {
        job?.cancel()
        val newJob = scope.launch(context, start, block).apply {
            invokeOnCompletion {
                if (job === this) {
                    _isRunning.value = false
                }
            }
        }.also { job = it }
        _isRunning.value = true

        return newJob
    }

    override fun <R> async(
        context: CoroutineContext,
        start: CoroutineStart,
        block: suspend CoroutineScope.() -> R
    ): Deferred<R> {
        job?.cancel()
        val deferred = scope.async(context, start, block).apply {
            invokeOnCompletion {
                if (job === this) {
                    _isRunning.value = false
                }
            }
        }
        job = deferred
        _isRunning.value = true
        return deferred
    }

    override fun launchNext(
        context: CoroutineContext,
        start: CoroutineStart,
        block: suspend CoroutineScope.() -> Unit
    ) {
        val existingJob = job
        job = scope.launch(context, start) {
            try {
                existingJob?.join()
                block()
            } catch (e: CancellationException) {
                existingJob?.cancel()
                throw e
            }
        }.apply {
            invokeOnCompletion {
                if (job === this) {
                    _isRunning.value = false
                }
            }
        }
        _isRunning.value = true
    }

    override fun cancel(cause: CancellationException?) {
        job?.cancel(cause) // use completion handler to set _isRunning to false
    }

    override suspend fun cancelAndJoin() {
        job?.run {
            join()
        }
    }

    override suspend fun join() {
        job?.join()
    }
}

