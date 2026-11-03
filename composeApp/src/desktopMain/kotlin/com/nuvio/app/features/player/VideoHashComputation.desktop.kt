package com.nuvio.app.features.player

import com.nuvio.app.features.addons.desktopHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

private const val ChunkSize = 65536L

private val hashClient = desktopHttpClient.newBuilder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.SECONDS)
    .build()

internal actual suspend fun computeVideoHash(
    url: String,
    headers: Map<String, String>,
): Pair<String, Long>? = withContext(Dispatchers.IO) {
    runCatching {
        val fileSize = contentLength(url, headers) ?: return@runCatching null
        if (fileSize < ChunkSize * 2) return@runCatching null
        val head = chunkSum(url, headers, 0) ?: return@runCatching null
        val tail = chunkSum(url, headers, fileSize - ChunkSize) ?: return@runCatching null
        "%016x".format(fileSize + head + tail) to fileSize
    }.getOrNull()
}

private fun Request.Builder.withHeaders(headers: Map<String, String>): Request.Builder {
    headers.forEach { (name, value) -> header(name, value) }
    return this
}

private fun contentLength(url: String, headers: Map<String, String>): Long? {
    val request = Request.Builder().url(url).head().withHeaders(headers).build()
    hashClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return null
        if (response.header("Accept-Ranges").equals("none", ignoreCase = true)) return null
        return response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }
    }
}

private fun chunkSum(url: String, headers: Map<String, String>, offset: Long): Long? {
    val request = Request.Builder()
        .url(url)
        .withHeaders(headers)
        .header("Range", "bytes=$offset-${offset + ChunkSize - 1}")
        .build()
    hashClient.newCall(request).execute().use { response ->
        if (response.code != 206) return null
        val bytes = response.body?.byteStream()?.readNBytes(ChunkSize.toInt()) ?: return null
        if (bytes.size < ChunkSize) return null
        val longs = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer()
        var sum = 0L
        while (longs.hasRemaining()) sum += longs.get()
        return sum
    }
}
