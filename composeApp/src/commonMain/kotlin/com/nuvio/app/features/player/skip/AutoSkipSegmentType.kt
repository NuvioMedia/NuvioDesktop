package com.nuvio.app.features.player.skip

enum class AutoSkipSegmentType(val storedValue: String) {
    INTRO("intro"),
    RECAP("recap"),
    OUTRO("outro");

    companion object {
        fun fromStoredValue(value: String): AutoSkipSegmentType? =
            entries.firstOrNull { it.storedValue == value }

        fun fromSkipIntervalType(type: String): AutoSkipSegmentType? = when (type.trim().lowercase()) {
            "op", "opening", "mixed-op", "intro" -> INTRO
            "recap" -> RECAP
            "ed", "ending", "mixed-ed", "outro", "credits" -> OUTRO
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
