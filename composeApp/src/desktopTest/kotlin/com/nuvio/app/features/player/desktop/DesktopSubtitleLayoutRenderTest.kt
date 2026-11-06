package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.SubtitleTrack
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopSubtitleLayoutRenderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun textCuesWrapTogetherWithoutAnOverlappingTransitionFrame() {
        val header = File("src/desktopMain/native/subtitle_layout.h").absoluteFile
        val source = temporary.newFile("subtitle-layout.cpp").apply {
            writeText("""
                #include "${header.invariantSeparatorsPath}"
                extern "C" void subtitle_observe(mpv_handle *mpv, decltype(&mpv_observe_property) observe) {
                    nuvioObserveSubtitleLayout(mpv, observe);
                }
            """.trimIndent())
        }
        val probe = File(temporary.root, System.mapLibraryName("subtitle-layout"))
        val compiler = try {
            ProcessBuilder("c++", "-std=c++17", "-shared", "-fPIC", "-O2", source.path, "-o", probe.path)
                .redirectErrorStream(true).start()
        } catch (error: IOException) {
            assumeNoException("Optional render check requires a C++ compiler", error)
            return
        }
        val compilerOutput = compiler.inputStream.bufferedReader().readText()
        assertTrue(compiler.waitFor(20, TimeUnit.SECONDS), "Native observer compilation timed out")
        assertEquals(0, compiler.exitValue(), compilerOutput)
        val script = temporary.newFile("subtitle-layout-render.py").apply {
            writeText(this@DesktopSubtitleLayoutRenderTest.javaClass.getResourceAsStream("/subtitle-layout-render.py")!!.bufferedReader().use { it.readText() })
        }
        val long = "This is a long primary subtitle that wraps naturally across several lines and must remain below the translated secondary subtitle."
        val cases = listOf(
            fixture("single-line", "PRIMARY SUBTITLE", 640, 360, secondary = "SECONDARY SUBTITLE"),
            fixture("wrapped-text", long, 640, 400),
            fixture("narrow-text", long, 400, 360),
            fixture("explicit-lines", "PRIMARY FIRST LINE\nPRIMARY SECOND LINE", 640, 360),
            fixture("live-two-lines", "PRIMARY FIRST LINE\nPRIMARY SECOND LINE", 640, 360, transition = true),
            fixture("live-wrapped-primary", long, 400, 360, transition = true),
            fixture("wrapped-secondary", "PRIMARY SUBTITLE", 400, 360, secondary = long),
            fixture("letterbox", long, 640, 400, videoHeight = 268),
            fixture("large-text", long, 640, 360, fontSize = 72, scale = 1.3333333),
            fixture("literal-characters", "Use {braces} and C:\\temp\\name.", 640, 360),
        )
        val artifacts = File("build/reports/subtitle-layout").apply { mkdirs() }
        for (case in cases) {
            val name = case.getValue("name").jsonPrimitive.content
            val errorLog = File(artifacts, "$name.log")
            val process = try {
                ProcessBuilder("python3", script.path).redirectError(errorLog).start()
            } catch (error: IOException) {
                assumeNoException("Optional render check requires Python 3", error)
                return
            }
            try {
                val writer = process.outputStream.bufferedWriter()
                val reader = process.inputStream.bufferedReader()
                writer.write(JsonObject(case + mapOf("output" to JsonPrimitive(File(artifacts, name).absolutePath),
                    "probe" to JsonPrimitive(probe.absolutePath))).toString() + "\n")
                writer.flush()
                val properties = mutableMapOf<String, String>()
                val writes = mutableMapOf<String, String>()
                val commands = mutableListOf<List<String>>()
                val slots = DesktopSubtitleSlots(Any(), { 1L }, {
                    listOf(SubtitleTrack(0, "1", "Primary", "en", codec = "subrip"))
                }, readProperty = { _, key -> properties[key].orEmpty() },
                    writeProperty = { _, key, value -> properties[key] = value; writes[key] = value },
                    command = { _, args -> commands += args.toList() })
                var bounds: String? = null
                repeat(if (case.getValue("transition").jsonPrimitive.content == "true") 2 else 1) { phase ->
                    val ready = reader.readLine()
                    if (ready == null) {
                        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Renderer did not stop")
                        assumeTrue(errorLog.readText(), process.exitValue() != 77)
                    }
                    properties.putAll(Json.parseToJsonElement(assertNotNull(ready, errorLog.readText())).jsonObject
                        .mapValues { it.value.jsonPrimitive.content })
                    writes.clear(); commands.clear()
                    if (phase == 0) slots.setEnabled(true) else slots.updatePosition()
                    writer.write(buildJsonObject {
                        put("properties", JsonObject(writes.mapValues { JsonPrimitive(it.value) }))
                        put("commands", JsonArray(commands.map { args -> JsonArray(args.map(::JsonPrimitive)) }))
                    }.toString() + "\n")
                    writer.flush()
                    bounds = reader.readLine()
                    println("$name/$phase: $bounds")
                }
                assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Renderer timed out for $name")
                assertEquals(0, process.exitValue(), "$name: $bounds\n${errorLog.readText()}")
            } finally {
                process.destroyForcibly()
            }
        }
    }

    private fun fixture(name: String, primary: String, width: Int, height: Int,
        videoHeight: Int = 360, fontSize: Int = 42, scale: Double = 0.7777777778,
        transition: Boolean = false,
        secondary: String = "SECONDARY FIRST LINE\nSECONDARY SECOND LINE") = buildJsonObject {
        put("name", name); put("primary", primary); put("secondary", secondary)
        put("width", width); put("height", height); put("videoHeight", videoHeight); put("transition", transition)
        put("properties", buildJsonObject {
            put("sub-pos", "90"); put("sub-font-size", "$fontSize"); put("sub-scale", "$scale")
            put("sub-font", "sans-serif"); put("sub-outline-size", "2")
        })
    }
}
