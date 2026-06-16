package com.nuvio.app.features.updater

import com.nuvio.app.core.build.AppVersionConfig

actual object AppUpdaterPlatform {
    actual val isSupported: Boolean = true

    actual val releaseSource: AppUpdateReleaseSource = AppUpdateReleaseSource(
        owner = "NuvioMedia",
        repo = "NuvioMobile",
        channelBranch = "cmp-rewrite",
        userAgent = "NuvioMobile",
    )

    actual val assetSelector: AppUpdateAssetSelector
        get() = AppUpdateAssetSelector(
            fileExtensions = listOf(".apk"),
            contentTypes = listOf("application/vnd.android.package-archive"),
            preferredNameFragments = AndroidAppUpdaterPlatform.getSupportedAbis(),
            fallbackNameFragments = listOf("universal", "all"),
        )

    actual val currentVersionName: String = AppVersionConfig.VERSION_NAME

    actual fun getIgnoredTag(): String? = AndroidAppUpdaterPlatform.getIgnoredTag()

    actual fun setIgnoredTag(tag: String?) {
        AndroidAppUpdaterPlatform.setIgnoredTag(tag)
    }

    actual suspend fun downloadUpdateAsset(
        assetUrl: String,
        assetName: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Result<String> = AndroidAppUpdaterPlatform.downloadUpdateAsset(assetUrl, assetName, onProgress)

    actual fun canInstallDownloadedUpdate(): Boolean = AndroidAppUpdaterPlatform.canRequestPackageInstalls()

    actual fun openInstallPermissionSettings() {
        AndroidAppUpdaterPlatform.openUnknownSourcesSettings()
    }

    actual fun installDownloadedUpdate(path: String): Result<Unit> = AndroidAppUpdaterPlatform.installDownloadedUpdate(path)
}
