package com.nuvio.app.features.updater

import com.nuvio.app.core.build.AppVersionConfig
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.updates_not_available
import org.jetbrains.compose.resources.getString

actual object AppUpdaterPlatform {
    actual val isSupported: Boolean = false

    actual val releaseSource: AppUpdateReleaseSource = AppUpdateReleaseSource(
        owner = "NuvioMedia",
        repo = "NuvioMobile",
        channelBranch = "cmp-rewrite",
        userAgent = "NuvioMobile",
    )

    actual val assetSelector: AppUpdateAssetSelector = AppUpdateAssetSelector(
        fileExtensions = emptyList(),
    )

    actual val currentVersionName: String = AppVersionConfig.VERSION_NAME

    actual fun getIgnoredTag(): String? = null

    actual fun setIgnoredTag(tag: String?) = Unit

    actual suspend fun downloadUpdateAsset(
        assetUrl: String,
        assetName: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Result<String> = Result.failure(IllegalStateException(getString(Res.string.updates_not_available)))

    actual fun canInstallDownloadedUpdate(): Boolean = false

    actual fun openInstallPermissionSettings() = Unit

    actual fun installDownloadedUpdate(path: String): Result<Unit> =
        Result.failure(IllegalStateException(runBlocking { getString(Res.string.updates_not_available) }))
}
