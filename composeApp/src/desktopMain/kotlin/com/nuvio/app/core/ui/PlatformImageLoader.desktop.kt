package com.nuvio.app.core.ui

import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.CacheStrategy
import coil3.network.NetworkClient
import coil3.network.NetworkFetcher
import coil3.network.NetworkHeaders
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.NetworkResponseBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path

private const val ImageMemoryCacheBytes = 256L * 1024 * 1024
private const val ImageDiskCacheBytes = 512L * 1024 * 1024

internal actual val platformProvidesImageLoader: Boolean = false

private val DesktopImageOkHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .dispatcher(
            Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 16
            },
        )
        .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
}

private object DesktopOkHttpNetworkClient : NetworkClient {
    override suspend fun <T> executeRequest(
        request: NetworkRequest,
        block: suspend (response: NetworkResponse) -> T,
    ): T {
        val okHttpRequest = request.toOkHttpRequest()
        val call = DesktopImageOkHttpClient.newCall(okHttpRequest)
        val response = awaitCall(call)
        return response.use { res ->
            block(res.toNetworkResponse())
        }
    }
}

private suspend fun awaitCall(call: Call): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation {
        runCatching { call.cancel() }
    }
    call.enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) {
                    continuation.resume(response) { _, res, _ ->
                        res.close()
                    }
                } else {
                    response.close()
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled() && continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }
        },
    )
}

private suspend fun NetworkRequest.toOkHttpRequest(): Request {
    val builder = Request.Builder().url(url)
    for ((key, values) in headers.asMap()) {
        for (value in values) {
            builder.addHeader(key, value)
        }
    }
    val okHttpBody = body?.let { requestBody ->
        val buffer = Buffer()
        requestBody.writeTo(buffer)
        buffer.readByteArray().toRequestBody(headers["Content-Type"]?.toMediaTypeOrNull())
    }
    builder.method(method, okHttpBody)
    return builder.build()
}

private fun Response.toNetworkResponse(): NetworkResponse {
    val responseHeaders = NetworkHeaders.Builder().apply {
        for (i in 0 until headers.size) {
            add(headers.name(i), headers.value(i))
        }
    }.build()
    val responseBody = body?.source()?.let(::OkHttpSourceResponseBody)
    return NetworkResponse(
        code = code,
        requestMillis = sentRequestAtMillis,
        responseMillis = receivedResponseAtMillis,
        headers = responseHeaders,
        body = responseBody,
        delegate = this,
    )
}

private class OkHttpSourceResponseBody(
    private val source: BufferedSource,
) : NetworkResponseBody {
    override suspend fun writeTo(sink: BufferedSink) {
        withContext(Dispatchers.IO) {
            source.use { it.readAll(sink) }
        }
    }

    override suspend fun writeTo(fileSystem: FileSystem, path: Path) {
        withContext(Dispatchers.IO) {
            source.use { input ->
                fileSystem.write(path) {
                    writeAll(input)
                }
            }
        }
    }

    override fun close() {
        source.close()
    }
}

internal actual fun ImageLoader.Builder.configurePlatformImageLoader(): ImageLoader.Builder {
    return memoryCache {
        MemoryCache.Builder()
            .maxSizeBytes(ImageMemoryCacheBytes)
            .build()
    }.diskCache {
        DiskCache.Builder()
            .directory(FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "coil3_desktop_image_cache")
            .maxSizeBytes(ImageDiskCacheBytes)
            .build()
    }
}

internal actual fun ComponentRegistry.Builder.addPlatformDecoders() {
    // Prefer HTTP/2 multiplexed OkHttp with direct disk-cache hits over HTTP/1.1 Ktor CIO on desktop.
    add(
        NetworkFetcher.Factory(
            networkClient = { DesktopOkHttpNetworkClient },
            cacheStrategy = { CacheStrategy.DEFAULT },
        ),
    )
    add(SkiaGifDecoder.Factory())
    add(SvgEmbeddedImageDecoder.Factory())
}
