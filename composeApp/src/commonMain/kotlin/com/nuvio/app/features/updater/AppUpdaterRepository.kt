package com.nuvio.app.features.updater

import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.updates_github_api_error
import org.jetbrains.compose.resources.getString

@Serializable
internal data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String? = null,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("html_url") val htmlUrl: String? = null,
    val assets: List<GitHubAssetDto> = emptyList(),
)

@Serializable
internal data class GitHubAssetDto(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long? = null,
    @SerialName("content_type") val contentType: String? = null,
)

internal class NoChannelReleaseException : IllegalStateException()

internal data class AppUpdateAssetCandidate(
    val name: String,
    val downloadUrl: String,
    val size: Long? = null,
    val contentType: String? = null,
)

internal fun selectBestUpdateAsset(
    assets: List<AppUpdateAssetCandidate>,
    selector: AppUpdateAssetSelector,
): AppUpdateAssetCandidate? {
    val updateAssets = assets.filter { asset ->
        selector.fileExtensions.any { extension -> asset.name.endsWith(extension, ignoreCase = true) } ||
            selector.contentTypes.any { it.equals(asset.contentType, ignoreCase = true) }
    }
    if (updateAssets.isEmpty()) return null
    if (updateAssets.size == 1) return updateAssets.first()

    for (fragment in selector.preferredNameFragments) {
        updateAssets.firstOrNull { it.name.contains(fragment, ignoreCase = true) }?.let { return it }
    }
    return updateAssets.firstOrNull { asset ->
        selector.fallbackNameFragments.any { asset.name.contains(it, ignoreCase = true) }
    } ?: updateAssets.first()
}

internal object AppUpdaterRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun getLatestChannelUpdate(channel: UpdateChannel): Result<AppUpdate> = runCatching {
        val source = AppUpdaterPlatform.releaseSource
        val response = httpRequestRaw(
            method = "GET",
            url = "https://api.github.com/repos/${source.owner}/${source.repo}/${releasePath(channel)}",
            headers = mapOf(
                "Accept" to "application/vnd.github+json",
                "User-Agent" to source.userAgent,
            ),
            body = "",
        )
        currentCoroutineContext().ensureActive()
        if (response.status == 404) throw NoChannelReleaseException()
        if (response.status !in 200..299) {
            error(getString(Res.string.updates_github_api_error, response.status))
        }
        selectUpdate(
            responseBody = response.body,
            channel = channel,
            supportedAbis = AppUpdaterPlatform.getSupportedAbis(),
            selector = AppUpdaterPlatform.assetSelector,
        )
            ?: throw NoChannelReleaseException()
    }

    internal fun releasePath(channel: UpdateChannel): String = when (channel) {
        UpdateChannel.STABLE -> "releases/latest"
        UpdateChannel.BETA -> "releases?per_page=100"
        UpdateChannel.ALL_RELEASES -> "releases?per_page=20"
    }

    internal fun selectUpdate(
        responseBody: String,
        channel: UpdateChannel,
        supportedAbis: List<String>,
        selector: AppUpdateAssetSelector = AppUpdateAssetSelector(
            fileExtensions = listOf(".apk"),
            contentTypes = listOf("application/vnd.android.package-archive"),
            preferredNameFragments = supportedAbis,
            fallbackNameFragments = listOf("universal", "all"),
        ),
    ): AppUpdate? {
        val releases = when (channel) {
            UpdateChannel.STABLE -> listOf(json.decodeFromString<GitHubReleaseDto>(responseBody))
            UpdateChannel.BETA, UpdateChannel.ALL_RELEASES ->
                json.decodeFromString<List<GitHubReleaseDto>>(responseBody)
        }
        val eligibleReleases = ReleaseSelector.eligibleReleases(releases, channel)
        val releasesToCheck = if (channel == UpdateChannel.ALL_RELEASES) {
            eligibleReleases.take(1)
        } else {
            eligibleReleases
        }
        return releasesToCheck.firstNotNullOfOrNull { release ->
            val asset = selectBestUpdateAsset(
                assets = release.assets.map { candidate ->
                    AppUpdateAssetCandidate(
                        name = candidate.name,
                        downloadUrl = candidate.browserDownloadUrl,
                        size = candidate.size,
                        contentType = candidate.contentType,
                    )
                },
                selector = selector,
            ) ?: return@firstNotNullOfOrNull null
            val tag = if (channel == UpdateChannel.ALL_RELEASES) {
                release.tagName?.takeIf(String::isNotBlank)
                    ?: release.name?.takeIf(String::isNotBlank)
                    ?: return@firstNotNullOfOrNull null
            } else {
                release.tagName?.takeIf { VersionUtils.parse(it) != null }
                    ?: release.name.orEmpty()
            }
            AppUpdate(
                tag = tag,
                title = release.name?.takeIf { it.isNotBlank() } ?: tag,
                notes = release.body.orEmpty(),
                releaseUrl = release.htmlUrl,
                assetName = asset.name,
                assetUrl = asset.downloadUrl,
                assetSizeBytes = asset.size,
            )
        }
    }
}
