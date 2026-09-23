package com.nuvio.app.features.updater

data class AppUpdateReleaseSource(
    val owner: String,
    val repo: String,
    val channelBranch: String? = null,
    val includePrereleases: Boolean = false,
    val userAgent: String,
)

data class AppUpdateAssetSelector(
    val fileExtensions: List<String>,
    val contentTypes: List<String> = emptyList(),
    val preferredNameFragments: List<String> = emptyList(),
    val fallbackNameFragments: List<String> = emptyList(),
)

expect object AppUpdaterPlatform {
    val isSupported: Boolean
    val isDebugBuild: Boolean
    val hasSingleUpdateChannel: Boolean

    fun getSupportedAbis(): List<String>

    val releaseSource: AppUpdateReleaseSource

    val assetSelector: AppUpdateAssetSelector

    val currentVersionName: String

    fun getIgnoredTag(): String?

    fun setIgnoredTag(tag: String?)

    fun getUpdateChannel(): String?

    fun setUpdateChannel(channel: String)

    fun deleteDownloadedUpdate(path: String)

    suspend fun downloadUpdateAsset(
        assetUrl: String,
        assetName: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Result<String>

    fun canInstallDownloadedUpdate(): Boolean

    fun openInstallPermissionSettings()

    fun installDownloadedUpdate(path: String): Result<Unit>
}
