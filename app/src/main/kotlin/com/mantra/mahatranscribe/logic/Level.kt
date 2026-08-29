package com.mantra.mahatranscribe.logic

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * LEVELLING, BECAUSE EVERYTHING ARRIVES AT A DIFFERENT LOUDNESS.
 *
 * Two separate problems from the brief, both solved by the same arithmetic and neither optional:
 *
 * **Quiet recordings are misheard in a particular way.** Whisper DROPS short words rather than
 * guessing at them, so the transcript reads fluently and is missing things. There is nothing in
 * the result to notice, which is what makes it expensive. A phone recording of somebody speaking
 * softly across a room arrives quiet and uneven, and that is Baba's normal working condition:
 * dictating while moving.
 *
 * **Audio arrives at wildly different volumes.** Measured 29.5 dB apart between two voices from
 * the same provider. Level everything before it is heard, or every judgement about a performance
 * is really a judgement about loudness.
 *
 * ### Why this is written by hand rather than handed to ffmpeg
 *
 * The Streamlit app used `loudnorm=I=-16:TP=-1.5:LRA=11`, which is EBU R128 and correct. There
 * is no ffmpeg on Android worth carrying: ffmpeg-kit is retired, and pulling a native build for
 * one filter is a large dependency for a small job. So this is a simplified levelling: measure
 * the loudness of what is actually there, work out one gain that would put it at the target, and
 * refuse to apply a gain that would clip.
 *
 * **It is not R128 and this file does not claim to be.** R128 is gated, K-weighted and windowed;
 * this is an ungated RMS over the samples above the noise floor. For the job it does here —
 * getting a quiet dictation up to where a transcriber hears its short words, and getting two
 * voices within a few dB of each other — the difference does not matter. Where it would matter
 * is programme loudness for broadcast, which this app does not do. Written down so that nobody
 * later reads `-16` and believes it is a measured LUFS figure.
 */
object Level {

    /** The target, in dBFS RMS. The streaming convention is -16 LUFS and this is its neighbour. */
    const val TARGET_DB = -16.0

    /** Never let a peak go above this. Leaves headroom so nothing clips on the way out. */
    const val CEILING_DB = -1.5

    /**
     * The most a signal is ever lifted.
     *
     * Without a cap, a take that is silence plus a cough gets 60 dB of gain and becomes a wall of
     * amplified room noise, which is worse for a transcriber than the quiet original. Twenty
     * decibels is a factor of ten and covers real quiet speech.
     */
    const val MAX_GAIN_DB = 20.0

    /** Samples quieter than this are treated as room rather than as speech when measuring. */
    const val FLOOR_DB = -60.0

    /** dBFS of a linear amplitude in 0..1. Silence is [FLOOR_DB], never negative infinity. */
    fun db(amplitude: Double): Double {
        if (amplitude <= 0.0) return FLOOR_DB
        return (20.0 * log10(amplitude)).coerceAtLeast(FLOOR_DB)
    }

    /** Linear amplitude of a dBFS value. The inverse of [db]. */
    fun amp(dbfs: Double): Double = 10.0.pow(dbfs / 20.0)

    /**
     * RMS of the samples that are actually carrying something, in dBFS.
     *
     * Silence is EXCLUDED from the measurement. Including it means a take with long thinking
     * pauses measures quiet and gets lifted for it, so the same voice ends up at two different
     * loudnesses depending on how much it paused. Baba pauses; that is what MULTI mode is for.
     */
    fun speechRms(samples: FloatArray): Double {
        if (samples.isEmpty()) return FLOOR_DB
        val floor = amp(FLOOR_DB)
        var sum = 0.0
        var n = 0L
        for (s in samples) {
            val a = abs(s)
            if (a < floor) continue
            sum += a.toDouble() * a.toDouble()
            n++
        }
        if (n == 0L) return FLOOR_DB
        return db(sqrt(sum / n))
    }

    /** The loudest single sample, in dBFS. */
    fun peak(samples: FloatArray): Double {
        var p = 0.0f
        for (s in samples) { val a = abs(s); if (a > p) p = a }
        return db(p.toDouble())
    }

    /**
     * The gain in dB that should be applied to [samples], already limited three ways.
     *
     * The three limits, in the order they bind:
     *
     *  1. never more than [MAX_GAIN_DB], so silence plus a cough does not become a wall of noise
     *  2. never enough to push the PEAK past [CEILING_DB], so nothing clips
     *  3. never below zero unless the material is genuinely too loud, in which case it is pulled
     *     down, because a clipped input is misheard as surely as a quiet one
     *
     * All-silence returns 0. Lifting silence by 20 dB produces louder silence and a meter that
     * lies about it.
     */
    fun gainDb(samples: FloatArray): Double {
        if (samples.isEmpty()) return 0.0
        val rms = speechRms(samples)
        if (rms <= FLOOR_DB) return 0.0
        val wanted = TARGET_DB - rms
        val headroom = CEILING_DB - peak(samples)
        return wanted.coerceAtMost(MAX_GAIN_DB).coerceAtMost(headroom).let {
            // Pulling down is allowed; pulling down past the target is not.
            if (wanted < 0) wanted.coerceAtLeast(-MAX_GAIN_DB) else it.coerceAtLeast(0.0)
        }
    }

    /**
     * [samples] levelled in place, and the gain that was applied.
     *
     * In place because a take can be minutes long and a second copy of a float array of that size
     * is real memory on the phone this has to run on.
     */
    fun applyInPlace(samples: FloatArray): Double {
        val g = gainDb(samples)
        if (g == 0.0) return 0.0
        val factor = amp(g).toFloat()
        for (i in samples.indices) {
            samples[i] = (samples[i] * factor).coerceIn(-1.0f, 1.0f)
        }
        return g
    }
}
