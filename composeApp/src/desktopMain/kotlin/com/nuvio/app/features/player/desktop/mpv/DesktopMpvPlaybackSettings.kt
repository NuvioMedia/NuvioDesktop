package com.nuvio.app.features.player.desktop.mpv

import com.nuvio.app.desktop.DesktopPreferences
import com.nuvio.app.desktop.DesktopRuntimeLog
import com.nuvio.app.features.player.IosTargetPrimaries
import com.nuvio.app.features.player.IosTargetTransfer
import com.nuvio.app.features.player.IosToneMappingMode
import com.nuvio.app.features.player.IosVideoOutputPreset
import com.nuvio.app.features.player.PlayerHardwareDecoderMode
import com.nuvio.app.features.player.PlayerSettingsUiState
import com.nuvio.app.features.player.PlayerTargetPrimaries
import com.nuvio.app.features.player.PlayerTargetTransfer
import com.nuvio.app.features.player.PlayerToneMappingMode
import com.nuvio.app.features.player.PlayerVideoOutputPreset
import com.nuvio.app.features.player.PlayerVideoTuningSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

internal const val DesktopDecoderPreferencesName = "nuvio_decoder_settings"
internal const val DesktopHwdecModeKey = "hwdec_mode"
internal const val DesktopHdrModeKey = "hdr_mode"
internal const val DesktopVideoOutputPresetKey = "video_output_preset"
internal const val DesktopToneMappingModeKey = "tone_mapping_mode"
internal const val DesktopTargetPrimariesKey = "target_primaries"
internal const val DesktopTargetTransferKey = "target_transfer"
internal const val DesktopHdrComputePeakKey = "hdr_compute_peak"
internal const val DesktopDebandEnabledKey = "deband_enabled"
internal const val DesktopInterpolationEnabledKey = "interpolation_enabled"
internal const val DesktopBrightnessKey = "brightness"
internal const val DesktopContrastKey = "contrast"
internal const val DesktopSaturationKey = "saturation"
internal const val DesktopGammaKey = "gamma"

internal object DesktopMpvPlaybackSettingsSignal {
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    fun notifyChanged() {
        _version.update { it + 1 }
    }
}

internal data class MpvRuntimeOption(
    val name: String,
    val value: String,
)

internal data class DesktopMpvVideoTuning(
    val settings: PlayerVideoTuningSettings,
    val legacyHdrMode: DesktopHdrMode,
)

internal enum class DesktopHdrMode(
    val storageValue: String,
    val label: String,
    val description: String,
) {
    Auto(
        storageValue = "auto",
        label = "Auto (recommended)",
        description = "Let mpv pick the best HDR and tone-mapping path for the current display.",
    ),
    ToneMapToSdr(
        storageValue = "tone_map_sdr",
        label = "Tone map to SDR",
        description = "Map HDR video into the app's SDR desktop surface for consistent colors.",
    );

    companion object {
        fun fromStorage(value: String?): DesktopHdrMode =
            entries.firstOrNull { it.storageValue == value } ?: Auto
    }
}

internal fun loadDesktopMpvVideoTuning(): DesktopMpvVideoTuning {
    val legacyHdrMode = DesktopHdrMode.fromStorage(
        DesktopPreferences.getString(DesktopDecoderPreferencesName, DesktopHdrModeKey),
    )
    val preset = DesktopPreferences.getString(DesktopDecoderPreferencesName, DesktopVideoOutputPresetKey)
        ?.enumValueOrNull<PlayerVideoOutputPreset>()
        ?: legacyHdrMode.toVideoOutputPreset()

    return DesktopMpvVideoTuning(
        settings = PlayerVideoTuningSettings(
            outputPreset = preset,
            hardwareDecoderMode = loadHardwareDecoderMode(),
            toneMappingMode = loadEnum(DesktopToneMappingModeKey, preset.defaultToneMapping()),
            targetPrimaries = loadEnum(DesktopTargetPrimariesKey, preset.defaultPrimaries()),
            targetTransfer = loadEnum(DesktopTargetTransferKey, preset.defaultTransfer()),
            hdrComputePeakEnabled = DesktopPreferences.getBoolean(
                DesktopDecoderPreferencesName,
                DesktopHdrComputePeakKey,
            ) ?: true,
            debandEnabled = DesktopPreferences.getBoolean(DesktopDecoderPreferencesName, DesktopDebandEnabledKey) ?: false,
            interpolationEnabled = DesktopPreferences.getBoolean(
                DesktopDecoderPreferencesName,
                DesktopInterpolationEnabledKey,
            ) ?: false,
            brightness = DesktopPreferences.getInt(DesktopDecoderPreferencesName, DesktopBrightnessKey)?.coerceVideoEq()
                ?: 0,
            contrast = DesktopPreferences.getInt(DesktopDecoderPreferencesName, DesktopContrastKey)?.coerceVideoEq()
                ?: 0,
            saturation = DesktopPreferences.getInt(DesktopDecoderPreferencesName, DesktopSaturationKey)?.coerceVideoEq()
                ?: 0,
            gamma = DesktopPreferences.getInt(DesktopDecoderPreferencesName, DesktopGammaKey)?.coerceVideoEq()
                ?: 0,
        ),
        legacyHdrMode = legacyHdrMode,
    )
}

internal fun storeDesktopVideoOutputPreset(preset: PlayerVideoOutputPreset) {
    DesktopPreferences.putString(DesktopDecoderPreferencesName, DesktopVideoOutputPresetKey, preset.name)
    DesktopPreferences.putString(DesktopDecoderPreferencesName, DesktopToneMappingModeKey, preset.defaultToneMapping().name)
    DesktopPreferences.putString(DesktopDecoderPreferencesName, DesktopTargetPrimariesKey, preset.defaultPrimaries().name)
    DesktopPreferences.putString(DesktopDecoderPreferencesName, DesktopTargetTransferKey, preset.defaultTransfer().name)
    DesktopPreferences.putString(DesktopDecoderPreferencesName, DesktopHdrModeKey, preset.toLegacyHdrMode().storageValue)
    DesktopMpvPlaybackSettingsSignal.notifyChanged()
}

internal fun storeDesktopHardwareDecoderMode(mode: PlayerHardwareDecoderMode) {
    DesktopPreferences.putString(DesktopDecoderPreferencesName, DesktopHwdecModeKey, mode.name)
    DesktopMpvPlaybackSettingsSignal.notifyChanged()
}

internal fun storeDesktopToneMappingMode(mode: PlayerToneMappingMode) {
    DesktopPreferences.putString(DesktopDecoderPreferencesName, DesktopVideoOutputPresetKey, PlayerVideoOutputPreset.Custom.name)
    DesktopPreferences.putString(DesktopDecoderPreferencesName, DesktopToneMappingModeKey, mode.name)
    DesktopMpvPlaybackSettingsSignal.notifyChanged()
}

internal fun storeDesktopTargetPrimaries(primaries: PlayerTargetPrimaries) {
    DesktopPreferences.putString(DesktopDecoderPreferencesName, DesktopVideoOutputPresetKey, PlayerVideoOutputPreset.Custom.name)
    DesktopPreferences.putString(DesktopDecoderPreferencesName, DesktopTargetPrimariesKey, primaries.name)
    DesktopMpvPlaybackSettingsSignal.notifyChanged()
}

internal fun storeDesktopTargetTransfer(transfer: PlayerTargetTransfer) {
    DesktopPreferences.putString(DesktopDecoderPreferencesName, DesktopVideoOutputPresetKey, PlayerVideoOutputPreset.Custom.name)
    DesktopPreferences.putString(DesktopDecoderPreferencesName, DesktopTargetTransferKey, transfer.name)
    DesktopMpvPlaybackSettingsSignal.notifyChanged()
}

internal fun storeDesktopBooleanTuning(key: String, value: Boolean) {
    DesktopPreferences.putBoolean(DesktopDecoderPreferencesName, key, value)
    DesktopMpvPlaybackSettingsSignal.notifyChanged()
}

internal fun storeDesktopIntTuning(key: String, value: Int) {
    DesktopPreferences.putInt(DesktopDecoderPreferencesName, key, value.coerceVideoEq())
    DesktopMpvPlaybackSettingsSignal.notifyChanged()
}

internal fun storeDesktopVideoTuningFromPlayerSettings(settings: PlayerSettingsUiState) {
    DesktopPreferences.putString(
        DesktopDecoderPreferencesName,
        DesktopVideoOutputPresetKey,
        settings.iosVideoOutputPreset.toDesktopPreset().name,
    )
    DesktopPreferences.putString(
        DesktopDecoderPreferencesName,
        DesktopToneMappingModeKey,
        settings.iosToneMappingMode.toDesktopToneMappingMode().name,
    )
    DesktopPreferences.putString(
        DesktopDecoderPreferencesName,
        DesktopTargetPrimariesKey,
        settings.iosTargetPrimaries.toDesktopTargetPrimaries().name,
    )
    DesktopPreferences.putString(
        DesktopDecoderPreferencesName,
        DesktopTargetTransferKey,
        settings.iosTargetTransfer.toDesktopTargetTransfer().name,
    )
    DesktopPreferences.putString(
        DesktopDecoderPreferencesName,
        DesktopHdrModeKey,
        settings.iosVideoOutputPreset.toDesktopPreset().toLegacyHdrMode().storageValue,
    )
    DesktopPreferences.putBoolean(DesktopDecoderPreferencesName, DesktopHdrComputePeakKey, settings.iosHdrComputePeakEnabled)
    DesktopPreferences.putBoolean(DesktopDecoderPreferencesName, DesktopDebandEnabledKey, settings.iosDebandEnabled)
    DesktopPreferences.putBoolean(DesktopDecoderPreferencesName, DesktopInterpolationEnabledKey, settings.iosInterpolationEnabled)
    DesktopPreferences.putInt(DesktopDecoderPreferencesName, DesktopBrightnessKey, settings.iosBrightness.coerceVideoEq())
    DesktopPreferences.putInt(DesktopDecoderPreferencesName, DesktopContrastKey, settings.iosContrast.coerceVideoEq())
    DesktopPreferences.putInt(DesktopDecoderPreferencesName, DesktopSaturationKey, settings.iosSaturation.coerceVideoEq())
    DesktopPreferences.putInt(DesktopDecoderPreferencesName, DesktopGammaKey, settings.iosGamma.coerceVideoEq())
    DesktopMpvPlaybackSettingsSignal.notifyChanged()
}

internal fun mpvRuntimeOptions(tuning: DesktopMpvVideoTuning): List<MpvRuntimeOption> {
    val settings = tuning.settings
    val targetPeak = when (settings.outputPreset) {
        PlayerVideoOutputPreset.ToneMappedSdr -> "203"
        else -> "auto"
    }
    // Linux ships without a working VA-API/CUDA/Vulkan video decode stack in
    // most desktop environments. When the user has hwdec=auto or a specific
    // codec enabled, mpv spams init errors (CUDA_ERROR_NO_DEVICE, VK_KHR_video
    // decode unsupported, etc.) and may stall. Force software decode on Linux
    // unless the user explicitly opted in via NUVIO_MPV_DIAGNOSTIC_HWDEC.
    //
    // Windows with vo=libmpv (offscreen GL renderer): GPU-decoded frames must
    // be in system memory for the render API to access. Non-copy hwdec modes
    // (d3d11va, nvdec, cuda) keep frames on the GPU, causing mpv to silently
    // fall back to software decoding. Always use a copy-back mode on Windows
    // unless the user explicitly overrides via NUVIO_MPV_DIAGNOSTIC_HWDEC.
    val hwdecValue = when {
        isOsLinux() &&
            System.getProperty("nuvio.mpv.diagnostic.hwdec") == null &&
            System.getenv("NUVIO_MPV_DIAGNOSTIC_HWDEC") == null -> "no"
        isOsWindows() &&
            System.getProperty("nuvio.mpv.diagnostic.hwdec") == null &&
            System.getenv("NUVIO_MPV_DIAGNOSTIC_HWDEC") == null -> {
            val userValue = settings.hardwareDecoderMode.mpvValue
            if (userValue == "auto" || userValue == "d3d11va" || userValue == "nvdec" || userValue == "cuda") {
                "d3d11va-copy"
            } else {
                userValue
            }
        }
        else -> settings.hardwareDecoderMode.mpvValue
    }
    return listOf(
        *stremioBaselineRuntimeOptions().toTypedArray(),
        MpvRuntimeOption("hwdec", hwdecValue),
        MpvRuntimeOption("tone-mapping", settings.toneMappingMode.mpvValue),
        MpvRuntimeOption("hdr-compute-peak", if (settings.hdrComputePeakEnabled) "auto" else "no"),
        MpvRuntimeOption("target-prim", settings.targetPrimaries.mpvValue),
        MpvRuntimeOption("target-trc", settings.targetTransfer.mpvValue),
        MpvRuntimeOption("target-peak", targetPeak),
        MpvRuntimeOption("gamut-mapping", if (settings.outputPreset == PlayerVideoOutputPreset.ToneMappedSdr) "desaturate" else "auto"),
        MpvRuntimeOption("hdr-peak-percentile", "99.99"),
        MpvRuntimeOption("hdr-contrast-recovery", "0.30"),
        MpvRuntimeOption("hdr-contrast-recovery-speed", "3.0"),
        *when (settings.toneMappingMode) {
            PlayerToneMappingMode.Bt2390 -> listOf(MpvRuntimeOption("tone-mapping-param", "2.0"))
            PlayerToneMappingMode.Mobius -> listOf(MpvRuntimeOption("tone-mapping-param", "0.3"))
            PlayerToneMappingMode.Reinhard -> listOf(MpvRuntimeOption("tone-mapping-param", "0.5"))
            PlayerToneMappingMode.Gamma -> listOf(MpvRuntimeOption("tone-mapping-param", "1.8"))
            else -> emptyList()
        }.toTypedArray(),
        MpvRuntimeOption("deband", if (settings.debandEnabled) "yes" else "no"),
        MpvRuntimeOption("interpolation", if (settings.interpolationEnabled) "yes" else "no"),
        MpvRuntimeOption("video-sync", if (settings.interpolationEnabled) "display-resample" else "audio"),
        MpvRuntimeOption("brightness", settings.brightness.toString()),
        MpvRuntimeOption("contrast", settings.contrast.toString()),
        MpvRuntimeOption("saturation", settings.saturation.toString()),
        MpvRuntimeOption("gamma", settings.gamma.toString()),
        *diagnosticRuntimeOptions().toTypedArray(),
    )
}

internal fun stremioBaselineRuntimeOptions(): List<MpvRuntimeOption> =
    listOf(
        // Probesize/analyzeduration: defaults are 5MB/~5s but mpv's own
        // --demuxer-lavf-probesize=524288 (512KB) with analyzeduration=5s is
        // too aggressive for HLS streams with corrupt mid-segment packets
        // (e.g. "non-existing PPS 0 referenced"); the demuxer stalls exactly
        // when the cache window is exhausted, producing a freeze that looks
        // like a 1:01 hang. Give the demuxer more data so it can find a clean
        // keyframe past the bad packet.
        MpvRuntimeOption("demuxer-lavf-probesize", "1048576"),
        MpvRuntimeOption("demuxer-lavf-analyzeduration", "10"),
        // Drop corrupt packets at the demuxer rather than passing them to
        // the decoder. Without this, a single bad packet in an HLS segment
        // hangs the decoder ("non-existing PPS 0 referenced") and the
        // player never recovers. discardcorrupt is the ffmpeg-level flag
        // exposed via mpv's demuxer-lavf option list.
        MpvRuntimeOption("demuxer-lavf-o", "fflags=+discardcorrupt"),
        // Drop frames the decoder couldn't produce (vs. erroring out).
        // Required for the same reason: one bad H.264 reference frame
        // otherwise stalls playback forever.
        MpvRuntimeOption("framedrop", "decoder+vo"),
        // Tell libavcodec to ignore decode errors instead of bailing out on
        // a single bad H.264 reference (e.g. "non-existing PPS 0 referenced"
        // from a mid-segment HLS corruption). Without this, the decoder can
        // hit a fatal error state that discardcorrupt + framedrop don't
        // catch, and the stream stays frozen forever.
        MpvRuntimeOption("errordetect", "ignore_err"),
        // For HLS in particular, reduce the read-ahead so a stalled segment
        // doesn't pin the whole 60s cache window. This complements
        // cache-secs=60 below by limiting how much pre-buffer the network
        // thread will wait on.
        MpvRuntimeOption("demuxer-readahead-secs", "1"),
        // Keep the most recently decoded frame around when the decoder
        // gives up on a packet, so the surface still has something to draw
        // even if a corrupt NAL halts decoding briefly.
        MpvRuntimeOption("demuxer-lavf-keep-frame", "1"),
        MpvRuntimeOption("demuxer-max-bytes", stremioCacheBytes()),
        MpvRuntimeOption("demuxer-max-packets", "150000000"),
        MpvRuntimeOption("cache", "yes"),
        MpvRuntimeOption("cache-pause", "no"),
        MpvRuntimeOption("cache-secs", "60"),
        MpvRuntimeOption("force-seekable", "yes"),
        MpvRuntimeOption("vd-lavc-threads", "0"),
        MpvRuntimeOption("ad-lavc-threads", "0"),
        MpvRuntimeOption("audio-fallback-to-null", "yes"),
        MpvRuntimeOption("audio-client-name", "Nuvio"),
        MpvRuntimeOption("title", "Nuvio"),
    )

private fun stremioCacheBytes(): String {
    val configured = System.getProperty("nuvio.mpv.demuxer.maxBytes")
        ?: System.getenv("NUVIO_MPV_DEMUXER_MAX_BYTES")
    return configured
        ?.toLongOrNull()
        ?.coerceIn(32L * 1024L * 1024L, 300L * 1024L * 1024L)
        ?.toString()
        ?: (128L * 1024L * 1024L).toString()
}

private fun diagnosticRuntimeOptions(): List<MpvRuntimeOption> =
    listOfNotNull(
        boundedDiagnosticOption(
            name = "hwdec",
            propertyName = "nuvio.mpv.diagnostic.hwdec",
            envName = "NUVIO_MPV_DIAGNOSTIC_HWDEC",
            allowedValues = setOf("auto", "no", "d3d11va", "d3d11va-copy", "dxva2", "nvdec", "nvdec-copy", "vaapi", "vaapi-copy", "vdpau", "vdpau-copy", "cuda", "cuda-copy"),
        ),
        boundedDiagnosticOption(
            name = "framedrop",
            propertyName = "nuvio.mpv.diagnostic.framedrop",
            envName = "NUVIO_MPV_DIAGNOSTIC_FRAMEDROP",
            allowedValues = setOf("no", "vo", "decoder", "decoder+vo"),
        ),
        boundedDiagnosticOption(
            name = "video-sync",
            propertyName = "nuvio.mpv.diagnostic.videoSync",
            envName = "NUVIO_MPV_DIAGNOSTIC_VIDEO_SYNC",
            allowedValues = setOf(
                "audio",
                "display-resample",
                "display-resample-vdrop",
                "display-resample-desync",
                "display-vdrop",
                "display-adrop",
                "display-desync",
                "desync",
            ),
        ),
    )

private fun boundedDiagnosticOption(
    name: String,
    propertyName: String,
    envName: String,
    allowedValues: Set<String>,
): MpvRuntimeOption? {
    val value = (System.getProperty(propertyName) ?: System.getenv(envName))
        ?.trim()
        ?.lowercase()
        ?: return null
    return value
        .takeIf { it in allowedValues }
        ?.let { MpvRuntimeOption(name, it) }
}

internal fun loadHardwareDecoderMode(): PlayerHardwareDecoderMode {
    val storedValue = DesktopPreferences.getString(DesktopDecoderPreferencesName, DesktopHwdecModeKey)
    return storedValue?.enumValueOrNull<PlayerHardwareDecoderMode>()
        ?: storedValue?.legacyHwdecValue()?.enumValueOrNull<PlayerHardwareDecoderMode>()
        ?: defaultHardwareDecoderMode()
}

private fun defaultHardwareDecoderMode(): PlayerHardwareDecoderMode {
    val osName = System.getProperty("os.name").lowercase()
    return when {
        osName.startsWith("linux") -> PlayerHardwareDecoderMode.Off
        osName.startsWith("windows") -> PlayerHardwareDecoderMode.D3d11vaCopy
        else -> PlayerHardwareDecoderMode.Auto
    }
}

private inline fun <reified T : Enum<T>> loadEnum(key: String, default: T): T =
    DesktopPreferences.getString(DesktopDecoderPreferencesName, key)?.enumValueOrNull<T>()
        ?: default

private inline fun <reified T : Enum<T>> String.enumValueOrNull(): T? =
    enumValues<T>().firstOrNull { it.name == this }

private fun String.legacyHwdecValue(): String? =
    when (this) {
        "auto" -> PlayerHardwareDecoderMode.Auto.name
        "no" -> PlayerHardwareDecoderMode.Off.name
        "d3d11va" -> PlayerHardwareDecoderMode.D3d11va.name
        "d3d11va-copy" -> PlayerHardwareDecoderMode.D3d11vaCopy.name
        "dxva2" -> PlayerHardwareDecoderMode.Dxva2.name
        "nvdec" -> PlayerHardwareDecoderMode.Nvdec.name
        "nvdec-copy" -> PlayerHardwareDecoderMode.NvdecCopy.name
        "vaapi" -> PlayerHardwareDecoderMode.Vaapi.name
        "vaapi-copy" -> PlayerHardwareDecoderMode.VaapiCopy.name
        "vdpau" -> PlayerHardwareDecoderMode.Vdpau.name
        "vdpau-copy" -> PlayerHardwareDecoderMode.VdpauCopy.name
        "cuda" -> PlayerHardwareDecoderMode.Cuda.name
        "cuda-copy" -> PlayerHardwareDecoderMode.CudaCopy.name
        else -> null
    }

private fun DesktopHdrMode.toVideoOutputPreset(): PlayerVideoOutputPreset =
    when (this) {
        DesktopHdrMode.Auto -> PlayerVideoOutputPreset.Native
        DesktopHdrMode.ToneMapToSdr -> PlayerVideoOutputPreset.ToneMappedSdr
    }

private fun PlayerVideoOutputPreset.toLegacyHdrMode(): DesktopHdrMode =
    when (this) {
        PlayerVideoOutputPreset.ToneMappedSdr -> DesktopHdrMode.ToneMapToSdr
        else -> DesktopHdrMode.Auto
    }

private fun PlayerVideoOutputPreset.defaultToneMapping(): PlayerToneMappingMode =
    when (this) {
        PlayerVideoOutputPreset.ToneMappedSdr -> PlayerToneMappingMode.Mobius
        else -> PlayerToneMappingMode.Auto
    }

private fun PlayerVideoOutputPreset.defaultPrimaries(): PlayerTargetPrimaries =
    when (this) {
        PlayerVideoOutputPreset.ToneMappedSdr -> PlayerTargetPrimaries.Bt709
        else -> PlayerTargetPrimaries.Auto
    }

private fun PlayerVideoOutputPreset.defaultTransfer(): PlayerTargetTransfer =
    when (this) {
        PlayerVideoOutputPreset.ToneMappedSdr -> PlayerTargetTransfer.Srgb
        else -> PlayerTargetTransfer.Auto
    }

private fun IosVideoOutputPreset.toDesktopPreset(): PlayerVideoOutputPreset =
    when (this) {
        IosVideoOutputPreset.NativeEdr -> PlayerVideoOutputPreset.Native
        IosVideoOutputPreset.SdrToneMapped -> PlayerVideoOutputPreset.ToneMappedSdr
        IosVideoOutputPreset.Compatibility -> PlayerVideoOutputPreset.Compatibility
        IosVideoOutputPreset.Custom -> PlayerVideoOutputPreset.Custom
    }

private fun IosToneMappingMode.toDesktopToneMappingMode(): PlayerToneMappingMode =
    when (this) {
        IosToneMappingMode.Auto -> PlayerToneMappingMode.Auto
        IosToneMappingMode.Bt2390 -> PlayerToneMappingMode.Bt2390
        IosToneMappingMode.Mobius -> PlayerToneMappingMode.Mobius
        IosToneMappingMode.Reinhard -> PlayerToneMappingMode.Reinhard
        IosToneMappingMode.Hable -> PlayerToneMappingMode.Hable
        IosToneMappingMode.Gamma -> PlayerToneMappingMode.Gamma
        IosToneMappingMode.Clip -> PlayerToneMappingMode.Clip
    }

private fun IosTargetPrimaries.toDesktopTargetPrimaries(): PlayerTargetPrimaries =
    when (this) {
        IosTargetPrimaries.Auto -> PlayerTargetPrimaries.Auto
        IosTargetPrimaries.Bt709 -> PlayerTargetPrimaries.Bt709
        IosTargetPrimaries.DisplayP3 -> PlayerTargetPrimaries.DisplayP3
        IosTargetPrimaries.Bt2020 -> PlayerTargetPrimaries.Bt2020
    }

private fun IosTargetTransfer.toDesktopTargetTransfer(): PlayerTargetTransfer =
    when (this) {
        IosTargetTransfer.Auto -> PlayerTargetTransfer.Auto
        IosTargetTransfer.Srgb -> PlayerTargetTransfer.Srgb
        IosTargetTransfer.Bt1886 -> PlayerTargetTransfer.Bt1886
        IosTargetTransfer.Gamma22 -> PlayerTargetTransfer.Gamma22
        IosTargetTransfer.Gamma24 -> PlayerTargetTransfer.Gamma24
        IosTargetTransfer.Pq -> PlayerTargetTransfer.Pq
        IosTargetTransfer.Hlg -> PlayerTargetTransfer.Hlg
    }

private fun Int.coerceVideoEq(): Int = coerceIn(-100, 100)
