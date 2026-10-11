package app.feldkit

import app.feldkit.audio.KeyAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/** Synthetic chord progressions have a known key; loops from tools/make-key-labels.py have the key stated in their file names. */
class KeyTest {
    private val dir = File("build/fixtures")

    private fun tone(out: FloatArray, start: Int, len: Int, midi: Int, rate: Int, amp: Double) {
        val f = 440.0 * 2.0.pow((midi - 69) / 12.0)
        for (h in 1..5) for (i in 0 until len) out[start + i] += (amp / h * sin(2 * PI * f * h * i / rate) * (1.0 - i.toDouble() / len * 0.3)).toFloat()
    }

    private fun progression(tonic: Int, minor: Boolean, rate: Int = 44100): FloatArray {
        val scale = if (minor) intArrayOf(0, 2, 3, 5, 7, 8, 10) else intArrayOf(0, 2, 4, 5, 7, 9, 11)
        val chords = listOf(0, 3, 4, 0, 5, 3, 4, 0)             // degrees: i iv v i vi iv v i
        val out = FloatArray(rate * 16); val bar = rate * 2
        for ((n, d) in chords.withIndex()) {
            for (k in intArrayOf(0, 2, 4)) tone(out, n * bar, bar, 48 + tonic + scale[(d + k) % 7] + 12 * ((d + k) / 7), rate, 0.2)
            tone(out, n * bar, bar, 36 + tonic + scale[d], rate, 0.3)
        }
        return out
    }

    @Test fun syntheticKeys() {
        var ok = 0; var total = 0
        for (t in 0 until 12) for (minor in listOf(false, true)) {
            val r = KeyAnalyzer.analyze(progression(t, minor), 44100)!!
            total++
            if (r.key.tonic == t && r.key.minor == minor) ok++ else println("synthetic miss: want $t minor=$minor got ${r.key.tonic} minor=${r.key.minor}")
        }
        println("synthetic keys: $ok / $total")
        assertTrue("$ok of $total", ok >= 22)
    }

    @Test fun notation() {
        assertEquals("11A", KeyAnalyzer.Key(6, true).camelot)       // F# minor
        assertEquals("8A", KeyAnalyzer.Key(9, true).camelot)        // A minor
        assertEquals("8B", KeyAnalyzer.Key(0, false).camelot)       // C major
        assertEquals("A major", KeyAnalyzer.Key(6, true).relative.name)
        assertEquals("1B", KeyAnalyzer.Key(11, false).camelot)      // B major
        assertEquals("1A", KeyAnalyzer.Key(8, true).camelot)        // G# minor
    }

    @Test fun midiNotes() {
        val w = DoubleArray(12); for (pc in intArrayOf(9, 11, 0, 2, 4, 5, 7)) w[pc] = 1.0; w[9] = 3.0; w[4] = 2.0; w[0] = 2.0
        assertEquals("A minor", KeyAnalyzer.fromNotes(w)!!.key.name)
    }

    private fun readWav(f: File): Pair<FloatArray, Int> {
        val raw = f.readBytes(); val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val rate = b.getInt(24); var p = 12; var off = -1; var len = 0
        while (p + 8 <= raw.size) { val id = String(raw, p, 4, Charsets.ISO_8859_1); val sz = b.getInt(p + 4); if (id == "data") { off = p + 8; len = if (sz <= 0 || off + sz > raw.size) raw.size - off else sz; break }; p += 8 + sz + (sz and 1) }
        return FloatArray(len / 2) { b.getShort(off + it * 2) / 32768f } to rate
    }

    @Test fun labelledLoops() {
        val tsv = File(dir, "key-labels.tsv"); assumeTrue("run tools/make-key-labels.py", tsv.exists())
        var exact = 0; var near = 0; var n = 0
        for (line in tsv.readLines().filter { it.isNotBlank() }) {
            val p = line.split('\t'); if (p[3] != "tonal") continue
            val f = File(dir, p[0]); if (!f.exists()) continue
            val (x, rate) = readWav(f)
            val r = KeyAnalyzer.analyze(x, rate) ?: continue
            n++
            val want = KeyAnalyzer.Key(p[1].toInt(), p[2] == "min")
            if (r.key == want) exact++ else if (r.key.relative == want) near++
        }
        println("labelled loops: exact $exact / $n, relative key $near")
        assertTrue("accuracy collapsed: $exact / $n", exact * 100 >= n * 28)
    }
}
