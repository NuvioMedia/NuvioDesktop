/*
 * Ported from NuvioDeck (https://github.com/kllakm/NuvioDeck), GPLv3.
 */
package com.nuvio.app.controller.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.FloatControl
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Desktop (javax.sound) implementation of the navigation sounds.
 *
 * Clips are loaded asynchronously so app startup never blocks on audio initialisation. When the
 * bundled WAV files are unavailable (e.g. an unpackaged run), small PCM variants are synthesised.
 */
internal object DesktopControllerSound : ControllerSoundPlayer.Backend {
    private val scope = CoroutineScope(Dispatchers.Default)

    @Volatile
    private var navigateClip: Clip? = null

    @Volatile
    private var selectClip: Clip? = null

    @Volatile
    private var backClip: Clip? = null

    @Volatile
    private var lastNavigateTime = 0L
    private const val NAVIGATE_DEBOUNCE_MS = 60L

    fun install() {
        ControllerSoundPlayer.backend = this
        scope.launch { initClips() }
    }

    fun uninstall() {
        if (ControllerSoundPlayer.backend === this) ControllerSoundPlayer.backend = null
    }

    private fun initClips() {
        runCatching {
            navigateClip = loadClip("/controller/audio/navigate.wav") ?: synthesizeNavigateClip()
            selectClip = loadClip("/controller/audio/select.wav") ?: synthesizeSelectClip()
            backClip = loadClip("/controller/audio/back.wav") ?: synthesizeBackClip()
        }
    }

    private fun loadClip(resourcePath: String): Clip? = runCatching {
        val stream = javaClass.getResourceAsStream(resourcePath) ?: return@runCatching null
        val audioStream = AudioSystem.getAudioInputStream(BufferedInputStream(stream))
        val clip = AudioSystem.getClip()
        clip.open(audioStream)
        tuneVolume(clip, -15.0f)
        clip
    }.getOrNull()

    private fun tuneVolume(clip: Clip, targetGainDb: Float) {
        runCatching {
            if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                val gain = clip.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
                gain.value = targetGainDb.coerceIn(gain.minimum, gain.maximum)
            }
        }
    }

    private fun play(clip: Clip?) {
        if (clip == null) return
        runCatching {
            if (clip.isRunning) clip.stop()
            clip.framePosition = 0
            clip.start()
        }
    }

    override fun playNavigate() {
        if (!ControllerSoundSettings.isEnabled()) return
        val now = System.currentTimeMillis()
        if (now - lastNavigateTime < NAVIGATE_DEBOUNCE_MS) return
        lastNavigateTime = now
        play(navigateClip)
    }

    override fun playSelect() {
        if (!ControllerSoundSettings.isEnabled()) return
        play(selectClip)
    }

    override fun playBack() {
        if (!ControllerSoundSettings.isEnabled()) return
        play(backClip)
    }

    // Fallback synthesizers (16-bit 44.1 kHz mono PCM), used when the WAV resources are missing.
    private fun synthesizeNavigateClip(): Clip? = runCatching {
        val sampleRate = 44100f
        val numSamples = (0.032 * sampleRate).toInt()
        val buffer = ByteArray(numSamples * 2)
        for (i in 0 until numSamples) {
            val t = i / sampleRate.toDouble()
            val click = sin(2.0 * PI * 1800.0 * t) * exp(-t / 0.005) * 0.12
            val thud = sin(2.0 * PI * 130.0 * t) * exp(-t / 0.012) * 0.14
            writeSample(buffer, i, (click + thud) * 32767.0)
        }
        createClipFromPcm(buffer, sampleRate)
    }.getOrNull()

    private fun synthesizeSelectClip(): Clip? = runCatching {
        val sampleRate = 44100f
        val numSamples = (0.090 * sampleRate).toInt()
        val buffer = ByteArray(numSamples * 2)
        var phase1 = 0.0
        var phase2 = 0.0
        for (i in 0 until numSamples) {
            val t = i / sampleRate.toDouble()
            val env = min(1.0, t / 0.006) * exp(-t / 0.035)
            val freq1 = 440.0 + (520.0 * min(1.0, t / 0.050))
            val freq2 = freq1 * 1.5
            phase1 += 2.0 * PI * freq1 / sampleRate
            phase2 += 2.0 * PI * freq2 / sampleRate
            writeSample(buffer, i, (sin(phase1) * 0.18 + sin(phase2) * 0.06) * env * 32767.0)
        }
        createClipFromPcm(buffer, sampleRate)
    }.getOrNull()

    private fun synthesizeBackClip(): Clip? = runCatching {
        val sampleRate = 44100f
        val numSamples = (0.080 * sampleRate).toInt()
        val buffer = ByteArray(numSamples * 2)
        var phase = 0.0
        for (i in 0 until numSamples) {
            val t = i / sampleRate.toDouble()
            val env = min(1.0, t / 0.005) * exp(-t / 0.028)
            val freq = 580.0 - (290.0 * min(1.0, t / 0.060))
            phase += 2.0 * PI * freq / sampleRate
            writeSample(buffer, i, sin(phase) * 0.17 * env * 32767.0)
        }
        createClipFromPcm(buffer, sampleRate)
    }.getOrNull()

    private fun writeSample(buffer: ByteArray, index: Int, value: Double) {
        val sample = value.roundToInt().coerceIn(-32767, 32767)
        buffer[index * 2] = (sample and 0xFF).toByte()
        buffer[index * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
    }

    private fun createClipFromPcm(pcmData: ByteArray, sampleRate: Float): Clip {
        val format = AudioFormat(sampleRate, 16, 1, true, false)
        val audioStream = AudioInputStream(ByteArrayInputStream(pcmData), format, pcmData.size.toLong() / 2)
        val clip = AudioSystem.getClip()
        clip.open(audioStream)
        tuneVolume(clip, -15.0f)
        return clip
    }
}
