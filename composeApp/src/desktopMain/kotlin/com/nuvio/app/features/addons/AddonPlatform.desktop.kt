package com.nuvio.app.features.addons

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.network.DesktopIPv4FirstDns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.network_empty_response_body
import nuvio.composeapp.generated.resources.network_request_failed_http
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.jetbrains.compose.resources.getString
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

internal actual object AddonStorage {
    private val store = DesktopStorage.store("nuvio_addons")
    private val json = Json { ignoreUnknownKeys = true }

    actual fun loadInstalledAddonUrls(profileId: Int): List<String> =
        store.getString("installed_addon_urls_$profileId")
            ?.let { payload -> runCatching { json.decodeFromString<List<String>>(payload) }.getOrNull() }
            ?: emptyList()

    actual fun saveInstalledAddonUrls(profileId: Int, urls: List<String>) {
        store.putString("installed_addon_urls_$profileId", json.encodeToString(urls))
    }

    actual fun loadAddonEnabledStates(profileId: Int): Map<String, Boolean> =
        store.getString("addon_enabled_states_$profileId")
            ?.let { payload -> runCatching { json.decodeFromString<Map<String, Boolean>>(payload) }.getOrNull() }
            ?: emptyMap()

    actual fun saveAddonEnabledStates(profileId: Int, states: Map<String, Boolean>) {
        store.putString("addon_enabled_states_$profileId", json.encodeToString(states))
    }
}

private val desktopHttpClient = OkHttpClient.Builder()
    .dispatcher(okhttp3.Dispatcher().apply {
        maxRequests = 64
        maxRequestsPerHost = 16
    })
    .connectionPool(okhttp3.ConnectionPool(16, 5, TimeUnit.MINUTES))
    .dns(DesktopIPv4FirstDns())
    .connectTimeout(60, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .build()


private const val truncationSuffix = "\n...[truncated]"

actual suspend fun httpGetText(url: String): String =
    executeTextRequest(
        method = "GET",
        url = url,
        headers = mapOf("Accept" to "application/json"),
    )

actual suspend fun httpPostJson(url: String, body: String): String =
    executeTextRequest(
        method = "POST",
        url = url,
        headers = mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
        ),
        body = body,
    )

actual suspend fun httpGetTextWithHeaders(
    url: String,
    headers: Map<String, String>,
): String =
    executeTextRequest(
        method = "GET",
        url = url,
        headers = mapOf("Accept" to "application/json") + headers,
    )

actual suspend fun httpPostJsonWithHeaders(
    url: String,
    body: String,
    headers: Map<String, String>,
): String =
    executeTextRequest(
        method = "POST",
        url = url,
        headers = mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
        ) + headers,
        body = body,
    )

actual suspend fun httpRequestRaw(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: String,
    followRedirects: Boolean,
    maxResponseBodyBytes: Int,
): RawHttpResponse = withContext(Dispatchers.IO) {
    val client = if (followRedirects) {
        desktopHttpClient
    } else {
        desktopHttpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
    val request = buildDesktopRequest(method, url, headers, body)

    client.newCall(request).execute().use { response ->
        RawHttpResponse(
            status = response.code,
            statusText = response.message,
            url = response.request.url.toString(),
            body = readResponseBodyLimited(response.body, maxResponseBodyBytes),
            headers = response.headers.toMultimap().mapValues { (_, values) ->
                values.joinToString(",")
            }.mapKeys { (name, _) ->
                name.lowercase()
            },
        )
    }
}

private suspend fun executeTextRequest(
    method: String,
    url: String,
    headers: Map<String, String> = emptyMap(),
    body: String = "",
): String = withContext(Dispatchers.IO) {
    val request = buildDesktopRequest(method, url, headers, body)
    desktopHttpClient.newCall(request).execute().use { response ->
        val payload = readResponseBody(response.body)
        if (!response.isSuccessful) {
            error(runBlocking { getString(Res.string.network_request_failed_http, response.code) })
        }
        if (payload.isBlank()) {
            throw IllegalStateException(runBlocking { getString(Res.string.network_empty_response_body) })
        }
        payload
    }
}

private fun buildDesktopRequest(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: String,
): Request {
    val normalizedMethod = method.trim().uppercase().ifBlank { "GET" }
    val sanitizedHeaders = headers.withoutAcceptEncoding()
    val builder = Request.Builder().url(url.encodeUnsafeHttpUrlCharacters())
    sanitizedHeaders.forEach { (key, value) ->
        if (key.isNotBlank() && value.isNotBlank()) {
            builder.header(key, value)
        }
    }

    return if (requestAllowsBody(normalizedMethod)) {
        val contentType = sanitizedHeaders.getHeaderIgnoreCase("Content-Type")
            ?: if (normalizedMethod == "POST") "application/x-www-form-urlencoded" else "application/json"
        builder.method(
            normalizedMethod,
            body.toByteArray(Charsets.UTF_8).toRequestBody(contentType.toMediaType()),
        )
    } else {
        builder.method(normalizedMethod, null)
    }.build()
}

private fun requestAllowsBody(method: String): Boolean =
    when (method.uppercase()) {
        "POST", "PUT", "PATCH", "DELETE" -> true
        else -> false
    }

private fun Map<String, String>.withoutAcceptEncoding(): Map<String, String> =
    entries
        .filterNot { (key, _) -> key.equals("Accept-Encoding", ignoreCase = true) }
        .associate { (key, value) -> key to value }

private fun Map<String, String>.getHeaderIgnoreCase(name: String): String? =
    entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value

private data class LimitedReadResult(
    val bytes: ByteArray,
    val truncated: Boolean,
)

private fun readAtMostBytes(stream: InputStream, maxBytes: Int): LimitedReadResult {
    val out = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
    val buffer = ByteArray(8 * 1024)
    var remaining = maxBytes
    var truncated = false

    while (remaining > 0) {
        val read = stream.read(buffer, 0, minOf(buffer.size, remaining))
        if (read <= 0) break
        out.write(buffer, 0, read)
        remaining -= read
    }

    if (remaining == 0) {
        truncated = stream.read() != -1
    }

    return LimitedReadResult(out.toByteArray(), truncated)
}

private fun readResponseBodyLimited(body: ResponseBody?, maxBytes: Int): String {
    if (body == null) return ""
    val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
    val readResult = body.byteStream().use { stream ->
        readAtMostBytes(stream, maxBytes.coerceAtLeast(0))
    }
    val decoded = runCatching {
        String(readResult.bytes, charset)
    }.getOrElse {
        String(readResult.bytes, Charsets.UTF_8)
    }
    return if (readResult.truncated) decoded + truncationSuffix else decoded
}

private fun readResponseBody(body: ResponseBody?): String {
    if (body == null) return ""
    val bytes = body.bytes()
    return runCatching {
        val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        String(bytes, charset)
    }.getOrElse {
        String(bytes, Charsets.UTF_8)
    }
}
