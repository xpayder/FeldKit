package app.feldkit.audio

import android.content.Context
import android.net.Uri
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What a WAV file says about itself beyond its audio: broadcast-wave (bext) description and time reference, the iXML block
 * field recorders write (scene, take, tape, notes, track names, timecode rate), sampler data (smpl: root note, loops),
 * Acid loop data and cue markers. Only chunk headers are walked; the audio itself is skipped, not read.
 */
object AudioMeta {
    class Row(val label: String, val value: String)

    fun read(ctx: Context, uri: Uri): List<Row> {
        val ext = uri.getQueryParameter("path")?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (ext != "wav" && ext != "wave" && ext != "bwf") return emptyList()
        return try { ctx.contentResolver.openInputStream(uri)?.use { parseWav(it) } ?: emptyList() } catch (_: Throwable) { emptyList() }
    }

    private const val MAX_CHUNK = 1 shl 20

    private fun readFully(s: InputStream, n: Int): ByteArray? {
        val b = ByteArray(n); var got = 0
        while (got < n) { val r = s.read(b, got, n - got); if (r <= 0) return null; got += r }
        return b
    }

    private fun skip(s: InputStream, n: Long): Boolean {
        var left = n
        while (left > 0) {
            val k = s.skip(left)
            if (k > 0) { left -= k; continue }
            // skip() may refuse on some streams: read and discard instead
            val buf = ByteArray(minOf(left, 65536L).toInt()); val r = s.read(buf)
            if (r <= 0) return false
            left -= r
        }
        return true
    }

    private fun cstr(b: ByteArray, off: Int, len: Int): String {
        var e = off; val end = minOf(off + len, b.size)
        while (e < end && b[e].toInt() != 0) e++
        return String(b, off, e - off, Charsets.ISO_8859_1).trim()
    }

    internal fun parseWav(s: InputStream): List<Row> {
        val head = readFully(s, 12) ?: return emptyList()
        val tag = String(head, 0, 4, Charsets.ISO_8859_1)
        if (tag != "RIFF" && tag != "RF64" || String(head, 8, 4, Charsets.ISO_8859_1) != "WAVE") return emptyList()
        val rows = ArrayList<Row>()
        var rate = 0; var timeRef = -1L; var tcRate: String? = null; var cues = 0
        var safety = 0
        while (safety++ < 200) {
            val h = readFully(s, 8) ?: break
            val id = String(h, 0, 4, Charsets.ISO_8859_1)
            val size = ByteBuffer.wrap(h, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val pad = size and 1
            val want = id in setOf("fmt ", "bext", "iXML", "smpl", "acid", "cue ", "LIST")
            if (!want || size > MAX_CHUNK) { if (!skip(s, size + pad)) break; continue }
            val b = readFully(s, size.toInt()) ?: break
            if (pad == 1L) skip(s, 1)
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            when (id) {
                "fmt " -> if (b.size >= 8) rate = bb.getInt(4)
                "bext" -> if (b.size >= 338) {
                    cstr(b, 0, 256).takeIf { it.isNotEmpty() }?.let { rows.add(Row("Description", it)) }
                    cstr(b, 256, 32).takeIf { it.isNotEmpty() }?.let { rows.add(Row("Originator", it)) }
                    val date = cstr(b, 320, 10); val time = cstr(b, 330, 8)
                    if (date.isNotEmpty()) rows.add(Row("Recorded", "$date $time".trim()))
                    timeRef = bb.getLong(338)
                }
                "iXML" -> {
                    val xml = String(b, Charsets.UTF_8)
                    fun f(tagName: String) = Regex("<$tagName>\\s*([^<]*?)\\s*</$tagName>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
                    f("PROJECT")?.let { rows.add(Row("Project", it)) }
                    f("SCENE")?.let { rows.add(Row("Scene", it)) }
                    f("TAPE")?.let { rows.add(Row("Tape / roll", it)) }
                    f("TAKE")?.let { rows.add(Row("Take", it + if (f("CIRCLED")?.equals("TRUE", true) == true) " (circled)" else "")) }
                    f("NOTE")?.let { rows.add(Row("Note", it)) }
                    tcRate = f("TIMECODE_RATE")?.split('/')?.let { q -> val n = q[0].toDoubleOrNull(); val d = q.getOrNull(1)?.toDoubleOrNull() ?: 1.0; if (n != null && d > 0) "%.3f".format(java.util.Locale.US, n / d).trimEnd('0').trimEnd('.') else null }
                    val names = Regex("<TRACK>.*?<NAME>\\s*([^<]*?)\\s*</NAME>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { it.groupValues[1] }.filter { it.isNotEmpty() }.toList()
                    if (names.isNotEmpty()) rows.add(Row("Tracks", names.joinToString(", ")))
                }
                "smpl" -> if (b.size >= 36) {
                    val note = bb.getInt(12); val loops = bb.getInt(28)
                    if (note in 0..127) rows.add(Row("Root note", noteName(note) + " (MIDI $note)"))
                    if (loops > 0) rows.add(Row("Sampler loops", loops.toString()))
                }
                "acid" -> if (b.size >= 24) {
                    val flags = bb.getInt(0); val root = bb.getShort(4).toInt() and 0xFFFF
                    val beats = bb.getInt(12); val meterDen = bb.getShort(16).toInt(); val meterNum = bb.getShort(18).toInt(); val tempo = bb.getFloat(20)
                    if (flags and 0x02 != 0 && root in 0..127) rows.add(Row("Root note (Acid)", noteName(root)))
                    if (beats > 0) rows.add(Row("Loop length", "$beats beats" + if (meterNum > 0 && meterDen > 0) " in $meterNum/$meterDen" else ""))
                    if (tempo in 20f..400f) rows.add(Row("Acid tempo", "%.2f BPM".format(java.util.Locale.US, tempo)))
                }
                "cue " -> if (b.size >= 4) cues = bb.getInt(0)
            }
        }
        if (timeRef >= 0 && rate > 0) {
            val sec = timeRef.toDouble() / rate
            val h = (sec / 3600).toInt(); val m = ((sec % 3600) / 60).toInt(); val sx = sec % 60
            rows.add(Row("Start timecode", "%02d:%02d:%06.3f".format(java.util.Locale.US, h, m, sx) + (tcRate?.let { "  ($it fps)" } ?: "")))
        }
        if (cues > 0) rows.add(Row("Markers", cues.toString()))
        return rows
    }

    private val NOTES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    internal fun noteName(midi: Int) = NOTES[midi % 12] + (midi / 12 - 1)
}
