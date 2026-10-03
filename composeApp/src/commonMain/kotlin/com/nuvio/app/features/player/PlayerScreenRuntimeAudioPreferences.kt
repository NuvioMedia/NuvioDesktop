package com.nuvio.app.features.player

internal val PlayerScreenRuntime.contentLanguage: String?
    get() {
        val metadata = listOfNotNull(metaUiState.meta, playerMeta).filter {
            it.id == parentMetaId && it.type == parentMetaType
        }
        return metadata.firstNotNullOfOrNull { normalizeLanguageCode(it.language) }
            ?: normalizeLanguageCode(args.contentLanguage)
            ?: metadata.firstNotNullOfOrNull { countryToLanguageCode(it.country) }
    }

internal val PlayerScreenRuntime.preferredAudioLanguageTargets: List<String>
    get() = resolvePreferredAudioLanguageTargets(
        preferredAudioLanguage = playerSettingsUiState.preferredAudioLanguage,
        secondaryPreferredAudioLanguage = playerSettingsUiState.secondaryPreferredAudioLanguage,
        deviceLanguages = DeviceLanguagePreferences.preferredLanguageCodes(),
        contentOriginalLanguage = contentLanguage,
    )

internal data class AppliedAudioPreferences(
    val controller: PlayerEngineController,
    val languages: List<String>,
    val tracks: List<AudioTrack>,
)

internal fun PlayerScreenRuntime.applyPreferredAudioTrack(targets: List<String>) {
    if (isUserExplicitAudioSelection) return
    val controller = playerController ?: return
    val preferences = AppliedAudioPreferences(
        controller = controller,
        languages = targets,
        tracks = audioTracks.map { it.copy(isSelected = false) },
    )
    // The next episode often brings an identical track list, so the cached
    // preferences match although the new player sits on its default track.
    // Re-apply whenever the preferred track isn't the selected one.
    val preferredTrack = targets.firstNotNullOfOrNull { target ->
        audioTracks.firstOrNull { languageMatchesPreference(it.language, target) }
    }
    val preferredTrackNotSelected = preferredTrack != null && !preferredTrack.isSelected
    if (appliedAudioPreferences != preferences || preferredTrackNotSelected) {
        controller.applyAudioLanguagePreferences(targets)
        appliedAudioPreferences = preferences
        preferredAudioSelectionApplied = false
    } else {
        preferredAudioSelectionApplied = true
    }
}

internal fun PlayerScreenRuntime.restorePersistedAudioPreference(preference: PersistedPlayerTrackPreference) {
    if (audioTracks.isEmpty()) return
    val restoredIndex = findPersistedAudioTrackIndex(audioTracks, preference)
    if (restoredIndex < 0) return
    playerController?.selectAudioTrack(restoredIndex)
    selectedAudioIndex = restoredIndex
    isUserExplicitAudioSelection = true
    preferredAudioSelectionApplied = true
}

internal fun PlayerScreenRuntime.refreshAudioTracksIfChanged() {
    if (playbackSnapshot.isLoading) return
    val controller = playerController ?: return
    if (!preferredAudioSelectionApplied || controller.getAudioTracks() != audioTracks) {
        refreshTracks()
    }
}
