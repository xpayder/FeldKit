package app.feldkit.audio

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Musical key from the spectrum: spectral peaks (interpolated, tuning-corrected) are folded into a 12-bin chromagram, each peak
 * also voting for the notes it could be an overtone of; the chromagram is correlated with major and minor key profiles
 * (Sha'ath's profiles made for electronic music). Prototype and accuracy numbers: tools/key-prototype.py, KeyTest.
 */
object KeyAnalyzer {
    private val NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    /** Camelot wheel: number for the major key by tonic (B = relative minor shares the number with the "A" letter). */
    private val CAMELOT_MAJOR = intArrayOf(8, 3, 10, 5, 12, 7, 2, 9, 4, 11, 6, 1)

    private val MAJOR = doubleArrayOf(7.24, 3.5, 3.5, 3.52, 5.38, 4.8, 3.98, 6.22, 3.4, 3.59, 3.0, 3.8)
    private val MINOR = doubleArrayOf(7.0, 3.14, 4.36, 5.4, 3.54, 4.6, 3.6, 5.88, 3.95, 3.52, 3.84, 3.37)

    class Key(val tonic: Int, val minor: Boolean) {
        val name get() = NAMES[tonic] + if (minor) " minor" else " major"
        /** Camelot code, e.g. "11A" for F# minor. */
        val camelot: String get() = if (minor) "${CAMELOT_MAJOR[(tonic + 3) % 12]}A" else "${CAMELOT_MAJOR[tonic]}B"
        /** The other key with the same notes: relative minor of a major key and the other way round. */
        val relative get() = if (minor) Key((tonic + 3) % 12, false) else Key((tonic + 9) % 12, true)
        override fun equals(other: Any?) = other is Key && other.tonic == tonic && other.minor == minor
        override fun hashCode() = tonic * 2 + if (minor) 1 else 0
    }

    class Result(
        val key: Key,
        /** Correlation of the chromagram with the winning profile, 0..1. */
        val strength: Double,
        /** The next-best reading that is not just the relative key (usually the other mode on the same tonic). */
        val alternative: Key,
        val chroma: DoubleArray,
    ) {
        /** "likely" when the chromagram fits the profile well; otherwise "uncertain". */
        val likely get() = strength >= 0.8
    }

    private const val N = 32768
    private const val LO_HZ = 40.0
    private const val HI_HZ = 2000.0

    /** Returns null for silence or material with no tonal content. */
    fun analyze(samples: FloatArray, rate: Int, maxSeconds: Int = 90): Result? {
        val x = if (samples.size > rate * maxSeconds) samples.copyOf(rate * maxSeconds) else samples
        if (x.size < N) return null
        val fft = Fft(N)
        val win = DoubleArray(N) { 0.5 - 0.5 * cos(2.0 * PI * it / (N - 1)) }
        val re = DoubleArray(N); val im = DoubleArray(N); val mag = DoubleArray(N / 2 + 1)
        val lo = (LO_HZ * N / rate).toInt().coerceAtLeast(2); val hi = minOf((HI_HZ * N / rate).toInt(), N / 2 - 2)
        // pass 1: collect peaks (midi pitch, weight) of every frame
        val framePitch = ArrayList<DoubleArray>(); val frameWeight = ArrayList<DoubleArray>()
        var s = 0
        while (s + N <= x.size) {
            for (i in 0 until N) { re[i] = x[s + i] * win[i]; im[i] = 0.0 }
            fft.transform(re, im)
            var peak = 0.0
            for (i in lo - 1..hi + 1) { mag[i] = sqrt(re[i] * re[i] + im[i] * im[i]); if (mag[i] > peak) peak = mag[i] }
            if (peak > 1e-3) {
                val ps = ArrayList<Double>(); val ws = ArrayList<Double>()
                for (i in lo..hi) {
                    val c = mag[i]
                    if (c > mag[i - 1] && c >= mag[i + 1] && c > peak * 0.02) {
                        val a = ln(mag[i - 1] + 1e-9); val b = ln(c + 1e-9); val g = ln(mag[i + 1] + 1e-9)
                        val d = 0.5 * (a - g) / (a - 2 * b + g)
                        val f = (i + d) * rate / N
                        ps.add(69 + 12 * ln(f / 440.0) / ln(2.0)); ws.add(sqrt(c / peak))
                    }
                }
                if (ps.isNotEmpty()) { framePitch.add(ps.toDoubleArray()); frameWeight.add(ws.toDoubleArray()) }
            }
            s += N / 2
        }
        if (framePitch.isEmpty()) return null
        // tuning offset: circular mean of how far the peaks sit from the equal-tempered grid
        var sn = 0.0; var cs = 0.0
        for (f in framePitch.indices) for (k in framePitch[f].indices) {
            val m = framePitch[f][k]; val ang = (m - round(m)) * 2 * PI
            sn += frameWeight[f][k] * sin(ang); cs += frameWeight[f][k] * cos(ang)
        }
        val tuning = atan2(sn, cs) / (2 * PI)
        val chroma = DoubleArray(12)
        val fc = DoubleArray(12)
        for (f in framePitch.indices) {
            java.util.Arrays.fill(fc, 0.0)
            for (k in framePitch[f].indices) {
                val n = round(framePitch[f][k] - tuning).toInt(); val w = frameWeight[f][k]
                fc[Math.floorMod(n, 12)] += w
                // the peak may be the 3rd or 5th harmonic of a lower note
                fc[Math.floorMod(n - 19, 12)] += w * 0.5
                fc[Math.floorMod(n - 28, 12)] += w * 0.3
            }
            val sum = fc.sum()
            if (sum > 0) for (i in 0 until 12) chroma[i] += fc[i] / sum
        }
        var best = -2.0; var second = -2.0; var bestKey = Key(0, false); var secondKey = Key(0, true)
        for (t in 0 until 12) for (minor in booleanArrayOf(false, true)) {
            val r = correlate(chroma, if (minor) MINOR else MAJOR, t)
            val k = Key(t, minor)
            if (r > best) { second = best; secondKey = bestKey; best = r; bestKey = k } else if (r > second) { second = r; secondKey = k }
        }
        // the alternative shown to the user: best reading that is not the relative key of the winner
        var alt = secondKey; var altR = second
        if (alt == bestKey.relative) {
            altR = -2.0
            for (t in 0 until 12) for (minor in booleanArrayOf(false, true)) {
                val k = Key(t, minor); if (k == bestKey || k == bestKey.relative) continue
                val r = correlate(chroma, if (minor) MINOR else MAJOR, t); if (r > altR) { altR = r; alt = k }
            }
        }
        return Result(bestKey, max(best, 0.0), alt, chroma)
    }

    /** Pearson correlation of the chromagram with a key profile whose first entry is at [tonic]. */
    private fun correlate(c: DoubleArray, p: DoubleArray, tonic: Int): Double {
        var mc = 0.0; var mp = 0.0
        for (i in 0 until 12) { mc += c[i]; mp += p[i] }
        mc /= 12; mp /= 12
        var num = 0.0; var dc = 0.0; var dp = 0.0
        for (i in 0 until 12) {
            val a = c[(tonic + i) % 12] - mc; val b = p[i] - mp
            num += a * b; dc += a * a; dp += b * b
        }
        return if (dc <= 0.0 || dp <= 0.0) 0.0 else num / sqrt(dc * dp)
    }

    /** Key of a MIDI file from its note durations (exact: no spectrum involved). */
    fun fromNotes(weightsByPitchClass: DoubleArray): Result? {
        if (weightsByPitchClass.sum() <= 0.0) return null
        var best = -2.0; var bestKey = Key(0, false)
        for (t in 0 until 12) for (minor in booleanArrayOf(false, true)) {
            val r = correlate(weightsByPitchClass, if (minor) MINOR else MAJOR, t)
            if (r > best) { best = r; bestKey = Key(t, minor) }
        }
        return Result(bestKey, max(best, 0.0), bestKey.relative, weightsByPitchClass)
    }
}
