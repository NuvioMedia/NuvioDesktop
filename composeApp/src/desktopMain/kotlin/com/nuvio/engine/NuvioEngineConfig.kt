package com.nuvio.engine

import java.io.File

public data class NuvioEngineConfig(
    val dataDirectory: File,
    val cacheDirectory: File,
    val memoryCacheCapacityBytes: Long = 64L * 1024L * 1024L,
    val diskCacheCapacityBytes: Long = 2L * 1024L * 1024L * 1024L,
    val torrentProfile: NuvioTorrentProfile = NuvioTorrentProfile.Balanced,
    val listenPort: Int = 0,
    val uploadMode: NuvioUploadMode = NuvioUploadMode.Unlimited,
    val uploadLimitBytesPerSecond: Long = 0L,
    val streamInactivityTimeoutMilliseconds: Int = 0,
    val warmTorrentTimeoutMilliseconds: Int = 60000,
    val tlsCaBundle: File? = null,
) {
    internal fun validate() {
        require(memoryCacheCapacityBytes >= 0L) { "memory cache capacity must be non-negative" }
        require(diskCacheCapacityBytes >= 0L) { "disk cache capacity must be non-negative" }
        require(listenPort in 0..65535) { "listen port must be between 0 and 65535" }
        require(streamInactivityTimeoutMilliseconds >= 0) { "stream inactivity timeout must be non-negative" }
        require(warmTorrentTimeoutMilliseconds >= 0) { "warm torrent timeout must be non-negative" }
        when (uploadMode) {
            NuvioUploadMode.Limited -> require(uploadLimitBytesPerSecond > 0L) {
                "limited upload mode requires a positive byte rate"
            }
            NuvioUploadMode.Disabled, NuvioUploadMode.Unlimited -> require(uploadLimitBytesPerSecond == 0L) {
                "disabled and unlimited upload modes require a zero byte rate"
            }
        }
        tlsCaBundle?.let { validateTlsCaBundle(it) }
    }

    internal fun validateTlsCaBundle(file: File) {
        require(file.isFile) { "TLS CA bundle must be a regular file" }
        val len = file.length()
        require(len in 1L..MAXIMUM_TLS_CA_BUNDLE_BYTES) { "TLS CA bundle must contain between 1 byte and 16 MiB" }
    }

    public companion object {
        public const val MAXIMUM_TLS_CA_BUNDLE_BYTES: Long = 16L * 1024L * 1024L
    }
}
