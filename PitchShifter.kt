package com.belawal.voicechanger

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pitch shifter for converting a male voice into a clear female-sounding voice,
 * without changing the length/duration of the recording.
 *
 * Technique (same family used by SoundTouch / most "voice changer" apps):
 *   1. Resample the signal by [pitchFactor] -> shifts pitch AND changes duration.
 *   2. Time-stretch the result back with WSOLA (Waveform-Similarity Overlap-Add)
 *      -> restores the original duration WITHOUT undoing the pitch shift.
 *
 * WSOLA searches for the best-matching splice point each frame (via normalized
 * cross-correlation) instead of a fixed overlap-add, which is what keeps the
 * result clear/natural instead of robotic or "wobbly".
 */
object PitchShifter {

    /** Recommended pitch factor for a clear male -> female conversion. */
    const val MALE_TO_FEMALE_PITCH = 1.45

    fun shiftPitch(input: ShortArray, pitchFactor: Double = MALE_TO_FEMALE_PITCH): ShortArray {
        if (input.isEmpty()) return input

        val floatIn = DoubleArray(input.size) { input[it].toDouble() }

        // Step 1: resample -> changes pitch and duration together
        val resampled = resample(floatIn, pitchFactor)

        // Step 2: WSOLA time-stretch back to the original duration
        val stretched = wsola(resampled, pitchFactor)

        return ShortArray(stretched.size) { i ->
            stretched[i].roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun resample(input: DoubleArray, ratio: Double): DoubleArray {
        val outLen = max(1, (input.size / ratio).toInt())
        val output = DoubleArray(outLen)
        for (i in 0 until outLen) {
            val srcPos = i * ratio
            val idx0 = srcPos.toInt()
            if (idx0 >= input.size - 1) {
                output[i] = input[input.size - 1]
                continue
            }
            val idx1 = idx0 + 1
            val frac = srcPos - idx0
            output[i] = input[idx0] * (1 - frac) + input[idx1] * frac
        }
        return output
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
