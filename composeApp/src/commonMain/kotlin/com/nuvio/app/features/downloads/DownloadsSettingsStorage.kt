package com.nuvio.app.features.downloads

internal expect object DownloadsSettingsStorage {
    fun loadDownloadDirectory(): String?
    fun saveDownloadDirectory(path: String?)
    fun loadSeedRatio(): Double?
    fun saveSeedRatio(ratio: Double)
    fun loadOpenDownloadsOnStart(): Boolean?
    fun saveOpenDownloadsOnStart(enabled: Boolean)
}
