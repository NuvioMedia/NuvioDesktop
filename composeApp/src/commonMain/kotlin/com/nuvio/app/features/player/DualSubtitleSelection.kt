package com.nuvio.app.features.player

enum class SubtitleSlot { Primary, Secondary }

sealed interface PlayerSubtitleSelection {
    data class BuiltIn(val index: Int, val trackId: String? = null) : PlayerSubtitleSelection
    data class External(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val language: String? = null,
    ) : PlayerSubtitleSelection
}

internal data class DualSubtitleSelection(
    val primary: SubtitleSelectionOption?,
    val secondary: SubtitleSelectionOption?,
)

internal val SubtitleTrack.isBitmap: Boolean
    get() = codec?.lowercase() in setOf(
        "hdmv_pgs_subtitle", "pgs", "dvd_subtitle", "dvb_subtitle", "dvb_teletext", "xsub",
    )

internal fun SubtitleSelectionOption.sameSubtitle(other: SubtitleSelectionOption?): Boolean = when {
    this is SubtitleSelectionOption.BuiltIn && other is SubtitleSelectionOption.BuiltIn ->
        track.id == other.track.id
    this is SubtitleSelectionOption.Addon && other is SubtitleSelectionOption.Addon ->
        subtitle.selectionKey == other.subtitle.selectionKey
    else -> false
}

/** Resolve each language's audio-aware policy before compacting available results into two slots. */
internal fun resolveDualSubtitleSelection(
    tracks: List<SubtitleTrack>,
    addons: List<AddonSubtitle>,
    preferredLanguage: String,
    secondaryLanguage: String?,
    deviceLanguages: List<String>,
    audioTrack: AudioTrack?,
    preferredAudioTargets: List<String>,
    useForcedSubtitles: Boolean,
    preferBuiltInSubtitles: Boolean = false,
    currentSelection: DualSubtitleSelection? = null,
): DualSubtitleSelection {
    fun targets(language: String?): List<String> = when (language) {
        null, SubtitleLanguageOption.NONE, SubtitleLanguageOption.FORCED -> emptyList()
        else -> resolvePreferredSubtitleLanguageTargets(language, null, deviceLanguages)
    }

    fun pick(languageTargets: List<String>): SubtitleSelectionOption? {
        val plan = resolveSubtitleAutoSelectionPlan(
            selectedAudioTrack = audioTrack,
            preferredAudioTargets = preferredAudioTargets,
            preferredSubtitleTargets = languageTargets,
            useForcedSubtitles = useForcedSubtitles,
        ) ?: return null
        val candidates = tracks.filterNot { it.isBitmap }
        for (target in plan.targets) {
            fun matchesAddon(subtitle: AddonSubtitle): Boolean =
                addonSubtitleMatchesLanguage(subtitle, target) && when (plan.mode) {
                    SubtitleAutoSelectionMode.FORCED_ONLY -> addonSubtitleIsForced(subtitle) &&
                        audioTrack != null && addonSubtitleMatchesSelectedAudioLanguage(subtitle, audioTrack)
                    SubtitleAutoSelectionMode.NORMAL_ONLY -> !addonSubtitleIsForced(subtitle)
                }
            if (!preferBuiltInSubtitles) {
                val existing = listOfNotNull(currentSelection?.primary, currentSelection?.secondary).firstOrNull { option ->
                    when (option) {
                        is SubtitleSelectionOption.BuiltIn -> candidates.firstOrNull { it.id == option.track.id }?.let {
                            findPreferredSubtitleTrackIndex(listOf(it), listOf(target), plan.mode, audioTrack) >= 0
                        } == true
                        is SubtitleSelectionOption.Addon -> addons.any { it.selectionKey == option.subtitle.selectionKey && matchesAddon(it) }
                    }
                }
                if (existing != null) return existing
            }
            val index = findPreferredSubtitleTrackIndex(candidates, listOf(target), plan.mode, audioTrack)
            if (index >= 0) return SubtitleSelectionOption.BuiltIn(candidates[index])
            val addon = addons.firstOrNull(::matchesAddon)
            if (addon != null) return SubtitleSelectionOption.Addon(addon)
        }
        return null
    }

    // None/Forced retain their existing meaning instead of enabling the secondary preference alone.
    if (preferredLanguage == SubtitleLanguageOption.NONE || preferredLanguage == SubtitleLanguageOption.FORCED) {
        return DualSubtitleSelection(pick(emptyList()), null)
    }
    val primaryTargets = targets(preferredLanguage)
    val secondaryTargets = targets(secondaryLanguage).filterNot { secondary ->
        primaryTargets.any { primary -> SubtitleLanguageMatching.matchesLanguageCode(primary, secondary) }
    }
    val primary = pick(primaryTargets)
    val secondary = secondaryTargets.takeIf { it.isNotEmpty() }?.let {
        pick(it)
    }?.takeUnless { it.sameSubtitle(primary) }
    return if (primary == null) DualSubtitleSelection(secondary, null)
    else DualSubtitleSelection(primary, secondary)
}
