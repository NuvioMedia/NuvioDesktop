package com.nuvio.app.features.player.skip

enum class AutoSkipSegmentType(val storedValue: String) {
    INTRO("intro"),
    RECAP("recap"),
    OUTRO("outro"),
    MOVIE_CREDITS("movie-credits");

    companion object {
        fun fromStoredValue(value: String): AutoSkipSegmentType? =
            entries.firstOrNull { it.storedValue == value }

        fun fromSkipIntervalType(type: String): AutoSkipSegmentType? = when (type.trim().lowercase()) {
            "op", "opening", "mixed-op", "intro" -> INTRO
            "recap" -> RECAP
            "ed", "ending", "mixed-ed", "outro", "credits" -> OUTRO
            "movie-credits" -> MOVIE_CREDITS
            else -> null
        }
    }
}

internal fun SkipInterval.autoSkipKey(): String =
    "$provider:$type:$startTime:$endTime"

internal fun List<SkipInterval>.autoSkipKeysCompletedBy(positionMs: Long): Set<String> {
    if (positionMs <= 0L) return emptySet()
    return asSequence()
        .filter { interval -> interval.endTime * 1000.0 <= positionMs.toDouble() }
        .mapTo(linkedSetOf()) { interval -> interval.autoSkipKey() }
}

internal fun SkipInterval.shouldAutoSkip(selectedTypes: Set<AutoSkipSegmentType>): Boolean =
    startTime.isFinite() && endTime.isFinite() && startTime >= 0 && endTime > startTime &&
        AutoSkipSegmentType.fromSkipIntervalType(type) in selectedTypes

internal fun List<SkipInterval>.intervalsAtSeekPositions(fromMs: Long, toMs: Long): List<SkipInterval> =
    filter { interval ->
        AutoSkipSegmentType.fromSkipIntervalType(interval.type) != null &&
            listOf(fromMs, toMs).any { position ->
                val seconds = position / 1000.0
                seconds >= interval.startTime && seconds < interval.endTime
            }
    }
