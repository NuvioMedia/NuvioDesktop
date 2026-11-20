package com.nuvio.app.features.player.desktop

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.player.Anime4kInstallState
import com.nuvio.app.features.player.Anime4kInstallStatus
import com.nuvio.app.features.player.Anime4kMode
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The Anime4K v4.0.1 preset chains. These are the standard bloc97 presets, not a synthetic
 * "mode" switch: every entry expands to the real GLSL files mpv runs in the renderer.
 */
internal enum class Anime4kPreset(
    internal val mode: Anime4kMode,
    internal val files: List<String>,
) {
    A(
        Anime4kMode.ModeA,
        listOf(
            "Anime4K_Clamp_Highlights",
            "Anime4K_Restore_CNN_M",
            "Anime4K_Upscale_CNN_x2_M",
            "Anime4K_AutoDownscalePre_x2",
            "Anime4K_AutoDownscalePre_x4",
            "Anime4K_Upscale_CNN_x2_S",
        ),
    ),
    APlus(
        Anime4kMode.ModeAPlus,
        listOf(
            "Anime4K_Clamp_Highlights",
            "Anime4K_Restore_CNN_VL",
            "Anime4K_Upscale_CNN_x2_VL",
            "Anime4K_AutoDownscalePre_x2",
            "Anime4K_AutoDownscalePre_x4",
            "Anime4K_Upscale_CNN_x2_M",
        ),
    ),
    B(
        Anime4kMode.ModeB,
        listOf(
            "Anime4K_Clamp_Highlights",
            "Anime4K_Restore_CNN_Soft_M",
            "Anime4K_Upscale_CNN_x2_M",
            "Anime4K_AutoDownscalePre_x2",
            "Anime4K_AutoDownscalePre_x4",
            "Anime4K_Upscale_CNN_x2_S",
        ),
    ),
    BPlus(
        Anime4kMode.ModeBPlus,
        listOf(
            "Anime4K_Clamp_Highlights",
            "Anime4K_Restore_CNN_Soft_VL",
            "Anime4K_Upscale_CNN_x2_VL",
            "Anime4K_AutoDownscalePre_x2",
            "Anime4K_AutoDownscalePre_x4",
            "Anime4K_Upscale_CNN_x2_M",
        ),
    ),
    C(
        Anime4kMode.ModeC,
        listOf(
            "Anime4K_Clamp_Highlights",
            "Anime4K_Upscale_Denoise_CNN_x2_M",
            "Anime4K_AutoDownscalePre_x2",
            "Anime4K_AutoDownscalePre_x4",
            "Anime4K_Upscale_CNN_x2_S",
        ),
    ),
    CPlus(
        Anime4kMode.ModeCPlus,
        listOf(
            "Anime4K_Clamp_Highlights",
            "Anime4K_Upscale_Denoise_CNN_x2_VL",
            "Anime4K_AutoDownscalePre_x2",
            "Anime4K_AutoDownscalePre_x4",
            "Anime4K_Upscale_CNN_x2_M",
        ),
    ),
    ;

    companion object {
        fun forMode(mode: Anime4kMode): Anime4kPreset? = entries.firstOrNull { it.mode == mode }
    }
}

internal data class Anime4kSourceInfo(
    val width: Int,
    val height: Int,
    val bitrateKbps: Int? = null,
) {
    val shortSide: Int get() = minOf(width, height).takeIf { it > 0 } ?: 0
    val pixels: Long get() = width.toLong() * height.toLong()
}

/**
 * Automatic is a real decision, not a fixed alias for Mode A:
 *
 * - 4K/1440p sources are left untouched (Anime4K would only waste GPU time on an already-sharp
 *   image, which is exactly the load the menu warns about).
 * - Low-bitrate or very small sources get the denoise-first chains (C / C+A).
 * - Clean 720p/1080p sources get A / B, and very small clean sources get A+A for the extra
 *   restore pass.
 *
 * Unknown dimensions fall back to B, the safest chain for a clean source: it never adds the
 * heavier VL upscaler and never denoises an already noisy stream.
 */
internal fun resolveAnime4kAutoPreset(source: Anime4kSourceInfo?): Anime4kPreset? {
    if (source == null || source.width <= 0 || source.height <= 0) return Anime4kPreset.B
    val shortSide = source.shortSide
    // 2560x1440 and 3840x2160: no meaningful headroom for an x2 CNN chain.
    if (shortSide >= 1300 || source.pixels >= 3_400_000L) return null
    val bitrate = source.bitrateKbps?.takeIf { it > 0 }
    val lowBitrate = bitrate != null && when {
        shortSide >= 900 -> bitrate < 2_500
        shortSide >= 600 -> bitrate < 1_600
        else -> bitrate < 900
    }
    return when {
        shortSide >= 900 -> if (lowBitrate) Anime4kPreset.C else Anime4kPreset.B
        shortSide >= 600 -> if (lowBitrate) Anime4kPreset.CPlus else Anime4kPreset.A
        shortSide >= 400 -> if (lowBitrate) Anime4kPreset.C else Anime4kPreset.A
        else -> if (lowBitrate) Anime4kPreset.CPlus else Anime4kPreset.APlus
    }
}

internal fun resolveAnime4kPreset(mode: Anime4kMode, source: Anime4kSourceInfo?): Anime4kPreset? = when (mode) {
    Anime4kMode.Auto -> resolveAnime4kAutoPreset(source)
    Anime4kMode.Off -> null
    else -> Anime4kPreset.forMode(mode)
}

internal object DesktopAnime4kShaders {
    const val VERSION = "Anime4K v4.0.1"

    /**
     * Every file the installer owns. Kept explicit (rather than listing the resource directory)
     * so a packaged JAR can verify the set without depending on directory enumeration.
     */
    val requiredShaderNames: List<String> = listOf(
        "Anime4K_AutoDownscalePre_x2",
        "Anime4K_AutoDownscalePre_x4",
        "Anime4K_Clamp_Highlights",
        "Anime4K_Restore_CNN_M",
        "Anime4K_Restore_CNN_S",
        "Anime4K_Restore_CNN_Soft_M",
        "Anime4K_Restore_CNN_Soft_VL",
        "Anime4K_Restore_CNN_VL",
        "Anime4K_Thin_Fast",
        "Anime4K_Thin_HQ",
        "Anime4K_Thin_VeryFast",
        "Anime4K_Upscale_CNN_x2_M",
        "Anime4K_Upscale_CNN_x2_S",
        "Anime4K_Upscale_CNN_x2_VL",
        "Anime4K_Upscale_Denoise_CNN_x2_M",
        "Anime4K_Upscale_Denoise_CNN_x2_VL",
    )

    private const val manifestName = "anime4k-shaders.properties"
    private const val resourceRoot = "/player-shaders/"

    private val mutableInstallDir = MutableStateFlow(resolveInstallDir())

    /** The directory the current installation uses; D: is preferred on Windows when writable. */
    internal val installDir: File
        get() = mutableInstallDir.value

    internal fun refreshInstallDir(): File {
        val resolved = resolveInstallDir()
        mutableInstallDir.value = resolved
        return resolved
    }

    internal fun shaderPath(name: String): File = File(installDir, "$name.glsl")

    /** Builds the `glsl-shaders` value, or a clear failure when the install is incomplete. */
    internal fun chainFor(mode: Anime4kMode, source: Anime4kSourceInfo?): Result<String> {
        val preset = resolveAnime4kPreset(mode, source) ?: return Result.success("")
        val missing = preset.files.map(::shaderPath).filterNot { it.isFile }
        if (missing.isNotEmpty()) {
            return Result.failure(
                IllegalStateException("missing ${missing.joinToString { it.name }}"),
            )
        }
        return Result.success(preset.files.joinToString(File.pathSeparator) { shaderPath(it).absolutePath })
    }

    internal fun resolvedMode(mode: Anime4kMode, source: Anime4kSourceInfo?): Anime4kMode? =
        resolveAnime4kPreset(mode, source)?.mode

    internal fun resourceBytes(name: String): ByteArray? =
        DesktopAnime4kShaders::class.java.getResourceAsStream("$resourceRoot$name.glsl")?.use { it.readBytes() }

    internal fun manifestFile(dir: File = installDir): File = File(dir, manifestName)

    private fun resolveInstallDir(): File {
        System.getenv("NUVIO_ANIME4K_DIR")?.takeIf { it.isNotBlank() }?.let { return File(it) }
        // Keep the (small) shader cache off the system drive when a data drive is available. D: is
        // the conventional secondary drive on the machines this desktop build targets.
        if (DesktopHostOs.current == DesktopHostOs.WINDOWS) {
            val dataRoot = File("D:/Nuvio")
            if (dataRoot.isDirectory || (File("D:/").isDirectory && dataRoot.mkdirs())) {
                return File(dataRoot, "anime4k-shaders")
            }
        }
        return DesktopStorage.rootDir.resolve("anime4k-shaders").toFile()
    }

    internal fun verify(dir: File = installDir): Anime4kInstallState {
        val manifest = manifestFile(dir)
        if (!manifest.isFile) {
            val present = requiredShaderNames.count { File(dir, "$it.glsl").isFile }
            return Anime4kInstallState(
                status = if (present == 0) Anime4kInstallStatus.NotInstalled else Anime4kInstallStatus.Invalid,
                installPath = dir.absolutePath,
                version = VERSION,
                message = if (present == 0) "Anime4K shaders are not installed." else "Shader manifest is missing.",
            )
        }
        val properties = runCatching { Properties().apply { manifest.inputStream().use { load(it) } } }
            .getOrElse {
                return Anime4kInstallState(
                    status = Anime4kInstallStatus.Invalid,
                    installPath = dir.absolutePath,
                    version = VERSION,
                    error = it.message,
                    message = "Shader manifest is unreadable.",
                )
            }
        val version = properties.getProperty("version").orEmpty()
        val problems = buildList {
            if (version != VERSION) add("version $version (expected $VERSION)")
            requiredShaderNames.forEach { name ->
                val file = File(dir, "$name.glsl")
                if (!file.isFile) {
                    add("$name missing")
                } else {
                    val expected = properties.getProperty("sha256.$name")
                    if (expected.isNullOrBlank()) {
                        add("$name has no checksum")
                    } else {
                        val actual = runCatching { sha256(file) }.getOrNull()
                        when {
                            actual == null -> add("$name is unreadable")
                            actual != expected -> add("$name is damaged")
                        }
                    }
                }
            }
        }
        return if (problems.isEmpty()) {
            Anime4kInstallState(
                status = Anime4kInstallStatus.Installed,
                progress = 1f,
                installPath = dir.absolutePath,
                version = VERSION,
                message = "Anime4K shaders are installed.",
            )
        } else {
            Anime4kInstallState(
                status = Anime4kInstallStatus.Invalid,
                installPath = dir.absolutePath,
                version = VERSION,
                message = problems.take(3).joinToString("; "),
            )
        }
    }

    internal fun install(force: Boolean, onState: (Anime4kInstallState) -> Unit) {
        val dir = refreshInstallDir()
        val existing = verify(dir)
        if (!force && existing.status == Anime4kInstallStatus.Installed) {
            onState(existing)
            return
        }
        dir.mkdirs()
        if (!dir.isDirectory) {
            onState(
                Anime4kInstallState(
                    status = Anime4kInstallStatus.Error,
                    installPath = dir.absolutePath,
                    version = VERSION,
                    error = "Cannot create ${dir.absolutePath}",
                ),
            )
            return
        }
        val total = requiredShaderNames.size + 1
        var completed = 0
        fun progress(message: String) {
            onState(
                Anime4kInstallState(
                    status = Anime4kInstallStatus.Installing,
                    progress = completed.toFloat() / total,
                    installPath = dir.absolutePath,
                    version = VERSION,
                    message = message,
                ),
            )
        }
        progress("Preparing shaders…")
        val hashes = linkedMapOf<String, String>()
        requiredShaderNames.forEach { name ->
            val bytes = resourceBytes(name)
                ?: return onState(
                    Anime4kInstallState(
                        status = Anime4kInstallStatus.Error,
                        progress = completed.toFloat() / total,
                        installPath = dir.absolutePath,
                        version = VERSION,
                        error = "Bundled shader $name.glsl is missing.",
                    ),
                )
            val target = File(dir, "$name.glsl")
            val temp = File(dir, "$name.glsl.tmp")
            temp.writeBytes(bytes)
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            hashes[name] = sha256(bytes)
            completed++
            progress("Installing $name.glsl…")
        }
        val manifest = manifestFile(dir)
        val properties = Properties().apply {
            setProperty("version", VERSION)
            hashes.forEach { (name, hash) -> setProperty("sha256.$name", hash) }
        }
        val tempManifest = File(dir, "$manifestName.tmp")
        tempManifest.outputStream().use { properties.store(it, "Nuvio Anime4K shader manifest") }
        Files.move(tempManifest.toPath(), manifest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        completed++
        onState(
            progressState(
                dir = dir,
                status = Anime4kInstallStatus.Installing,
                progress = completed.toFloat() / total,
                message = "Verifying shaders…",
            ),
        )
        onState(verify(dir).copy(progress = 1f, message = "Anime4K shaders installed successfully."))
    }

    private fun progressState(
        dir: File,
        status: Anime4kInstallStatus,
        progress: Float,
        message: String,
    ) = Anime4kInstallState(
        status = status,
        progress = progress,
        installPath = dir.absolutePath,
        version = VERSION,
        message = message,
    )

    internal fun sha256(file: File): String = sha256(file.readBytes())

    internal fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

