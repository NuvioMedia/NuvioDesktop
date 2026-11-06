package com.nuvio.app.features.player

import com.nuvio.app.features.autosync.AutoSyncPlayerController
import com.nuvio.app.features.autosync.AutoSyncPreferencesRepository
import com.nuvio.app.features.autosync.AutoSyncSyncedSubtitle
import com.nuvio.app.isDesktop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_subtitle_load_failed
import org.jetbrains.compose.resources.getString

internal val PlayerScreenRuntime.dualSubtitlesEnabled: Boolean
    get() = isDesktop && playerSettingsUiState.dualSubtitlesEnabled && !playerSettingsUiState.externalPlayerEnabled

internal fun PlayerScreenRuntime.subtitleSelection(slot: SubtitleSlot): SubtitleSelectionOption? =
    if (slot == SubtitleSlot.Secondary) secondarySubtitleSelection
    else selectedAddonSubtitle?.let(SubtitleSelectionOption::Addon)
        ?: subtitleTracks.firstOrNull { it.index == selectedSubtitleIndex }?.let(SubtitleSelectionOption::BuiltIn)

internal val PlayerScreenRuntime.activeSubtitleDelayMs: Int
    get() = if (activeSubtitleSlot == SubtitleSlot.Primary) subtitleDelayMs else secondarySubtitleDelayMs

internal var PlayerScreenRuntime.activeSubtitleAutoSyncState: SubtitleAutoSyncUiState
    get() = if (activeSubtitleSlot == SubtitleSlot.Primary) subtitleAutoSyncState else secondarySubtitleAutoSyncState
    set(value) {
        if (activeSubtitleSlot == SubtitleSlot.Primary) subtitleAutoSyncState = value
        else secondarySubtitleAutoSyncState = value
    }

internal fun PlayerScreenRuntime.resetDualSubtitleState() {
    automaticDualSubtitleJob?.cancel()
    primarySubtitleLoadJob?.cancel()
    secondarySubtitleLoadJob?.cancel()
    primarySubtitleCueJob?.cancel()
    secondarySubtitleCueJob?.cancel()
    primarySubtitleRequest++
    secondarySubtitleRequest++
    activeSubtitleSlot = SubtitleSlot.Primary
    dualPrimaryAddonSubtitle = null
    secondarySubtitleSelection = null
    secondarySubtitleNeedsClear = false
    secondarySubtitleAutoSyncedUrl = null
    isUserExplicitSecondarySubtitleSelection = false
    secondarySubtitleRestoreApplied = false
    secondarySubtitleDelayMs = 0
    secondarySubtitleAutoSyncState = SubtitleAutoSyncUiState()
    primarySubtitleLoading = false
    secondarySubtitleLoading = false
    failedDualSubtitleSelections.clear()
}

internal fun PlayerScreenRuntime.selectDualSubtitle(slot: SubtitleSlot, option: SubtitleSelectionOption?) {
    if (!dualSubtitlesEnabled) return
    val other = subtitleSelection(if (slot == SubtitleSlot.Primary) SubtitleSlot.Secondary else SubtitleSlot.Primary)
    if (option != null && (option.sameSubtitle(other) ||
            (option is SubtitleSelectionOption.BuiltIn && option.track.isBitmap))) return
    automaticDualSubtitleJob?.cancel()
    if (slot == SubtitleSlot.Primary) {
        primarySubtitleLoadJob?.cancel()
        primarySubtitleCueJob?.cancel()
        isUserExplicitSubtitleSelection = true
    } else {
        secondarySubtitleLoadJob?.cancel()
        secondarySubtitleCueJob?.cancel()
        isUserExplicitSecondarySubtitleSelection = true
    }
    val job = scope.launch {
        applyDualSubtitleSelection(slot, option, persist = true, manual = true)
        val completedLoad = currentCoroutineContext().job
        if (slot == SubtitleSlot.Primary && primarySubtitleLoadJob === completedLoad) primarySubtitleLoadJob = null
        if (slot == SubtitleSlot.Secondary && secondarySubtitleLoadJob === completedLoad) secondarySubtitleLoadJob = null
        refreshTracks()
    }
    if (slot == SubtitleSlot.Primary) primarySubtitleLoadJob = job else secondarySubtitleLoadJob = job
}

private suspend fun PlayerScreenRuntime.applyDualSubtitleSelection(
    slot: SubtitleSlot,
    option: SubtitleSelectionOption?,
    persist: Boolean,
    manual: Boolean,
) {
    val controller = playerController ?: return
    val identity = activePlaybackIdentity
    val request = if (slot == SubtitleSlot.Primary) ++primarySubtitleRequest else ++secondarySubtitleRequest
    val autoSync = controller as? AutoSyncPlayerController
    autoSync?.cancelForSubtitleSlot(slot)
    if (slot == SubtitleSlot.Primary) primarySubtitleLoading = true else secondarySubtitleLoading = true
    try {
        val selection = when (option) {
            null -> null
            is SubtitleSelectionOption.BuiltIn -> PlayerSubtitleSelection.BuiltIn(option.track.index, option.track.id)
            is SubtitleSelectionOption.Addon -> PlayerSubtitleSelection.External(
                option.subtitle.url,
                sanitizePlaybackHeaders(activeSourceHeaders + externalSubtitles.firstOrNull { it.url == option.subtitle.url }?.headers.orEmpty()),
                option.subtitle.language,
            )
        }
        check(controller.setSubtitleSelection(slot, selection)) { "Subtitle selection rejected" }
        currentCoroutineContext().ensureActive()
        if (identity != activePlaybackIdentity || controller !== playerController) return
        if (slot == SubtitleSlot.Primary) {
            dualPrimaryAddonSubtitle = (option as? SubtitleSelectionOption.Addon)?.subtitle
            selectedSubtitleIndex = (option as? SubtitleSelectionOption.BuiltIn)?.track?.index ?: -1
            selectedAddonSubtitleId = (option as? SubtitleSelectionOption.Addon)?.subtitle?.selectionKey
            useCustomSubtitles = option is SubtitleSelectionOption.Addon
            subtitleAutoSyncState = SubtitleAutoSyncUiState()
            if (persist) when (option) {
                is SubtitleSelectionOption.Addon -> persistAddonSubtitlePreference(option.subtitle)
                else -> persistInternalSubtitlePreference((option as? SubtitleSelectionOption.BuiltIn)?.track)
            }
        } else {
            secondarySubtitleSelection = option
            secondarySubtitleNeedsClear = false
            secondarySubtitleAutoSyncedUrl = null
            secondarySubtitleAutoSyncState = SubtitleAutoSyncUiState()
            if (persist) persistSecondarySubtitlePreference(option)
        }
        if (option is SubtitleSelectionOption.Addon && autoSync != null) {
            val startup = !manual
            if (!startup || AutoSyncPreferencesRepository.claimStartupRun(hashCode() + slot.ordinal, activePlaybackIdentity)) {
                autoSync.startSubtitleSlotAutoSync(slot, option.subtitle.url, startup)
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        if (identity == activePlaybackIdentity && controller === playerController) {
            failedDualSubtitleSelections += "$slot:${option?.id}"
            playerNotificationMessage = getString(Res.string.player_subtitle_load_failed)
            playerNotificationToken++
        }
    } finally {
        if (slot == SubtitleSlot.Primary && request == primarySubtitleRequest) primarySubtitleLoading = false
        if (slot == SubtitleSlot.Secondary && request == secondarySubtitleRequest) secondarySubtitleLoading = false
    }
}

private fun preserveSyncedSubtitle(
    current: SubtitleSelectionOption?,
    desired: SubtitleSelectionOption?,
    syncedUrl: String?,
    preferBuiltIn: Boolean,
): SubtitleSelectionOption? {
    if (preferBuiltIn && desired is SubtitleSelectionOption.BuiltIn) return desired
    if (current !is SubtitleSelectionOption.Addon || current.subtitle.url != syncedUrl || desired == null) return desired
    val (language, forced) = when (desired) {
        is SubtitleSelectionOption.BuiltIn -> desired.track.language to desired.track.isForced
        is SubtitleSelectionOption.Addon -> desired.subtitle.language to addonSubtitleIsForced(desired.subtitle)
    }
    return current.takeIf {
        language != null && languageMatchesPreference(it.subtitle.language, language) &&
            addonSubtitleIsForced(it.subtitle) == forced
    } ?: desired
}

internal fun PlayerScreenRuntime.applyDualSubtitlePreferences(preferredAudioTargets: List<String>) {
    if (!hasScannedTextTracksOnce || playbackSnapshot.isLoading || automaticDualSubtitleJob?.isActive == true ||
        primarySubtitleLoading || secondarySubtitleLoading) return
    restoreSecondarySubtitlePreference()
    if (secondarySubtitleLoadJob?.isActive == true) return
    fun failed(option: SubtitleSelectionOption) = failedDualSubtitleSelections.any { it.substringAfter(':') == option.id }
    val currentPrimary = subtitleSelection(SubtitleSlot.Primary)
    val resolved = resolveDualSubtitleSelection(
        tracks = subtitleTracks.filterNot { failed(SubtitleSelectionOption.BuiltIn(it)) },
        addons = addonSubtitles.filterNot { failed(SubtitleSelectionOption.Addon(it)) },
        preferredLanguage = playerSettingsUiState.preferredSubtitleLanguage,
        secondaryLanguage = playerSettingsUiState.secondaryPreferredSubtitleLanguage,
        deviceLanguages = DeviceLanguagePreferences.preferredLanguageCodes(),
        audioTrack = audioTracks.firstOrNull { it.index == selectedAudioIndex } ?: audioTracks.firstOrNull { it.isSelected },
        preferredAudioTargets = preferredAudioTargets,
        useForcedSubtitles = subtitleStyle.useForcedSubtitles,
        preferBuiltInSubtitles = subtitleStyle.preferBuiltInSubtitles,
        currentSelection = DualSubtitleSelection(currentPrimary, secondarySubtitleSelection),
    )
    val desiredPrimary = if (isUserExplicitSubtitleSelection) currentPrimary
    else resolved.primary?.takeUnless { isUserExplicitSecondarySubtitleSelection && it.sameSubtitle(secondarySubtitleSelection) }
        ?: resolved.secondary?.takeUnless { isUserExplicitSecondarySubtitleSelection && it.sameSubtitle(secondarySubtitleSelection) }
    val primary = preserveSyncedSubtitle(currentPrimary, desiredPrimary, AutoSyncSyncedSubtitle.url.value, subtitleStyle.preferBuiltInSubtitles)
    val desiredSecondary = if (isUserExplicitSecondarySubtitleSelection) secondarySubtitleSelection
    else (resolved.secondary ?: resolved.primary.takeIf { isUserExplicitSubtitleSelection })
        ?.takeUnless { it.sameSubtitle(primary) || (it is SubtitleSelectionOption.BuiltIn && it.track.isBitmap) }
    val secondary = preserveSyncedSubtitle(secondarySubtitleSelection, desiredSecondary, secondarySubtitleAutoSyncedUrl, subtitleStyle.preferBuiltInSubtitles)
    fun changed(slot: SubtitleSlot, desired: SubtitleSelectionOption?): Boolean {
        if ("$slot:${desired?.id}" in failedDualSubtitleSelections) return false
        if (slot == SubtitleSlot.Secondary && secondarySubtitleNeedsClear) return true
        val current = subtitleSelection(slot)
        return if (desired == null) current != null else !desired.sameSubtitle(current)
    }
    preferredSubtitleSelectionApplied = true
    if (!changed(SubtitleSlot.Primary, primary) && !changed(SubtitleSlot.Secondary, secondary)) return
    automaticDualSubtitleJob = scope.launch {
        if (secondarySubtitleNeedsClear) {
            applyDualSubtitleSelection(SubtitleSlot.Secondary, null, persist = false, manual = false)
        }
        // Clear a moving track before assigning it to the other native slot.
        if (primary != null && primary.sameSubtitle(secondarySubtitleSelection) && !isUserExplicitSecondarySubtitleSelection) {
            applyDualSubtitleSelection(SubtitleSlot.Secondary, null, persist = false, manual = false)
        }
        if (!isUserExplicitSubtitleSelection && changed(SubtitleSlot.Primary, primary)) {
            applyDualSubtitleSelection(SubtitleSlot.Primary, primary, persist = false, manual = false)
        }
        if (!isUserExplicitSecondarySubtitleSelection && changed(SubtitleSlot.Secondary, secondary)) {
            applyDualSubtitleSelection(SubtitleSlot.Secondary, secondary, persist = false, manual = false)
        }
        automaticDualSubtitleJob = null
        refreshTracks()
    }
}

internal fun PlayerScreenRuntime.persistSecondarySubtitlePreference(option: SubtitleSelectionOption?) {
    val preference = when (option) {
        null -> PersistedPlayerTrackPreference(subtitleType = PersistedSubtitleSelectionType.DISABLED)
        is SubtitleSelectionOption.BuiltIn -> PersistedPlayerTrackPreference(
            subtitleType = PersistedSubtitleSelectionType.INTERNAL,
            subtitleTrackId = option.track.id, subtitleLanguage = option.track.language,
            subtitleName = option.track.label, subtitleIsForced = option.track.isForced,
        )
        is SubtitleSelectionOption.Addon -> PersistedPlayerTrackPreference(
            subtitleType = PersistedSubtitleSelectionType.ADDON,
            subtitleLanguage = option.subtitle.language, subtitleName = option.subtitle.display,
            addonSubtitleId = option.subtitle.selectionKey, addonSubtitleUrl = option.subtitle.url,
            addonSubtitleAddonName = option.subtitle.addonName, addonSubtitleItemId = subtitlePreferenceItemId,
        )
    }
    updateTrackPreference { it.copy(secondarySubtitlePreference = Json.encodeToString(preference)) }
}

private fun PlayerScreenRuntime.restoreSecondarySubtitlePreference() {
    if (secondarySubtitleRestoreApplied || isUserExplicitSecondarySubtitleSelection) return
    val stored = PlayerTrackPreferenceStorage.load(parentMetaId)?.secondarySubtitlePreference
    if (stored == null) { secondarySubtitleRestoreApplied = true; return }
    val preference = Json.decodeFromString<PersistedPlayerTrackPreference>(stored)
    val option = when (preference.subtitleType) {
        PersistedSubtitleSelectionType.INTERNAL -> {
            val index = findPersistedSubtitleTrackIndex(subtitleTracks, preference)
            subtitleTracks.firstOrNull { it.index == index }?.takeUnless { it.isBitmap }?.let(SubtitleSelectionOption::BuiltIn)
        }
        PersistedSubtitleSelectionType.ADDON -> {
            if (isLoadingAddonSubtitles) return
            if (preference.addonSubtitleItemId != subtitlePreferenceItemId) null
            else findPersistedAddonSubtitle(addonSubtitles, preference)?.let(SubtitleSelectionOption::Addon)
        }
        else -> null
    }
    secondarySubtitleRestoreApplied = true
    if (option != null) {
        val preferred = playerSettingsUiState.secondaryPreferredSubtitleLanguage
        val targets = if (preferred == null || preferred == SubtitleLanguageOption.NONE || preferred == SubtitleLanguageOption.FORCED) {
            emptyList()
        } else {
            resolvePreferredSubtitleLanguageTargets(preferred, null, DeviceLanguagePreferences.preferredLanguageCodes())
        }
        val language = when (option) {
            is SubtitleSelectionOption.BuiltIn -> option.track.language
            is SubtitleSelectionOption.Addon -> option.subtitle.language
        }
        val matches = targets.any { target ->
            SubtitleLanguageMatching.matchesLanguageCode(language, target) ||
                (!language.isNullOrBlank() && SubtitleLanguageMatching.matchesLanguageCode(target, language)) ||
                (language.isNullOrBlank() && option is SubtitleSelectionOption.BuiltIn && subtitleTrackMatchesLanguage(option.track, target))
        }
        if (!matches) return
    }
    if (option != null) {
        selectDualSubtitle(SubtitleSlot.Secondary, option)
    }
}
