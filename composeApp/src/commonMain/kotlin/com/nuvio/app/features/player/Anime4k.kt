package com.nuvio.app.features.player

import kotlinx.coroutines.flow.StateFlow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.anime4k_mode_a
import nuvio.composeapp.generated.resources.anime4k_mode_a_plus
import nuvio.composeapp.generated.resources.anime4k_mode_auto
import nuvio.composeapp.generated.resources.anime4k_mode_b
import nuvio.composeapp.generated.resources.anime4k_mode_b_plus
import nuvio.composeapp.generated.resources.anime4k_mode_c
import nuvio.composeapp.generated.resources.anime4k_mode_c_plus
import nuvio.composeapp.generated.resources.anime4k_mode_off
import org.jetbrains.compose.resources.StringResource

/**
 * The Anime4K presets exposed by the player menu. Each non-[Off] preset maps to a real
 * Anime4K v4.0.1 GLSL chain in the desktop player; [Auto] picks one of them from the source.
 */
enum class Anime4kMode(
    val labelRes: StringResource,
) {
    Auto(Res.string.anime4k_mode_auto),
    Off(Res.string.anime4k_mode_off),
    ModeA(Res.string.anime4k_mode_a),
    ModeB(Res.string.anime4k_mode_b),
    ModeC(Res.string.anime4k_mode_c),
    ModeAPlus(Res.string.anime4k_mode_a_plus),
    ModeBPlus(Res.string.anime4k_mode_b_plus),
    ModeCPlus(Res.string.anime4k_mode_c_plus),
    ;

    val hasShaders: Boolean
        get() = this != Off

    val isAuto: Boolean
        get() = this == Auto

    companion object {
        val default = Auto

        fun fromStorage(value: String?): Anime4kMode =
            entries.firstOrNull { it.name == value } ?: default
    }
}

enum class Anime4kInstallStatus {
    /** The running platform has no mpv/GLSL renderer that this build can drive. */
    Unavailable,

    /** No complete, verified shader set exists in the install directory. */
    NotInstalled,

    /** The shader set is installed and matches the bundled version. */
    Installed,

    /** A partial or damaged shader set was found. */
    Invalid,

    /** Shader files are being copied/extracted. */
    Installing,

    /** The last install attempt failed. */
    Error,
}

data class Anime4kInstallState(
    val status: Anime4kInstallStatus = Anime4kInstallStatus.NotInstalled,
    val progress: Float = 0f,
    val message: String = "",
    val error: String? = null,
    val installPath: String = "",
    val version: String = "",
) {
    val isInstalling: Boolean
        get() = status == Anime4kInstallStatus.Installing

    val isInstalled: Boolean
        get() = status == Anime4kInstallStatus.Installed
}

/** Last shader decision reported by the desktop renderer, for in-player feedback. */
data class Anime4kRuntimeState(
    val activeMode: Anime4kMode? = null,
    val resolvedMode: Anime4kMode? = null,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val error: String? = null,
)

/**
 * Installs and verifies the bundled Anime4K shader set. The desktop implementation copies the
 * GLSL files from the application resources to a persistent folder, so changing videos or
 * restarting Nuvio never downloads or recompiles the set again.
 */
expect object Anime4kShaderInstaller {
    val isSupported: Boolean
    val state: StateFlow<Anime4kInstallState>

    /** Re-reads the install directory and publishes [state]. */
    fun refresh()

    /** Installs (or repairs/reinstalls) the shader set. */
    fun install(force: Boolean = false)
}

/** Shared, process-wide feedback from the native player's shader application. */
object Anime4kRuntime {
    private val mutableState = kotlinx.coroutines.flow.MutableStateFlow(Anime4kRuntimeState())
    val state: StateFlow<Anime4kRuntimeState> = mutableState

    internal fun report(
        activeMode: Anime4kMode?,
        resolvedMode: Anime4kMode? = null,
        width: Int = 0,
        height: Int = 0,
        error: String? = null,
    ) {
        mutableState.value = Anime4kRuntimeState(
            activeMode = activeMode,
            resolvedMode = resolvedMode,
            sourceWidth = width,
            sourceHeight = height,
            error = error,
        )
    }
}
