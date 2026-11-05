/*
 * Auralys Extended (C) 2026 — based on Metrolist (GPL-3.0)
 * Licensed under GPL-3.0
 */
package com.metrolist.music.eq.audio

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import timber.log.Timber
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.pow

/**
 * Bass boost + stereo virtualizer implemented as an ExoPlayer AudioProcessor.
 *
 * Bass boost: state-variable low-shelf boost around a tunable frequency
 * (default 100 Hz) with a bell-shaped gain curve up to +15 dB.
 *
 * Virtualizer: classic Haas-effect style widening — small inter-channel delay
 * plus cross-mixing of an out-of-phase component. Derived stereo widening
 * (M/S based) is blended with the original signal by [strength] (0..1000,
 * matching android.media.audiofx.Virtualizer semantics).
 */
@UnstableApi
class BassVirtualizerAudioProcessor : BaseAudioProcessor() {
    private var sampleRate = 0
    private var channelCount = 0

    // Bass boost state
    private var bassEnabled = false
    private var bassStrength = 0 // 0..1000
    private var bassCutoffHz = 100f

    // Virtualizer state
    private var virtEnabled = false
    private var virtStrength = 0 // 0..1000

    // Bass filter state (2nd-order low-shelf, per channel)
    private var bassLX1 = 0.0; private var bassLX2 = 0.0
    private var bassLY1 = 0.0; private var bassLY2 = 0.0
    private var bassRX1 = 0.0; private var bassRX2 = 0.0
    private var bassRY1 = 0.0; private var bassRY2 = 0.0
    private var bassB0 = 1.0; private var bassB1 = 0.0; private var bassB2 = 0.0
    private var bassA0 = 1.0; private var bassA1 = 0.0; private var bassA2 = 0.0

    // Virtualizer state
    private var haasDelaySamples = 0
    private var delayBufL = DoubleArray(0)
    private var delayBufR = DoubleArray(0)
    private var delayPos = 0

    @Synchronized
    fun setBassBoost(enabled: Boolean, strength: Int) {
        bassEnabled = enabled && strength > 0
        bassStrength = strength.coerceIn(0, 1000)
        if (sampleRate > 0) updateBassCoefficients()
        Timber.tag(TAG).d("BassBoost enabled=%s strength=%d", bassEnabled, bassStrength)
    }

    @Synchronized
    fun setVirtualizer(enabled: Boolean, strength: Int) {
        virtEnabled = enabled && strength > 0
        virtStrength = strength.coerceIn(0, 1000)
        if (sampleRate > 0) updateVirtDelay()
        Timber.tag(TAG).d("Virtualizer enabled=%s strength=%d", virtEnabled, virtStrength)
    }

    @Synchronized
    fun isBassActive(): Boolean = bassEnabled

    @Synchronized
    fun isVirtualizerActive(): Boolean = virtEnabled

    private fun updateBassCoefficients() {
        // Gain: up to +12 dB at max strength, scaled logarithmically for a natural curve
        val gainDb = 12.0 * (bassStrength / 1000.0)
        // Cutoff rises slightly with strength so the boost stays audible but not boomy
        val fc = (bassCutoffHz * (1.0 + 0.6 * bassStrength / 1000.0)).coerceAtMost(sampleRate / 4.0)
        val q = 0.707
        val a = 10.0.pow(gainDb / 40.0)
        val omega = 2.0 * PI * fc / sampleRate
        val sinOmega = sin(omega)
        val cosOmega = cos(omega)
        val alpha = sinOmega / (2.0 * q)

        bassB0 = a * ((a + 1.0) - (a - 1.0) * cosOmega + 2.0 * sqrt(a) * alpha)
        bassB1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosOmega)
        bassB2 = a * ((a + 1.0) - (a - 1.0) * cosOmega - 2.0 * sqrt(a) * alpha)
        bassA0 = (a + 1.0) + (a - 1.0) * cosOmega + 2.0 * sqrt(a) * alpha
        bassA1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosOmega)
        bassA2 = (a + 1.0) + (a - 1.0) * cosOmega - 2.0 * sqrt(a) * alpha

        bassB0 /= bassA0; bassB1 /= bassA0; bassB2 /= bassA0
        bassA1 /= bassA0; bassA2 /= bassA0
        resetFilterState()
    }

    private fun updateVirtDelay() {
        // Haas delay 0.2–1.1 ms scaled by strength
        val delayMs = 0.2 + 0.9 * (virtStrength / 1000.0)
        haasDelaySamples = (delayMs * sampleRate / 1000.0).toInt().coerceAtLeast(1)
        delayBufL = DoubleArray(haasDelaySamples)
        delayBufR = DoubleArray(haasDelaySamples)
        delayPos = 0
    }

    private fun resetFilterState() {
        bassLX1 = 0.0; bassLX2 = 0.0; bassLY1 = 0.0; bassLY2 = 0.0
        bassRX1 = 0.0; bassRX2 = 0.0; bassRY1 = 0.0; bassRY2 = 0.0
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        updateBassCoefficients()
        updateVirtDelay()
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val output = replaceOutputBuffer(inputBuffer.remaining())
        val process =
            (bassEnabled || virtEnabled) &&
                (channelCount == 1 || channelCount == 2)
        if (!process) {
            output.put(inputBuffer)
        } else {
            processBuffer(inputBuffer, output)
        }
        output.flip()
    }

    private fun processBuffer(input: ByteBuffer, output: ByteBuffer) {
        val bassGain = if (bassEnabled) bassStrength / 1000.0 else 0.0
        val widen = if (virtEnabled) virtStrength / 1000.0 else 0.0
        val delayL = delayBufL
        val delayR = delayBufR

        repeat(input.remaining() / 2 / channelCount) {
            when (channelCount) {
                1 -> {
                    var s = input.getShort().toDouble() / 32768.0
                    if (bassEnabled) s = bassFilterMono(s)
                    output.putShort(clamp(s))
                }
                2 -> {
                    var left = input.getShort().toDouble() / 32768.0
                    var right = input.getShort().toDouble() / 32768.0

                    // 1) Virtualizer (before bass so bass stays centred)
                    if (virtEnabled) {
                        val mid = (left + right) / 2.0
                        val side = left - right
                        // Widen: boost side signal, cross-feed delayed opposite channel
                        val dL = delayL[delayPos]
                        val dR = delayR[delayPos]
                        delayL[delayPos] = right
                        delayR[delayPos] = left
                        delayPos = (delayPos + 1) % haasDelaySamples
                        left = mid + side * (1.0 + 0.7 * widen) + dR * 0.25 * widen
                        right = mid - side * (1.0 + 0.7 * widen) + dL * 0.25 * widen
                    }

                    // 2) Bass boost
                    if (bassEnabled) {
                        left = bassFilterL(left)
                        right = bassFilterR(right)
                    }

                    output.putShort(clamp(left))
                    output.putShort(clamp(right))
                }
            }
        }
    }

    private fun bassFilterMono(x: Double): Double {
        val y = bassB0 * x + bassB1 * bassLX1 + bassB2 * bassLX2 -
            bassA1 * bassLY1 - bassA2 * bassLY2
        bassLX2 = bassLX1; bassLX1 = x
        bassLY2 = bassLY1; bassLY1 = y
        // parallel-mix the boosted band for a gentler effect
        return x + (y - x) * (bassStrength / 2000.0)
    }

    private fun bassFilterL(x: Double): Double {
        val y = bassB0 * x + bassB1 * bassLX1 + bassB2 * bassLX2 -
            bassA1 * bassLY1 - bassA2 * bassLY2
        bassLX2 = bassLX1; bassLX1 = x
        bassLY2 = bassLY1; bassLY1 = y
        return x + (y - x) * (bassStrength / 2000.0)
    }

    private fun bassFilterR(x: Double): Double {
        val y = bassB0 * x + bassB1 * bassRX1 + bassB2 * bassRX2 -
            bassA1 * bassRY1 - bassA2 * bassRY2
        bassRX2 = bassRX1; bassRX1 = x
        bassRY2 = bassRY1; bassRY1 = y
        return x + (y - x) * (bassStrength / 2000.0)
    }

    private fun clamp(v: Double): Short =
        (v * 32768.0).coerceIn(-32768.0, 32767.0).toInt().toShort()

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        resetFilterState()
        delayBufL.fill(0.0)
        delayBufR.fill(0.0)
    }

    override fun onReset() {
        sampleRate = 0
        channelCount = 0
        resetFilterState()
        delayBufL = DoubleArray(0)
        delayBufR = DoubleArray(0)
    }

    companion object {
        private const val TAG = "BassVirtualizer"
    }
}
