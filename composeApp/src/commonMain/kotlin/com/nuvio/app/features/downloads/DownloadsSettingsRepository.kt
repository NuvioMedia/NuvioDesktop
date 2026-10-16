package com.nuvio.app.features.downloads

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DownloadsSettingsUiState(
    val downloadDirectory: String? = null,
    val seedRatio: Double = DownloadsSettingsRepository.DEFAULT_SEED_RATIO,
    val openDownloadsOnStart: Boolean = true,
)

object DownloadsSettingsRepository {
    const val DEFAULT_SEED_RATIO = 1.0

    private val _uiState = MutableStateFlow(DownloadsSettingsUiState())
    val uiState: StateFlow<DownloadsSettingsUiState> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var downloadDirectory: String? = null
    private var seedRatio: Double = DEFAULT_SEED_RATIO
    private var openDownloadsOnStart: Boolean = true

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun clearLocalState() {
        hasLoaded = false
        downloadDirectory = null
        seedRatio = DEFAULT_SEED_RATIO
        openDownloadsOnStart = true
        publish()
    }

    fun setDownloadDirectory(path: String?) {
        ensureLoaded()
        val normalized = path?.trim()?.takeIf { it.isNotBlank() }
        if (downloadDirectory == normalized) return
        downloadDirectory = normalized
        DownloadsSettingsStorage.saveDownloadDirectory(normalized)
        publish()
    }

    fun setSeedRatio(ratio: Double) {
        ensureLoaded()
        val normalized = ratio.coerceAtLeast(0.0)
        if (seedRatio == normalized) return
        seedRatio = normalized
        DownloadsSettingsStorage.saveSeedRatio(normalized)
        publish()
    }

    fun setOpenDownloadsOnStart(enabled: Boolean) {
        ensureLoaded()
        if (openDownloadsOnStart == enabled) return
        openDownloadsOnStart = enabled
        DownloadsSettingsStorage.saveOpenDownloadsOnStart(enabled)
        publish()
    }

    private fun loadFromDisk() {
        hasLoaded = true
        downloadDirectory = DownloadsSettingsStorage.loadDownloadDirectory()
        seedRatio = DownloadsSettingsStorage.loadSeedRatio()
            ?.takeIf { it >= 0.0 }
            ?: DEFAULT_SEED_RATIO
        openDownloadsOnStart = DownloadsSettingsStorage.loadOpenDownloadsOnStart() ?: true
        publish()
    }

    private fun publish() {
        _uiState.value = DownloadsSettingsUiState(
            downloadDirectory = downloadDirectory,
            seedRatio = seedRatio,
            openDownloadsOnStart = openDownloadsOnStart,
        )
    }
}
