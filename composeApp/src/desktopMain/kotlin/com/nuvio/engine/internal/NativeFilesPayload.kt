package com.nuvio.engine.internal

public final class NativeFilesPayload(
    @JvmField public final val status: Int,
    @JvmField public final val files: Array<NativeFilePayload>,
)
