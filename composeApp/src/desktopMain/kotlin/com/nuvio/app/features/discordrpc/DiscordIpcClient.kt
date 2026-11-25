package com.nuvio.app.features.discordrpc

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import java.io.EOFException
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.UUID

private const val OpcodeHandshake = 0
private const val OpcodeFrame = 1

private val discordIpcJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = true
}

@Serializable
private data class HandshakePayload(
    val v: Int,
    @SerialName("client_id") val clientId: String,
)

@Serializable
private data class SetActivityArgs(
    val pid: Int,
    val activity: DiscordActivity?,
)

@Serializable
private data class SetActivityCommand(
    val cmd: String,
    val nonce: String,
    val args: SetActivityArgs,
)

internal interface DiscordPipe : Closeable {
    fun write(bytes: ByteArray)
    fun readFully(buffer: ByteArray)
}

private class WindowsNamedPipe(private val file: RandomAccessFile) : DiscordPipe {
    override fun write(bytes: ByteArray) = file.write(bytes)
    override fun readFully(buffer: ByteArray) = file.readFully(buffer)
    override fun close() = file.close()
}

private class UnixSocketPipe(private val channel: SocketChannel) : DiscordPipe {
    override fun write(bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    override fun readFully(buffer: ByteArray) {
        val target = ByteBuffer.wrap(buffer)
        while (target.hasRemaining()) {
            if (channel.read(target) < 0) throw EOFException("discord ipc socket closed")
        }
    }

    override fun close() = channel.close()
}

private fun openWindowsPipe(): DiscordPipe? {
    for (i in 0..9) {
        val file = runCatching { RandomAccessFile("\\\\.\\pipe\\discord-ipc-$i", "rw") }.getOrNull()
        if (file != null) return WindowsNamedPipe(file)
    }
    return null
}

private fun openUnixSocket(): DiscordPipe? {
    val candidateDirs = listOfNotNull(
        System.getenv("XDG_RUNTIME_DIR"),
        System.getenv("TMPDIR"),
        "/tmp",
        "/var/run",
    )
    for (dir in candidateDirs) {
        for (i in 0..9) {
            val path = Path.of(dir, "discord-ipc-$i")
            if (!Files.exists(path)) continue
            val channel = runCatching {
                SocketChannel.open(StandardProtocolFamily.UNIX).apply {
                    connect(UnixDomainSocketAddress.of(path))
                }
            }.getOrNull()
            if (channel != null) return UnixSocketPipe(channel)
        }
    }
    return null
}

private fun openTransport(): DiscordPipe? {
    val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
    return if (osName.contains("win")) openWindowsPipe() else openUnixSocket()
}

private fun writeFrame(pipe: DiscordPipe, opcode: Int, payload: String) {
    val payloadBytes = payload.toByteArray(Charsets.UTF_8)
    val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
    header.putInt(opcode)
    header.putInt(payloadBytes.size)
    pipe.write(header.array())
    pipe.write(payloadBytes)
}

private fun readFrame(pipe: DiscordPipe): Pair<Int, String> {
    val headerBytes = ByteArray(8)
    pipe.readFully(headerBytes)
    val header = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
    val opcode = header.int
    val length = header.int
    require(length in 0..1_048_576) { "unreasonable discord ipc frame length: $length" }
    val payloadBytes = ByteArray(length)
    if (length > 0) pipe.readFully(payloadBytes)
    return opcode to payloadBytes.toString(Charsets.UTF_8)
}

internal enum class SetActivityResult { Applied, Rejected, Disconnected }

internal class DiscordIpcClient(
    private val clientId: String,
    private val transportFactory: () -> DiscordPipe? = ::openTransport,
) {
    private val log = Logger.withTag("DiscordIpcClient")
    private val pid = ProcessHandle.current().pid().toInt()
    private var pipe: DiscordPipe? = null

    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        if (pipe != null) return@withContext true
        val opened = transportFactory() ?: return@withContext false
        try {
            writeFrame(opened, OpcodeHandshake, discordIpcJson.encodeToString(HandshakePayload(v = 1, clientId = clientId)))
            val (opcode, payload) = readFrame(opened)
            val evt = discordIpcJson.parseToJsonElement(payload).jsonObject["evt"]?.jsonPrimitive?.contentOrNull
            check(opcode == OpcodeFrame && evt == "READY") { "unexpected handshake response: opcode=$opcode evt=$evt" }
            pipe = opened
            true
        } catch (e: CancellationException) {
            runCatching { opened.close() }
            throw e
        } catch (e: Exception) {
            log.d { "handshake failed: ${e.message}" }
            runCatching { opened.close() }
            false
        }
    }

    suspend fun setActivity(activity: DiscordActivity?): SetActivityResult = withContext(Dispatchers.IO) {
        val currentPipe = pipe ?: return@withContext SetActivityResult.Disconnected
        try {
            val command = SetActivityCommand(
                cmd = "SET_ACTIVITY",
                nonce = UUID.randomUUID().toString(),
                args = SetActivityArgs(pid = pid, activity = activity),
            )
            val encodedCommand = discordIpcJson.encodeToString(command)
            writeFrame(currentPipe, OpcodeFrame, encodedCommand)
            val (responseOpcode, responsePayload) = readFrame(currentPipe)
            val response = discordIpcJson.parseToJsonElement(responsePayload).jsonObject
            val responseCommand = response["cmd"]?.jsonPrimitive?.contentOrNull
            val responseEvent = response["evt"]?.jsonPrimitive?.contentOrNull
            val responseNonce = response["nonce"]?.jsonPrimitive?.contentOrNull
            check(responseOpcode == OpcodeFrame) { "unexpected Discord response opcode=$responseOpcode" }
            check(responseCommand == "SET_ACTIVITY" && responseNonce == command.nonce) {
                "unexpected Discord response cmd=$responseCommand evt=$responseEvent nonce=$responseNonce"
            }
            // Discord answers an activity it refuses (a field too long, an unaccepted key, ...) with
            // evt=ERROR on the same nonce and keeps showing the previous activity, so this is not a success.
            if (responseEvent == "ERROR") {
                val message = response["data"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                log.w { "Discord rejected the activity: $message" }
                return@withContext SetActivityResult.Rejected
            }
            SetActivityResult.Applied
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.d { "setActivity failed: ${e.message}" }
            disconnect()
            SetActivityResult.Disconnected
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        runCatching { pipe?.close() }
        pipe = null
    }
}
