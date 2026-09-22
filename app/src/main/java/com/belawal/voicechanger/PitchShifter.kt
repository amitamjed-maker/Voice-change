package com.belawal.voicechanger

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pitch shifter for converting a male voice into a clear, natural-sounding
 * female voice, without changing the length/duration of the recording.
 *
 * Technique (same family used by SoundTouch / most "voice changer" apps):
 *   1. Resample the signal by [pitchFactor] with a tiny natural jitter ->
 *      shifts pitch AND changes duration, while avoiding the flat, robotic
 *      "auto-tuned" quality of a fixed-ratio resample.
 *   2. Time-stretch the result back with WSOLA (Waveform-Similarity Overlap-Add)
 *      -> restores the original duration WITHOUT undoing the pitch shift.
 *   3. A light brightness/air EQ pass -> female voices carry more energy in
 *      the upper harmonics, so this step is what keeps the result from
 *      sounding like a simply-sped-up male voice ("real" instead of "helium").
 *
 * WSOLA searches for the best-matching splice point each frame (via normalized
 * cross-correlation) instead of a fixed overlap-add, which is what keeps the
 * result clear instead of robotic or "wobbly".
 */
object PitchShifter {

    /** Recommended pitch factor for a natural-sounding male -> female conversion. */
    const val MALE_TO_FEMALE_PITCH = 1.35

    fun shiftPitch(
        input: ShortArray,
        sampleRate: Int = 44100,
        pitchFactor: Double = MALE_TO_FEMALE_PITCH
    ): ShortArray {
        if (input.isEmpty()) return input

        val floatIn = DoubleArray(input.size) { input[it].toDouble() }

        // Step 1: resample with a subtle natural jitter -> pitch + duration change
        val resampled = resampleWithJitter(floatIn, pitchFactor, sampleRate)

        // Step 2: WSOLA time-stretch back to the original duration
        val stretched = wsola(resampled, pitchFactor)

        // Step 3: brighten the tone so it doesn't sound like a flat pitch-up
        val brightened = brighten(stretched, gain = 0.22)

        return ShortArray(brightened.size) { i ->
            brightened[i].roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /**
     * Resample by [baseRatio], but wobble the instantaneous ratio slightly
     * (a few % at ~5 Hz) so pitch has the tiny natural variation real voices
     * have instead of a dead-flat, obviously-synthetic pitch shift.
     */
    private fun resampleWithJitter(input: DoubleArray, baseRatio: Double, sampleRate: Int): DoubleArray {
        val jitterDepth = 0.015   // +-1.5% wobble
        val jitterFreqHz = 5.0    // natural micro-vibrato rate
        val estOutLen = max(1, (input.size / baseRatio).toInt())
        val output = DoubleArray(estOutLen)

        var srcPos = 0.0
        var outIdx = 0
        var t = 0.0
        val dt = 1.0 / sampleRate

        while (outIdx < estOutLen) {
            val idx0 = srcPos.toInt()
            if (idx0 >= input.size - 1) break
            val idx1 = idx0 + 1
            val frac = srcPos - idx0
            output[outIdx] = input[idx0] * (1 - frac) + input[idx1] * frac

            val instRatio = baseRatio * (1.0 + jitterDepth * sin(2 * PI * jitterFreqHz * t))
            srcPos += instRatio
            t += dt
            outIdx++
        }
        return if (outIdx == output.size) output else output.copyOfRange(0, outIdx)
    }

    /** Cheap high-shelf-style brightness boost: adds back a slice of the high-frequency content. */
    private fun brighten(input: DoubleArray, gain: Double): DoubleArray {
        val out = DoubleArray(input.size)
        var prev = 0.0
        for (i in input.indices) {
            val hp = input[i] - prev
            out[i] = input[i] + gain * hp
            prev = input[i]
        }
        return out
    }

    /** Output length ~= input.size * stretchFactor, pitch of [input] is preserved. */
    private fun wsola(input: DoubleArray, stretchFactor: Double): DoubleArray {
        val frameSize = 1024
        val hopA = frameSize / 4                 // analysis hop
        val hopS = max(1, (hopA * stretchFactor).roundToInt()) // synthesis hop
        val tolerance = hopA / 2                  // search window for best splice

        if (input.size <= frameSize) return input.copyOf()

        val outLen = (input.size * stretchFactor).toInt() + frameSize
        val output = DoubleArray(outLen)
        val weight = DoubleArray(outLen)

        val window = DoubleArray(frameSize) { 0.5 - 0.5 * cos(2 * PI * it / (frameSize - 1)) }

        var posIn = 0
        var posOut = 0
        var prevFrame: DoubleArray? = null
        val overlapLen = frameSize - hopA

        while (posIn + frameSize < input.size) {
            var bestOffset = 0
            val prev = prevFrame
            if (prev != null) {
                var bestScore = Double.NEGATIVE_INFINITY
                val searchStart = max(0, posIn - tolerance)
                val searchEnd = min(input.size - frameSize, posIn + tolerance)
                var s = searchStart
                while (s <= searchEnd) {
                    var score = 0.0
                    var norm = 1e-9
                    var k = 0
                    while (k < overlapLen) {
                        val a = prev[hopA + k]
                        val b = input[s + k]
                        score += a * b
                        norm += b * b
                        k++
                    }
                    val normScore = score / sqrt(norm)
                    if (normScore > bestScore) {
                        bestScore = normScore
                        bestOffset = s - posIn
                    }
                    s++
                }
            }

            val framePos = posIn + bestOffset
            val frame = DoubleArray(frameSize)
            for (k in 0 until frameSize) {
                val srcIdx = framePos + k
                frame[k] = if (srcIdx in input.indices) input[srcIdx] * window[k] else 0.0
            }

            for (k in 0 until frameSize) {
                val outIdx = posOut + k
                if (outIdx < outLen) {
                    output[outIdx] += frame[k]
                    weight[outIdx] += window[k]
                }
            }

            prevFrame = frame
            posIn += hopA
            posOut += hopS
        }

        for (i in output.indices) {
            if (weight[i] > 1e-6) output[i] /= weight[i]
        }

        val finalLen = min(posOut, outLen).coerceAtLeast(0)
        return output.copyOfRange(0, finalLen)
    }
}
