package com.nuvio.engine.internal

public final class NativeFilePayload(
    @JvmField public final val index: Int,
    @JvmField public final val offset: Long,
    @JvmField public final val size: Long,
    @JvmField public final val pathTruncated: Boolean,
    @JvmField public final val path: String,
)
