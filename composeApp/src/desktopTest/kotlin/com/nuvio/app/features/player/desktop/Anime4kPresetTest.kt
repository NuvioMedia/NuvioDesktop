package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.Anime4kInstallStatus
import com.nuvio.app.features.player.Anime4kMode
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Anime4kPresetTest {
    @Test
    fun `automatic leaves 4k and 1440p untouched`() {
        assertNull(resolveAnime4kAutoPreset(Anime4kSourceInfo(3840, 2160, 20_000)))
        assertNull(resolveAnime4kAutoPreset(Anime4kSourceInfo(2560, 1440, 12_000)))
    }

    @Test
    fun `automatic picks presets from resolution and bitrate`() {
        assertEquals(Anime4kPreset.B, resolveAnime4kAutoPreset(Anime4kSourceInfo(1920, 1080, 8_000)))
        assertEquals(Anime4kPreset.C, resolveAnime4kAutoPreset(Anime4kSourceInfo(1920, 1080, 1_200)))
        assertEquals(Anime4kPreset.A, resolveAnime4kAutoPreset(Anime4kSourceInfo(1280, 720, 3_000)))
        assertEquals(Anime4kPreset.A, resolveAnime4kAutoPreset(Anime4kSourceInfo(854, 480, 1_500)))
        assertEquals(Anime4kPreset.APlus, resolveAnime4kAutoPreset(Anime4kSourceInfo(640, 360, 2_000)))
    }

    @Test
    fun `automatic falls back to a safe clean-source chain without dimensions`() {
        assertEquals(Anime4kPreset.B, resolveAnime4kAutoPreset(null))
        assertEquals(Anime4kPreset.B, resolveAnime4kAutoPreset(Anime4kSourceInfo(0, 0)))
    }

    @Test
    fun `every menu mode maps to its real shader preset`() {
        assertEquals(Anime4kPreset.A, resolveAnime4kPreset(Anime4kMode.ModeA, null))
        assertEquals(Anime4kPreset.B, resolveAnime4kPreset(Anime4kMode.ModeB, null))
        assertEquals(Anime4kPreset.C, resolveAnime4kPreset(Anime4kMode.ModeC, null))
        assertEquals(Anime4kPreset.APlus, resolveAnime4kPreset(Anime4kMode.ModeAPlus, null))
        assertEquals(Anime4kPreset.BPlus, resolveAnime4kPreset(Anime4kMode.ModeBPlus, null))
        assertEquals(Anime4kPreset.CPlus, resolveAnime4kPreset(Anime4kMode.ModeCPlus, null))
        assertNull(resolveAnime4kPreset(Anime4kMode.Off, null))
    }

    @Test
    fun `all preset files are bundled and owned by the installer`() {
        Anime4kPreset.entries.forEach { preset ->
            preset.files.forEach { name ->
                assertTrue(
                    name in DesktopAnime4kShaders.requiredShaderNames,
                    "$name is used by ${preset.mode} but not installed",
                )
                assertNotNull(DesktopAnime4kShaders.resourceBytes(name), "missing bundled $name.glsl")
            }
        }
    }

    @Test
    fun `verify reports an empty directory as not installed`() {
        val dir = Files.createTempDirectory("nuvio-anime4k-test").toFile()
        try {
            val state = DesktopAnime4kShaders.verify(dir)
            assertEquals(Anime4kInstallStatus.NotInstalled, state.status)
            assertEquals(dir.absolutePath, state.installPath)
        } finally {
            dir.deleteRecursively()
        }
    }
    @Test
    fun `installer writes every bundled shader and builds real chains`() {
        DesktopAnime4kShaders.install(force = true) { }
        val state = DesktopAnime4kShaders.verify(DesktopAnime4kShaders.installDir)
        assertEquals(Anime4kInstallStatus.Installed, state.status, state.message)
        Anime4kPreset.entries.forEach { preset ->
            val chain = DesktopAnime4kShaders.chainFor(preset.mode, null).getOrThrow()
            val paths = chain.split(java.io.File.pathSeparator)
            assertEquals(preset.files.size, paths.size)
            paths.forEach { path ->
                assertTrue(java.io.File(path).isFile, "missing installed shader $path")
            }
        }
    }

}
