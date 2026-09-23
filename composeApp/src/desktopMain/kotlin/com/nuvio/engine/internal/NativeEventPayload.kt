package com.nuvio.engine.internal

public final class NativeEventPayload(
    @JvmField public final val type: Int,
    @JvmField public final val sequence: Long,
    @JvmField public final val requestId: Long,
    @JvmField public final val droppedEvents: Long,
    @JvmField public final val torrentId: String,
    @JvmField public final val message: String,
    @JvmField public final val fileIndex: Int,
    @JvmField public final val fileSize: Long,
    @JvmField public final val streamId: String,
    @JvmField public final val streamUrl: String,
)
