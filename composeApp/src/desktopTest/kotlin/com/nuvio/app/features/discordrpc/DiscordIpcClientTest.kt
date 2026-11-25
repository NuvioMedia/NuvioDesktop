package com.nuvio.app.features.discordrpc

import com.nuvio.app.AppScreenTab
import com.nuvio.app.core.ui.PresenceSnapshot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscordIpcClientTest {
    @Test
    fun rejectedEpisodeIsRetriedWithoutNameAndAssetsOnTheSameConnection() = runBlocking {
        val pipe = ScriptedDiscordPipe(setActivityEvents = listOf(null, "ERROR", null))
        var opened = 0
        val client = DiscordIpcClient("test-client") { opened++; pipe }
        assertTrue(client.connect())

        val published = mutableListOf<DiscordActivity>()
        client.publishPresence(
            flowOf(
                PresenceSnapshot.Tab(AppScreenTab.Home),
                PresenceSnapshot.Player(
                    title = "Dune: Prophecy",
                    episodeLabel = "S1E2 - Two Wolves",
                    posterUrl = "https://img.example/dune.jpg",
                    isPlaying = true,
                    positionMs = 600_000L,
                    durationMs = 3_600_000L,
                ),
            ),
        ) { published += it }

        assertEquals(1, opened)
        assertEquals(2, published.size)
        val sent = pipe.setActivityRequests.map { it["args"]!!.jsonObject["activity"]!!.jsonObject }
        assertEquals(3, sent.size)
        assertEquals("Browsing Home", sent[0].text("details"))
        assertEquals("Dune: Prophecy", sent[1].text("name"))
        assertTrue("assets" in sent[1])
        val retry = sent[2]
        assertFalse("name" in retry)
        assertFalse("assets" in retry)
        assertEquals("Dune: Prophecy", retry.text("details"))
        assertEquals("S1, E2: Two Wolves", retry.text("state"))
        val timestamps = retry["timestamps"]!!.jsonObject
        assertEquals(sent[1]["timestamps"], retry["timestamps"])
        assertEquals(3_600L, timestamps.long("end") - timestamps.long("start"))
    }
}

private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.content

private fun JsonObject.long(key: String): Long = this[key]!!.jsonPrimitive.content.toLong()

// Plays Discord's side of the IPC socket: answers the handshake with READY, then each SET_ACTIVITY
// with the next scripted evt (null = applied, "ERROR" = refused) on the request's own nonce.
private class ScriptedDiscordPipe(private val setActivityEvents: List<String?>) : DiscordPipe {
    val setActivityRequests = mutableListOf<JsonObject>()
    private val written = ByteArrayOutputStream()
    private var incoming = ByteBuffer.allocate(0)

    override fun write(bytes: ByteArray) {
        written.write(bytes)
        val buffer = ByteBuffer.wrap(written.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.remaining() < 8) return
        val opcode = buffer.int
        val length = buffer.int
        if (buffer.remaining() < length) return
        val payload = ByteArray(length).also { buffer.get(it) }
        written.reset()
        val request = Json.parseToJsonElement(payload.toString(Charsets.UTF_8)).jsonObject
        val response: JsonObject = if (opcode == 0) {
            buildJsonObject { put("cmd", "DISPATCH"); put("evt", "READY") }
        } else {
            val event = setActivityEvents[setActivityRequests.size]
            setActivityRequests += request
            buildJsonObject {
                put("cmd", "SET_ACTIVITY")
                put("evt", event)
                put("nonce", request["nonce"]!!)
                if (event == "ERROR") put("data", buildJsonObject { put("code", 4000); put("message", "child \"activity\" fails") })
            }
        }
        enqueue(1, response)
    }

    override fun readFully(buffer: ByteArray) {
        if (incoming.remaining() < buffer.size) throw EOFException("no scripted response")
        incoming.get(buffer)
    }

    override fun close() = Unit

    private fun enqueue(opcode: Int, payload: JsonElement) {
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        val frame = ByteBuffer.allocate(8 + bytes.size).order(ByteOrder.LITTLE_ENDIAN)
        frame.putInt(opcode).putInt(bytes.size).put(bytes).flip()
        val remaining = ByteArray(incoming.remaining()).also { incoming.get(it) }
        incoming = ByteBuffer.wrap(remaining + ByteArray(frame.remaining()).also { frame.get(it) })
    }
}
