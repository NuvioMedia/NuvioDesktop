package com.nuvio.engine.internal

public object NativeBridge {
    init {
        NuvioEngineLibrary.load()
    }

    public external fun nativeCreate(
        dataDirectory: String,
        cacheDirectory: String,
        memoryCacheCapacityBytes: Long,
        diskCacheCapacityBytes: Long,
        torrentProfile: Int,
        listenPort: Int,
        uploadMode: Int,
        uploadLimitBytesPerSecond: Long,
        streamInactivityTimeoutMilliseconds: Int,
        warmTorrentTimeoutMilliseconds: Int,
        tlsCaBundlePath: String,
    ): LongArray

    public external fun nativeDestroy(handle: Long)

    public external fun nativeAddMagnet(handle: Long, magnetUri: String): LongArray

    public external fun nativeAddTorrentData(handle: Long, torrentData: ByteArray): LongArray

    public external fun nativePollEvent(handle: Long): NativeEventPayload?

    public external fun nativeGetFiles(handle: Long, torrentId: String): NativeFilesPayload

    public external fun nativePrepareStream(
        handle: Long,
        torrentId: String,
        fileIndex: Int,
        filenameHint: String?,
    ): LongArray

    public external fun nativeStopStream(handle: Long, streamId: String): LongArray

    public external fun nativeRemoveTorrent(handle: Long, torrentId: String): LongArray

    public external fun nativeGetStats(handle: Long): LongArray

    public external fun nativeGetStreamStats(handle: Long, streamId: String): LongArray

    public external fun nativeReclaimDiskCache(handle: Long, targetBytes: Long): LongArray

    public external fun nativeStatusMessage(status: Int): String

    public external fun nativeEngineVersion(): String

    public external fun nativeBackendVersion(): String
}
