package io.github.kdroidfilter.composemediaplayer.linux

internal data class LinuxFrameTargets<T : Any, B : Any>(
    val firstTarget: T,
    val secondTarget: T,
    val firstBuffer: B,
    val secondBuffer: B,
)

internal fun <T : Any, B : Any> transferFrameTargetOwnership(
    oldFirst: T?,
    oldSecond: T?,
    replacement: LinuxFrameTargets<T, B>,
    close: (T) -> Unit,
): LinuxFrameTargets<T, B> {
    var failure: Throwable? = null

    fun closeRecording(target: T?) {
        if (target == null) return
        try {
            close(target)
        } catch (closeFailure: Throwable) {
            val primary = failure
            if (primary == null) {
                failure = closeFailure
            } else if (closeFailure !== primary) {
                primary.addSuppressed(closeFailure)
            }
        }
    }

    closeRecording(oldFirst)
    closeRecording(oldSecond)
    if (failure != null) {
        closeRecording(replacement.firstTarget)
        closeRecording(replacement.secondTarget)
        throw checkNotNull(failure)
    }
    return replacement
}

internal fun <T : Any, P : Any, B : Any> allocateOwnedFrameTargets(
    create: () -> T,
    close: (T) -> Unit,
    peek: (T) -> P?,
    address: (P) -> Long,
    size: (P) -> Long?,
    wrap: (Long, Long) -> B?,
): LinuxFrameTargets<T, B>? {
    var first: T? = null
    var second: T? = null

    fun release(primary: Throwable? = null) {
        val owned = listOfNotNull(second, first)
        first = null
        second = null
        var releaseFailure = primary
        owned.forEach { target ->
            try {
                close(target)
            } catch (closeFailure: Throwable) {
                if (releaseFailure == null) {
                    releaseFailure = closeFailure
                } else {
                    val current = releaseFailure
                    if (closeFailure !== current) current.addSuppressed(closeFailure)
                }
            }
        }
        if (primary == null) releaseFailure?.let { throw it }
    }

    try {
        first = create()
        second = create()
        val ownedFirst = checkNotNull(first)
        val ownedSecond = checkNotNull(second)
        val firstPixmap = peek(ownedFirst)
        val secondPixmap = peek(ownedSecond)
        if (firstPixmap == null || secondPixmap == null) {
            release()
            return null
        }
        val firstAddress = address(firstPixmap)
        val secondAddress = address(secondPixmap)
        if (firstAddress == 0L || secondAddress == 0L) {
            release()
            return null
        }
        val firstSize = size(firstPixmap)
        val secondSize = size(secondPixmap)
        if (firstSize == null || secondSize == null) {
            release()
            return null
        }
        val firstBuffer = wrap(firstAddress, firstSize)
        val secondBuffer = wrap(secondAddress, secondSize)
        if (firstBuffer == null || secondBuffer == null) {
            release()
            return null
        }

        first = null
        second = null
        return LinuxFrameTargets(ownedFirst, ownedSecond, firstBuffer, secondBuffer)
    } catch (failure: Throwable) {
        release(failure)
        throw failure
    }
}
