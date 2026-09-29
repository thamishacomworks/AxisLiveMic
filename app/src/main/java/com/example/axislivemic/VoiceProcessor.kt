package com.example.axislivemic

import android.util.Log
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/*
 * Brings phone mic speech up to a paging-like level
 * before it is encoded for the speaker:
 *
 *  - 100 Hz high-pass removes rumble / handling noise
 *  - background noise level is learned continuously
 *  - automatic gain only adapts while someone speaks
 *  - noise gate fades the output down between words,
 *    so the amplified hiss is not heard
 *  - soft limiter so loud words never hard-clip
 *
 * Works on 16-bit little-endian mono PCM.
 * One instance per mic session.
 */
class VoiceProcessor(
    sampleRate: Int
) {

    /* ---------- automatic gain ---------- */

    private val targetPeak = 0.75 * 32767.0
    private val limiterThreshold = 0.85 * 32767.0

    private val minGain = 1.0
    private val maxGain = 16.0

    private val peakRelease = coefficient(0.300, sampleRate)
    private val gainAttack = coefficient(0.005, sampleRate)
    private val gainRelease = coefficient(0.600, sampleRate)

    private var peakEnvelope = 0.0
    private var gain = 4.0

    /* ---------- speech / noise detection ---------- */

    /* Speech must be this much above the noise floor (+8 dB). */
    private val speechRatio = 2.5

    /* Never treat anything below -55 dBFS as speech. */
    private val absoluteMinimum = 0.0018 * 32767.0

    /* Noise estimate limits: -65 .. -28 dBFS. */
    private val noiseMin = 0.00056 * 32767.0
    private val noiseMax = 0.040 * 32767.0

    private val levelSmoothing = coefficient(0.010, sampleRate)
    private val noiseFall = coefficient(0.050, sampleRate)
    private val noiseRisePerSample = 10.0.pow(4.0 / 20.0 / sampleRate)

    private var levelSquared = 0.0
    private var noiseLevel = 0.010 * 32767.0

    /* ---------- noise gate ---------- */

    /* Output level between words: -30 dB. */
    private val gateFloor = 0.0316

    private val gateOpen = coefficient(0.003, sampleRate)
    private val gateClose = coefficient(0.150, sampleRate)
    private val gateHoldSamples = (0.250 * sampleRate).toInt()

    private var gateGain = gateFloor
    private var holdCounter = 0

    /* ---------- 100 Hz high-pass (biquad) ---------- */

    private val hpB0: Double
    private val hpB1: Double
    private val hpB2: Double
    private val hpA1: Double
    private val hpA2: Double

    private var hpX1 = 0.0
    private var hpX2 = 0.0
    private var hpY1 = 0.0
    private var hpY2 = 0.0

    init {
        val w0 = 2.0 * PI * 100.0 / sampleRate
        val alpha = sin(w0) / (2.0 * 0.7071)
        val cosW0 = cos(w0)
        val a0 = 1.0 + alpha

        hpB0 = ((1.0 + cosW0) / 2.0) / a0
        hpB1 = (-(1.0 + cosW0)) / a0
        hpB2 = ((1.0 + cosW0) / 2.0) / a0
        hpA1 = (-2.0 * cosW0) / a0
        hpA2 = (1.0 - alpha) / a0
    }

    /* ---------- level meter (AXIS_LEVEL log) ---------- */

    private val meterWindow = sampleRate
    private var meterCount = 0
    private var inPeak = 0.0
    private var outPeak = 0.0
    private var inSumSquares = 0.0
    private var outSumSquares = 0.0
    private var gainSum = 0.0
    private var openSamples = 0

    fun process(pcm: ByteArray): ByteArray {

        val output = ByteArray(pcm.size)

        var i = 0

        while (i + 1 < pcm.size) {

            val low = pcm[i].toInt() and 0xFF
            val high = pcm[i + 1].toInt()
            val raw = ((high shl 8) or low).toShort().toDouble()

            val sample = highPass(raw)

            val speaking = detectSpeech(sample)

            val level = abs(sample)

            peakEnvelope =
                if (level > peakEnvelope) level
                else peakEnvelope * peakRelease

            if (speaking) {

                val desired =
                    (targetPeak / max(peakEnvelope, 1.0))
                        .coerceIn(minGain, maxGain)

                val coef =
                    if (desired < gain) gainAttack
                    else gainRelease

                gain = desired + (gain - desired) * coef
            }

            updateGate(speaking)

            val out =
                softLimit(sample * gain * gateGain)
                    .toInt()
                    .coerceIn(-32768, 32767)

            output[i] = (out and 0xFF).toByte()
            output[i + 1] = ((out shr 8) and 0xFF).toByte()

            meter(raw, out.toDouble(), speaking)

            i += 2
        }

        return output
    }

    private fun highPass(x: Double): Double {

        val y =
            hpB0 * x + hpB1 * hpX1 + hpB2 * hpX2 -
                    hpA1 * hpY1 - hpA2 * hpY2

        hpX2 = hpX1
        hpX1 = x
        hpY2 = hpY1
        hpY1 = y

        return y
    }

    /*
     * Short-term level (10 ms) is compared against a
     * running minimum that follows the room noise:
     * it drops quickly to quiet moments and creeps up
     * slowly (4 dB/s), so speech never becomes "noise".
     */
    private fun detectSpeech(sample: Double): Boolean {

        levelSquared =
            sample * sample +
                    (levelSquared - sample * sample) * levelSmoothing

        val level = sqrt(levelSquared)

        noiseLevel =
            if (level < noiseLevel) {
                level + (noiseLevel - level) * noiseFall
            } else {
                noiseLevel * noiseRisePerSample
            }
                .coerceIn(noiseMin, noiseMax)

        return level > noiseLevel * speechRatio &&
                level > absoluteMinimum
    }

    private fun updateGate(speaking: Boolean) {

        if (speaking) {
            holdCounter = gateHoldSamples
        } else if (holdCounter > 0) {
            holdCounter--
        }

        val target =
            if (holdCounter > 0) 1.0
            else gateFloor

        val coef =
            if (target > gateGain) gateOpen
            else gateClose

        gateGain = target + (gateGain - target) * coef
    }

    /*
     * Logs once per second (tag AXIS_LEVEL), in dBFS:
     * 0 = digital full scale, -12 ~ TTS loudness,
     * below -30 = quiet.
     */
    private fun meter(input: Double, output: Double, speaking: Boolean) {

        inPeak = max(inPeak, abs(input))
        outPeak = max(outPeak, abs(output))
        inSumSquares += input * input
        outSumSquares += output * output
        gainSum += gain
        if (speaking || holdCounter > 0) openSamples++
        meterCount++

        if (meterCount < meterWindow) return

        val inRms = sqrt(inSumSquares / meterCount)
        val outRms = sqrt(outSumSquares / meterCount)

        Log.d(
            "AXIS_LEVEL",
            "IN peak=${dbfs(inPeak)} rms=${dbfs(inRms)} | " +
                    "OUT peak=${dbfs(outPeak)} rms=${dbfs(outRms)} | " +
                    "avgGain=${"%.1f".format(gainSum / meterCount)}x | " +
                    "noise=${dbfs(noiseLevel)} | " +
                    "gateOpen=${100 * openSamples / meterCount}%"
        )

        meterCount = 0
        inPeak = 0.0
        outPeak = 0.0
        inSumSquares = 0.0
        outSumSquares = 0.0
        gainSum = 0.0
        openSamples = 0
    }

    private fun dbfs(value: Double): String =
        if (value < 1.0) "-inf"
        else "%.1f".format(20.0 * log10(value / 32767.0))

    private fun softLimit(value: Double): Double {

        val magnitude = abs(value)

        if (magnitude <= limiterThreshold) {
            return value
        }

        val headroom = 32767.0 - limiterThreshold

        val limited =
            limiterThreshold +
                    headroom *
                    tanh((magnitude - limiterThreshold) / headroom)

        return if (value < 0) -limited else limited
    }

    private fun coefficient(
        seconds: Double,
        sampleRate: Int
    ): Double =
        exp(-1.0 / (seconds * sampleRate))
}
