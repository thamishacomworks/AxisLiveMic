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

/*
 * Brings phone mic speech up to a paging-like level
 * before it is encoded for the speaker:
 *
 *   100 Hz high-pass        rumble / handling noise
 *   spectral noise reduction steady hiss, also while speaking
 *   high-shelf -6 dB @ 5 kHz "sss" band (16 kHz only)
 *   loudness auto gain       speech held near -15 dBFS RMS
 *   noise gate               silence between words
 *   peak limiter             loud words never clip
 *
 * Also reports mic overload (input clipping) so the UI
 * can ask the user to move the mic back.
 *
 * Works on 16-bit little-endian mono PCM.
 * One instance per mic session.
 */
class VoiceProcessor(
    private val sampleRate: Int,
    private val onOverloadChanged: ((Boolean) -> Unit)? = null
) {

    /* ---------- filters ---------- */

    private val highPass = Biquad.highPass(sampleRate, 100.0)

    private val highShelf: Biquad? =
        if (sampleRate >= 16000) Biquad.highShelf(sampleRate, 5000.0, -9.0)
        else null

    private val noiseReducer = SpectralNoiseReducer(sampleRate)

    /* ---------- speech / noise detection ---------- */

    /* Speech must be this much above the noise floor (+8 dB). */
    private val speechRatio = 2.5

    /* Never treat anything below -55 dBFS as speech. */
    private val absoluteMinimum = 0.0018 * 32767.0

    /* Noise estimate limits: -65 .. -28 dBFS. */
    private val noiseMin = 0.00056 * 32767.0
    private val noiseMax = 0.040 * 32767.0

    private val levelSmoothing = coefficient(0.010)
    private val noiseFall = coefficient(0.050)
    private val noiseRisePerSample = 10.0.pow(4.0 / 20.0 / sampleRate)

    private var levelSquared = 0.0
    private var noiseLevel = 0.010 * 32767.0

    /*
     * The noise reducer delays audio by its frame size.
     * The gate uses the undelayed flag (opens slightly
     * early), the auto gain uses the aligned one.
     */
    private val speechDelay = BooleanArray(noiseReducer.latency)
    private var speechDelayIndex = 0

    /* ---------- loudness auto gain ---------- */

    /* Speech loudness target: -15 dBFS RMS. */
    private val targetRms = 0.178 * 32767.0

    private val minGain = 1.0
    private val maxGain = 20.0

    private val loudnessSmoothing = coefficient(0.100)
    private val gainDown = coefficient(0.120)
    private val gainUp = coefficient(0.250)

    private var loudnessSquared = 0.0
    private var gain = 6.0

    /* ---------- noise gate ---------- */

    /* Output level between words: -30 dB. */
    private val gateFloor = 0.0316

    private val gateOpen = coefficient(0.003)
    private val gateClose = coefficient(0.150)
    private val gateHoldSamples = (0.250 * sampleRate).toInt()

    private var gateGain = gateFloor
    private var holdCounter = 0

    /* ---------- peak limiter ---------- */

    private val limiterThreshold = 0.89 * 32767.0
    private val limiterRelease = coefficient(0.080)
    private var limiterEnvelope = 0.0

    /* ---------- mic overload ---------- */

    private val clipLevel = 32000
    private val overloadWindow = sampleRate / 4
    private val overloadMinClips = max(2, sampleRate / 4000)
    private val overloadClearSamples = (1.5 * sampleRate).toInt()
    private val gainFreezeSamples = (0.300 * sampleRate).toInt()

    private var windowCount = 0
    private var windowClips = 0
    private var samplesSinceClip = Int.MAX_VALUE / 2
    private var samplesSinceOverload = Int.MAX_VALUE / 2
    private var overloaded = false

    /* ---------- level meter (AXIS_LEVEL log) ---------- */

    private val meterWindow = sampleRate
    private var meterCount = 0
    private var inPeak = 0.0
    private var outPeak = 0.0
    private var inSumSquares = 0.0
    private var outSumSquares = 0.0
    private var gainSum = 0.0
    private var openSamples = 0
    private var meterClips = 0

    fun process(pcm: ByteArray): ByteArray {

        val output = ByteArray(pcm.size)

        var i = 0

        while (i + 1 < pcm.size) {

            val low = pcm[i].toInt() and 0xFF
            val high = pcm[i + 1].toInt()
            val raw = ((high shl 8) or low).toShort().toInt()

            trackOverload(raw)

            val filtered = highPass.process(raw.toDouble())

            val speaking = detectSpeech(filtered)

            val alignedSpeaking = speechDelay[speechDelayIndex]
            speechDelay[speechDelayIndex] = speaking
            speechDelayIndex = (speechDelayIndex + 1) % speechDelay.size

            var voice = noiseReducer.process(filtered, speaking)
            voice = highShelf?.process(voice) ?: voice

            updateGain(voice, alignedSpeaking)
            updateGate(speaking)

            val out =
                limit(voice * gain * gateGain)
                    .toInt()
                    .coerceIn(-32768, 32767)

            output[i] = (out and 0xFF).toByte()
            output[i + 1] = ((out shr 8) and 0xFF).toByte()

            meter(filtered, out.toDouble(), speaking)

            i += 2
        }

        return output
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

    /*
     * Gain follows speech loudness (100 ms RMS), not
     * single peaks, so taps / pops don't pull it down;
     * the limiter handles those. Frozen right after the
     * mic clips.
     */
    private fun updateGain(sample: Double, speaking: Boolean) {

        loudnessSquared =
            sample * sample +
                    (loudnessSquared - sample * sample) * loudnessSmoothing

        if (!speaking || samplesSinceClip < gainFreezeSamples) {
            return
        }

        val desired =
            (targetRms / max(sqrt(loudnessSquared), 1.0))
                .coerceIn(minGain, maxGain)

        val coef =
            if (desired < gain) gainDown
            else gainUp

        gain = desired + (gain - desired) * coef
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

    private fun limit(value: Double): Double {

        val magnitude = abs(value)

        limiterEnvelope =
            if (magnitude > limiterEnvelope) magnitude
            else limiterEnvelope * limiterRelease

        return if (limiterEnvelope > limiterThreshold) {
            value * limiterThreshold / limiterEnvelope
        } else {
            value
        }
    }

    private fun trackOverload(raw: Int) {

        if (abs(raw) >= clipLevel) {
            windowClips++
            meterClips++
            samplesSinceClip = 0
        } else {
            samplesSinceClip++
        }

        samplesSinceOverload++
        windowCount++

        if (windowCount < overloadWindow) return

        if (windowClips >= overloadMinClips) {

            samplesSinceOverload = 0

            if (!overloaded) {
                overloaded = true
                Log.w("AXIS_LEVEL", "MIC OVERLOAD - input clipping")
                onOverloadChanged?.invoke(true)
            }

        } else if (overloaded && samplesSinceOverload > overloadClearSamples) {
            overloaded = false
            onOverloadChanged?.invoke(false)
        }

        windowCount = 0
        windowClips = 0
    }

    /*
     * Logs once per second (tag AXIS_LEVEL), in dBFS:
     * 0 = digital full scale, -12 ~ TTS loudness,
     * below -30 = quiet. IN is measured after the
     * 100 Hz high-pass. OUT rms includes pauses.
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
                    "nr=${"%.1f".format(noiseReducer.takeAverageReductionDb())}dB | " +
                    "gateOpen=${100 * openSamples / meterCount}% | " +
                    "clips=$meterClips"
        )

        meterCount = 0
        inPeak = 0.0
        outPeak = 0.0
        inSumSquares = 0.0
        outSumSquares = 0.0
        gainSum = 0.0
        openSamples = 0
        meterClips = 0
    }

    private fun dbfs(value: Double): String =
        if (value < 1.0) "-inf"
        else "%.1f".format(20.0 * log10(value / 32767.0))

    private fun coefficient(seconds: Double): Double =
        exp(-1.0 / (seconds * sampleRate))
}

/*
 * Standard RBJ biquad, direct form I.
 */
private class Biquad(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double
) {

    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun process(x: Double): Double {

        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2

        x2 = x1
        x1 = x
        y2 = y1
        y1 = y

        return y
    }

    companion object {

        fun highPass(sampleRate: Int, frequency: Double): Biquad {

            val w0 = 2.0 * PI * frequency / sampleRate
            val alpha = sin(w0) / (2.0 * 0.7071)
            val cosW0 = cos(w0)
            val a0 = 1.0 + alpha

            return Biquad(
                b0 = ((1.0 + cosW0) / 2.0) / a0,
                b1 = (-(1.0 + cosW0)) / a0,
                b2 = ((1.0 + cosW0) / 2.0) / a0,
                a1 = (-2.0 * cosW0) / a0,
                a2 = (1.0 - alpha) / a0
            )
        }

        fun highShelf(sampleRate: Int, frequency: Double, gainDb: Double): Biquad {

            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * frequency / sampleRate
            val cosW0 = cos(w0)
            val alpha = sin(w0) / 2.0 * sqrt(2.0)
            val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha

            val a0 = (a + 1) - (a - 1) * cosW0 + twoSqrtAAlpha

            return Biquad(
                b0 = a * ((a + 1) + (a - 1) * cosW0 + twoSqrtAAlpha) / a0,
                b1 = -2.0 * a * ((a - 1) + (a + 1) * cosW0) / a0,
                b2 = a * ((a + 1) + (a - 1) * cosW0 - twoSqrtAAlpha) / a0,
                a1 = 2.0 * ((a - 1) - (a + 1) * cosW0) / a0,
                a2 = ((a + 1) - (a - 1) * cosW0 - twoSqrtAAlpha) / a0
            )
        }
    }
}

/*
 * STFT noise reduction (sqrt-Hann, 50% overlap) with a
 * decision-directed Wiener gain per frequency bin.
 *
 * The noise spectrum is learned while nobody speaks.
 * Kept moderate on purpose: bins are never attenuated
 * more than gainFloor and gains are smoothed over time
 * and frequency, which avoids watery / robotic
 * "musical noise".
 */
private class SpectralNoiseReducer(sampleRate: Int) {

    private val size = if (sampleRate >= 16000) 512 else 256
    private val hop = size / 2
    private val bins = size / 2 + 1

    /* Strongest reduction per bin: -14 dB. */
    private val gainFloor = 0.20

    /* Decision-directed smoothing (higher = smoother). */
    private val ddAlpha = 0.98

    private val minPriorSnr = 10.0.pow(-25.0 / 10.0)

    private val noiseLearnRate = 0.08
    private val noiseDriftDown = 0.05
    private val initFrames = 8

    val latency = size

    private val window =
        DoubleArray(size) { sqrt(0.5 * (1.0 - cos(2.0 * PI * it / size))) }

    private val fft = Fft(size)

    private val inputFrame = DoubleArray(size)
    private val overlap = DoubleArray(size)
    private val ready = DoubleArray(hop)

    private val re = DoubleArray(size)
    private val im = DoubleArray(size)

    private val noisePower = DoubleArray(bins)
    private val previousGain = DoubleArray(bins) { 1.0 }
    private val previousSnr = DoubleArray(bins) { 1.0 }
    private val rawGain = DoubleArray(bins)
    private val smoothGain = DoubleArray(bins)

    private var position = 0
    private var frameHasSpeech = false
    private var framesSeen = 0

    private var reductionSum = 0.0
    private var reductionFrames = 0

    fun process(sample: Double, speaking: Boolean): Double {

        inputFrame[size - hop + position] = sample
        val out = ready[position]

        if (speaking) frameHasSpeech = true

        position++

        if (position == hop) {
            processFrame()
            position = 0
            frameHasSpeech = false
        }

        return out
    }

    fun takeAverageReductionDb(): Double {

        val average =
            if (reductionFrames == 0) 1.0
            else reductionSum / reductionFrames

        reductionSum = 0.0
        reductionFrames = 0

        return 20.0 * log10(max(average, 1e-6))
    }

    private fun processFrame() {

        for (n in 0 until size) {
            re[n] = inputFrame[n] * window[n]
            im[n] = 0.0
        }

        fft.transform(re, im, inverse = false)

        framesSeen++

        if (framesSeen <= initFrames) {

            for (k in 0 until bins) {
                val power = re[k] * re[k] + im[k] * im[k]
                noisePower[k] += power / initFrames
                smoothGain[k] = 1.0
            }

        } else {

            computeGains()
        }

        var gainTotal = 0.0

        for (k in 0 until bins) {

            val g = smoothGain[k]
            gainTotal += g

            re[k] *= g
            im[k] *= g

            if (k in 1 until size / 2) {
                re[size - k] = re[k]
                im[size - k] = -im[k]
            }
        }

        reductionSum += gainTotal / bins
        reductionFrames++

        fft.transform(re, im, inverse = true)

        for (n in 0 until size) {
            overlap[n] += re[n] * window[n]
        }

        System.arraycopy(overlap, 0, ready, 0, hop)
        System.arraycopy(overlap, hop, overlap, 0, size - hop)
        java.util.Arrays.fill(overlap, size - hop, size, 0.0)

        System.arraycopy(inputFrame, hop, inputFrame, 0, size - hop)
    }

    private fun computeGains() {

        for (k in 0 until bins) {

            val power = re[k] * re[k] + im[k] * im[k]

            if (!frameHasSpeech) {
                noisePower[k] += (power - noisePower[k]) * noiseLearnRate
            } else if (power < noisePower[k]) {
                noisePower[k] += (power - noisePower[k]) * noiseDriftDown
            }

            val noise = max(noisePower[k], 1e-3)
            val postSnr = power / noise

            val priorSnr =
                max(
                    ddAlpha * previousGain[k] * previousGain[k] * previousSnr[k] +
                            (1.0 - ddAlpha) * max(postSnr - 1.0, 0.0),
                    minPriorSnr
                )

            val g = priorSnr / (1.0 + priorSnr)

            previousGain[k] = g
            previousSnr[k] = postSnr
            rawGain[k] = max(g, gainFloor)
        }

        for (k in 0 until bins) {

            val left = rawGain[max(k - 1, 0)]
            val right = rawGain[minOf(k + 1, bins - 1)]

            smoothGain[k] = (left + 2.0 * rawGain[k] + right) / 4.0
        }
    }
}

/*
 * In-place iterative radix-2 complex FFT.
 */
private class Fft(private val n: Int) {

    private val cosTable = DoubleArray(n / 2) { cos(2.0 * PI * it / n) }
    private val sinTable = DoubleArray(n / 2) { sin(2.0 * PI * it / n) }

    private val bitReverse = IntArray(n).also { table ->
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) {
            table[i] = Integer.reverse(i) ushr (32 - bits)
        }
    }

    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {

        for (i in 0 until n) {
            val j = bitReverse[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        val sign = if (inverse) 1.0 else -1.0

        var length = 2

        while (length <= n) {

            val half = length / 2
            val step = n / length

            var start = 0

            while (start < n) {

                for (k in 0 until half) {

                    val wr = cosTable[k * step]
                    val wi = sign * sinTable[k * step]

                    val a = start + k
                    val b = a + half

                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr

                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                }

                start += length
            }

            length *= 2
        }

        if (inverse) {
            for (i in 0 until n) {
                re[i] /= n
                im[i] /= n
            }
        }
    }
}
