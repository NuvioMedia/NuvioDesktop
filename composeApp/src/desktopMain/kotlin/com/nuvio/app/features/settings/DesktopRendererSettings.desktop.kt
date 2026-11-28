package com.nuvio.app.features.settings

import com.nuvio.app.core.storage.DesktopStorage
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val OpenGlRenderer = "OPENGL"
private const val RenderApiEnvironmentVariable = "SKIKO_RENDER_API"
private const val RenderApiSystemProperty = "skiko.renderApi"

internal actual object DesktopRendererSettings {
    private const val openGlEnabledKey = "open_gl_enabled"
    private val store by lazy { DesktopStorage.store("nuvio_desktop_renderer") }
    private var appliedStoredPreference = false

    actual val isSupported: Boolean
        get() = supportsWindowsOpenGlRenderer(
            osName = System.getProperty("os.name").orEmpty(),
            osArchitecture = System.getProperty("os.arch").orEmpty(),
        )

    actual val useOpenGl: Boolean
        get() {
            val externalRenderer = externalRendererOverride()
            return if (externalRenderer != null && !appliedStoredPreference) {
                externalRenderer == OpenGlRenderer
            } else {
                loadStoredPreference()
            }
        }

    actual val isExternallyControlled: Boolean
        get() = externalRendererOverride() != null && !appliedStoredPreference

    actual fun setUseOpenGl(enabled: Boolean) {
        store.putBoolean(openGlEnabledKey, enabled)
    }

    private const val steadyFramePacingKey = "steady_frame_pacing_enabled"
    private val steadyFramePacingState by lazy {
        MutableStateFlow(runCatching { store.getBoolean(steadyFramePacingKey) }.getOrNull() ?: true)
    }

    /** Live value for the window's frame pacer, so the switch applies without a restart. */
    val steadyFramePacing: StateFlow<Boolean>
        get() = steadyFramePacingState.asStateFlow()

    actual val steadyFramePacingEnabled: Boolean
        get() = steadyFramePacingState.value

    actual fun setSteadyFramePacingEnabled(enabled: Boolean) {
        store.putBoolean(steadyFramePacingKey, enabled)
        steadyFramePacingState.value = enabled
    }

    fun applyBeforeCompose() {
        val storedOpenGlEnabled = runCatching(::loadStoredPreference).getOrDefault(false)
        if (!shouldApplyStoredOpenGlRenderer(
                osName = System.getProperty("os.name").orEmpty(),
                osArchitecture = System.getProperty("os.arch").orEmpty(),
                environmentRenderer = System.getenv(RenderApiEnvironmentVariable),
                systemPropertyRenderer = System.getProperty(RenderApiSystemProperty),
                storedOpenGlEnabled = storedOpenGlEnabled,
            )
        ) {
            return
        }

        runCatching {
            System.setProperty(RenderApiSystemProperty, OpenGlRenderer)
        }.onSuccess {
            appliedStoredPreference = true
        }
    }

    private fun loadStoredPreference(): Boolean =
        store.getBoolean(openGlEnabledKey) ?: false

    private fun externalRendererOverride(): String? =
        System.getenv(RenderApiEnvironmentVariable) ?: System.getProperty(RenderApiSystemProperty)
}

internal fun applyDesktopRendererPreference() {
    DesktopRendererSettings.applyBeforeCompose()
}

internal fun supportsWindowsOpenGlRenderer(
    osName: String,
    osArchitecture: String,
): Boolean {
    val normalizedOsName = osName.lowercase(Locale.ROOT)
    val normalizedArchitecture = osArchitecture.lowercase(Locale.ROOT)
    val isArm64 = normalizedArchitecture.contains("aarch64") ||
        normalizedArchitecture.contains("arm64")
    return normalizedOsName.startsWith("windows") && !isArm64
}

internal fun shouldApplyStoredOpenGlRenderer(
    osName: String,
    osArchitecture: String,
    environmentRenderer: String?,
    systemPropertyRenderer: String?,
    storedOpenGlEnabled: Boolean,
): Boolean =
    storedOpenGlEnabled &&
        environmentRenderer == null &&
        systemPropertyRenderer == null &&
        supportsWindowsOpenGlRenderer(osName, osArchitecture)
