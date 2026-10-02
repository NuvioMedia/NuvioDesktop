package com.nuvio.app.core.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

internal const val DefaultPosterCardWidthDp = 126
internal const val DefaultPosterCardHeightDp = 189
internal const val DefaultPosterCardCornerRadiusDp = 12
internal const val DefaultHoverPreviewOpenDelayMillis = 2_000
internal const val MinHoverPreviewOpenDelayMillis = 500
internal const val MaxHoverPreviewOpenDelayMillis = 5_000
internal const val HoverPreviewOpenDelayStepMillis = 500
internal const val DefaultHoverPreviewTrailerStartSeconds = 0
internal const val MinHoverPreviewTrailerStartSeconds = 0
internal const val MaxHoverPreviewTrailerStartSeconds = 5
internal const val HoverPreviewTrailerStartStepSeconds = 1

@Serializable
private data class StoredPosterCardStylePreferences(
    val widthDp: Int = DefaultPosterCardWidthDp,
    val heightDp: Int = DefaultPosterCardHeightDp,
    val cornerRadiusDp: Int = DefaultPosterCardCornerRadiusDp,
    val catalogLandscapeModeEnabled: Boolean = false,
    val hideLabelsEnabled: Boolean = false,
    val hoverPreviewEnabled: Boolean = true,
    val hoverPreviewOpenDelayMillis: Int = DefaultHoverPreviewOpenDelayMillis,
    val hoverPreviewTrailerEnabled: Boolean = false,
    val hoverPreviewTrailerSoundEnabled: Boolean = false,
    val hoverPreviewTrailerStartSeconds: Int = DefaultHoverPreviewTrailerStartSeconds,
    val alwaysShowLandscapeClearlogo: Boolean = false,
)

data class PosterCardStyleUiState(
    val widthDp: Int = DefaultPosterCardWidthDp,
    val heightDp: Int = DefaultPosterCardHeightDp,
    val cornerRadiusDp: Int = DefaultPosterCardCornerRadiusDp,
    val catalogLandscapeModeEnabled: Boolean = false,
    val hideLabelsEnabled: Boolean = false,
    val hoverPreviewEnabled: Boolean = true,
    val hoverPreviewOpenDelayMillis: Int = DefaultHoverPreviewOpenDelayMillis,
    val hoverPreviewTrailerEnabled: Boolean = false,
    val hoverPreviewTrailerSoundEnabled: Boolean = false,
    val hoverPreviewTrailerStartSeconds: Int = DefaultHoverPreviewTrailerStartSeconds,
    val alwaysShowLandscapeClearlogo: Boolean = false,
)

object PosterCardStyleRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _uiState = MutableStateFlow(PosterCardStyleUiState())
    val uiState: StateFlow<PosterCardStyleUiState> = _uiState.asStateFlow()

    private var hasLoaded = false

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun clearLocalState() {
        hasLoaded = false
        _uiState.value = PosterCardStyleUiState()
    }

    fun setWidthDp(widthDp: Int) {
        ensureLoaded()
        val nextWidth = widthDp
        val nextHeight = (nextWidth * 3) / 2
        if (_uiState.value.widthDp == nextWidth && _uiState.value.heightDp == nextHeight) return
        _uiState.value = _uiState.value.copy(
            widthDp = nextWidth,
            heightDp = nextHeight,
        )
        persist()
    }

    fun setCornerRadiusDp(cornerRadiusDp: Int) {
        ensureLoaded()
        if (_uiState.value.cornerRadiusDp == cornerRadiusDp) return
        _uiState.value = _uiState.value.copy(cornerRadiusDp = cornerRadiusDp)
        persist()
    }

    fun setCatalogLandscapeModeEnabled(enabled: Boolean) {
        ensureLoaded()
        if (_uiState.value.catalogLandscapeModeEnabled == enabled) return
        _uiState.value = _uiState.value.copy(catalogLandscapeModeEnabled = enabled)
        persist()
    }

    fun setHideLabelsEnabled(enabled: Boolean) {
        ensureLoaded()
        if (_uiState.value.hideLabelsEnabled == enabled) return
        _uiState.value = _uiState.value.copy(hideLabelsEnabled = enabled)
        persist()
    }

    fun setHoverPreviewEnabled(enabled: Boolean) {
        ensureLoaded()
        if (_uiState.value.hoverPreviewEnabled == enabled) return
        _uiState.value = _uiState.value.copy(hoverPreviewEnabled = enabled)
        persist()
    }

    fun setHoverPreviewOpenDelayMillis(delayMillis: Int) {
        ensureLoaded()
        val normalizedDelay = normalizeHoverPreviewOpenDelayMillis(delayMillis)
        if (_uiState.value.hoverPreviewOpenDelayMillis == normalizedDelay) return
        _uiState.value = _uiState.value.copy(hoverPreviewOpenDelayMillis = normalizedDelay)
        persist()
    }

    fun setHoverPreviewTrailerEnabled(enabled: Boolean) {
        ensureLoaded()
        if (_uiState.value.hoverPreviewTrailerEnabled == enabled) return
        _uiState.value = _uiState.value.copy(hoverPreviewTrailerEnabled = enabled)
        persist()
    }

    fun setHoverPreviewTrailerSoundEnabled(enabled: Boolean) {
        ensureLoaded()
        if (_uiState.value.hoverPreviewTrailerSoundEnabled == enabled) return
        _uiState.value = _uiState.value.copy(hoverPreviewTrailerSoundEnabled = enabled)
        persist()
    }

    fun setHoverPreviewTrailerStartSeconds(startSeconds: Int) {
        ensureLoaded()
        val normalizedStartSeconds = normalizeHoverPreviewTrailerStartSeconds(startSeconds)
        if (_uiState.value.hoverPreviewTrailerStartSeconds == normalizedStartSeconds) return
        _uiState.value = _uiState.value.copy(
            hoverPreviewTrailerStartSeconds = normalizedStartSeconds,
        )
        persist()
    }

    fun setAlwaysShowLandscapeClearlogo(enabled: Boolean) {
        ensureLoaded()
        if (_uiState.value.alwaysShowLandscapeClearlogo == enabled) return
        _uiState.value = _uiState.value.copy(alwaysShowLandscapeClearlogo = enabled)
        persist()
    }

    fun resetToDefaults() {
        ensureLoaded()
        val defaults = PosterCardStyleUiState(
            hoverPreviewEnabled = _uiState.value.hoverPreviewEnabled,
            hoverPreviewOpenDelayMillis = _uiState.value.hoverPreviewOpenDelayMillis,
            hoverPreviewTrailerEnabled = _uiState.value.hoverPreviewTrailerEnabled,
            hoverPreviewTrailerSoundEnabled = _uiState.value.hoverPreviewTrailerSoundEnabled,
            hoverPreviewTrailerStartSeconds = _uiState.value.hoverPreviewTrailerStartSeconds,
        )
        if (_uiState.value == defaults) return
        _uiState.value = defaults
        persist()
    }

    private fun loadFromDisk() {
        hasLoaded = true

        val payload = PosterCardStyleStorage.loadPayload().orEmpty().trim()
        if (payload.isEmpty()) {
            _uiState.value = PosterCardStyleUiState()
            return
        }

        val stored = runCatching {
            json.decodeFromString<StoredPosterCardStylePreferences>(payload)
        }.getOrNull()

        _uiState.value = if (stored != null) {
            val widthDp = stored.widthDp.takeIf { it > 0 } ?: DefaultPosterCardWidthDp
            val heightDp = stored.heightDp.takeIf { it > 0 } ?: ((widthDp * 3) / 2)
            val cornerRadiusDp = stored.cornerRadiusDp.coerceAtLeast(0)
            PosterCardStyleUiState(
                widthDp = widthDp,
                heightDp = heightDp,
                cornerRadiusDp = cornerRadiusDp,
                catalogLandscapeModeEnabled = stored.catalogLandscapeModeEnabled,
                hideLabelsEnabled = stored.hideLabelsEnabled,
                hoverPreviewEnabled = stored.hoverPreviewEnabled,
                hoverPreviewOpenDelayMillis = normalizeHoverPreviewOpenDelayMillis(
                    stored.hoverPreviewOpenDelayMillis,
                ),
                hoverPreviewTrailerEnabled = stored.hoverPreviewTrailerEnabled,
                hoverPreviewTrailerSoundEnabled = stored.hoverPreviewTrailerSoundEnabled,
                hoverPreviewTrailerStartSeconds = normalizeHoverPreviewTrailerStartSeconds(
                    stored.hoverPreviewTrailerStartSeconds,
                ),
                alwaysShowLandscapeClearlogo = stored.alwaysShowLandscapeClearlogo,
            )
        } else {
            PosterCardStyleUiState()
        }
    }

    private fun persist() {
        PosterCardStyleStorage.savePayload(
            json.encodeToString(
                StoredPosterCardStylePreferences(
                    widthDp = _uiState.value.widthDp,
                    heightDp = _uiState.value.heightDp,
                    cornerRadiusDp = _uiState.value.cornerRadiusDp,
                    catalogLandscapeModeEnabled = _uiState.value.catalogLandscapeModeEnabled,
                    hideLabelsEnabled = _uiState.value.hideLabelsEnabled,
                    hoverPreviewEnabled = _uiState.value.hoverPreviewEnabled,
                    hoverPreviewOpenDelayMillis = _uiState.value.hoverPreviewOpenDelayMillis,
                    hoverPreviewTrailerEnabled = _uiState.value.hoverPreviewTrailerEnabled,
                    hoverPreviewTrailerSoundEnabled = _uiState.value.hoverPreviewTrailerSoundEnabled,
                    hoverPreviewTrailerStartSeconds = _uiState.value.hoverPreviewTrailerStartSeconds,
                    alwaysShowLandscapeClearlogo = _uiState.value.alwaysShowLandscapeClearlogo,
                ),
            ),
        )
    }
}

private fun normalizeHoverPreviewOpenDelayMillis(delayMillis: Int): Int =
    ((delayMillis.coerceIn(
        MinHoverPreviewOpenDelayMillis,
        MaxHoverPreviewOpenDelayMillis,
    ) / HoverPreviewOpenDelayStepMillis.toFloat()).roundToInt() * HoverPreviewOpenDelayStepMillis)
        .coerceIn(MinHoverPreviewOpenDelayMillis, MaxHoverPreviewOpenDelayMillis)

private fun normalizeHoverPreviewTrailerStartSeconds(startSeconds: Int): Int =
    ((startSeconds.coerceIn(
        MinHoverPreviewTrailerStartSeconds,
        MaxHoverPreviewTrailerStartSeconds,
    ) / HoverPreviewTrailerStartStepSeconds.toFloat()).roundToInt() * HoverPreviewTrailerStartStepSeconds)
        .coerceIn(MinHoverPreviewTrailerStartSeconds, MaxHoverPreviewTrailerStartSeconds)
