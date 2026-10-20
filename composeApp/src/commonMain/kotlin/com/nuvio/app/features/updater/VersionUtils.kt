package com.nuvio.app.features.updater

internal data class SemanticVersion(
    val major: Long,
    val minor: Long,
    val patch: Long,
    val prerelease: List<String>
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }

        if (prerelease.isEmpty() && other.prerelease.isEmpty()) return 0
        if (prerelease.isEmpty()) return 1
        if (other.prerelease.isEmpty()) return -1

        val sharedSize = minOf(prerelease.size, other.prerelease.size)
        for (index in 0 until sharedSize) {
            comparePrereleaseIdentifier(
                prerelease[index],
                other.prerelease[index]
            ).takeIf { it != 0 }?.let { return it }
        }
        return compareValues(prerelease.size, other.prerelease.size)
    }

    private fun comparePrereleaseIdentifier(left: String, right: String): Int {
        val leftNumeric = left.all(Char::isDigit)
        val rightNumeric = right.all(Char::isDigit)

        if (leftNumeric && rightNumeric) {
            val normalizedLeft = left.trimStart('0').ifEmpty { "0" }
            val normalizedRight = right.trimStart('0').ifEmpty { "0" }
            compareValues(normalizedLeft.length, normalizedRight.length)
                .takeIf { it != 0 }
                ?.let { return it }
            return normalizedLeft.compareTo(normalizedRight)
        }
        if (leftNumeric) return -1
        if (rightNumeric) return 1
        return left.compareTo(right)
    }
}

internal object VersionUtils {
    private val versionPattern = Regex(
        "^(\\d+)\\.(\\d+)\\.(\\d+)" +
            "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?" +
            "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$"
    )

    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.trim().removePrefix("v").removePrefix("V")
    }

    fun parse(raw: String?): SemanticVersion? {
        val match = versionPattern.matchEntire(normalize(raw)) ?: return null
        return SemanticVersion(
            major = match.groupValues[1].toLongOrNull() ?: return null,
            minor = match.groupValues[2].toLongOrNull() ?: return null,
            patch = match.groupValues[3].toLongOrNull() ?: return null,
            prerelease = match.groupValues[4]
                .takeIf(String::isNotEmpty)
                ?.split('.')
                .orEmpty()
        )
    }

    fun isPrerelease(raw: String?): Boolean = parse(raw)?.prerelease?.isNotEmpty() == true

    fun isRemoteNewer(remote: String?, local: String?): Boolean {
        val remoteVersion = parse(remote) ?: return false
        val localVersion = parse(local) ?: return false
        return remoteVersion > localVersion
    }

    // Desktop historically compares numeric version components and falls back to
    // treating a changed, non-empty release label as a new release.
    fun isRemoteNewerLegacy(remote: String?, local: String?): Boolean {
        val remoteParts = parseLegacyVersionParts(remote)
        val localParts = parseLegacyVersionParts(local)

        if (remoteParts == null || localParts == null) {
            val remoteValue = normalize(remote)
            val localValue = normalize(local)
            return remoteValue.isNotBlank() && localValue.isNotBlank() && remoteValue != localValue
        }

        val maxSize = maxOf(remoteParts.size, localParts.size)
        for (index in 0 until maxSize) {
            val remoteValue = remoteParts.getOrElse(index) { 0 }
            val localValue = localParts.getOrElse(index) { 0 }
            if (remoteValue != localValue) return remoteValue > localValue
        }
        return false
    }

    private fun parseLegacyVersionParts(raw: String?): List<Int>? {
        val normalized = normalize(raw)
        if (normalized.isBlank()) return null

        val parts = normalized.split('.', '-', '_')
            .filter { it.isNotBlank() }
            .mapNotNull { token -> token.takeWhile(Char::isDigit).toIntOrNull() }
        return parts.takeIf { it.isNotEmpty() }
    }
}
