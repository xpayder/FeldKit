package app.feldkit.audio

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * "What is inside" a music project without starting the DAW: Ableton Live sets (.als, gzip XML), FL Studio projects (.flp,
 * binary events) and REAPER projects (.rpp, text). Streamed, so a 45 MB Live set costs no more memory than a small one.
 */
object DawProject {
    class Section(val title: String, val items: List<String>)
    class Info(val daw: String, val version: String?, val tempo: Double?, val meter: String?, val sections: List<Section>)

    val EXTENSIONS = setOf("als", "flp", "rpp")

    fun read(ctx: Context, uri: Uri): Info? {
        val ext = uri.getQueryParameter("path")?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return try { ctx.contentResolver.openInputStream(uri)?.use { parse(ext, BufferedInputStream(it, 1 shl 16)) } } catch (_: Throwable) { null }
    }

    internal fun parse(ext: String, s: InputStream): Info? = when (ext) {
        "als" -> ableton(GZIPInputStream(s, 1 shl 16))
        "flp" -> flStudio(s)
        "rpp" -> reaper(s)
        else -> null
    }

    private fun fmtTempo(t: Double) = if (t == Math.rint(t)) "%.0f".format(Locale.US, t) else "%.2f".format(Locale.US, t).trimEnd('0').trimEnd('.')

    // ── Ableton Live ──

    private fun attr(line: String, name: String): String? {
        val k = line.indexOf("$name=\""); if (k < 0) return null
        val st = k + name.length + 2; val e = line.indexOf('"', st); if (e < 0) return null
        return line.substring(st, e).replace("&amp;", "&").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&apos;", "'")
    }

    private fun ableton(gz: InputStream): Info? {
        val r = gz.bufferedReader(Charsets.UTF_8)
        val stack = ArrayList<String>()
        var version: String? = null; var tempo: Double? = null; var num: Int? = null; var den: Int? = null
        val tracks = ArrayList<String>(); var scenes = 0
        val plugins = LinkedHashMap<String, Int>(); val samples = LinkedHashSet<String>()
        var trackType: String? = null; var trackNamed = true; var trackDepth = -1
        var line = r.readLine()
        while (line != null) {
            val t = line.trim()
            if (t.startsWith("<") && !t.startsWith("<?")) {
                if (t.startsWith("</")) {
                    val nm = t.substring(2, t.length - 1).trim()
                    if (stack.isNotEmpty() && stack.last() == nm) stack.removeAt(stack.size - 1)
                    if (stack.size < trackDepth) { trackType = null; trackDepth = -1 }
                } else {
                    var e = 1; while (e < t.length && t[e] != ' ' && t[e] != '>' && t[e] != '/') e++
                    val name = t.substring(1, e); val selfClose = t.endsWith("/>")
                    val top = stack.lastOrNull()
                    if (stack.isEmpty() && name == "Ableton") version = attr(t, "Creator")?.removePrefix("Ableton ")?.removePrefix("Live ")
                    if (top == "Tracks" && (name == "MidiTrack" || name == "AudioTrack" || name == "ReturnTrack" || name == "GroupTrack")) {
                        trackType = when (name) { "MidiTrack" -> "MIDI"; "AudioTrack" -> "Audio"; "ReturnTrack" -> "Return"; else -> "Group" }
                        trackNamed = false; trackDepth = stack.size + 1
                    }
                    if (name == "EffectiveName" && top == "Name" && trackType != null && !trackNamed) { attr(t, "Value")?.let { tracks.add("$trackType · $it") }; trackNamed = true }
                    if (name == "Scene" && top == "Scenes") scenes++
                    val inMain = stack.contains("MainTrack") || stack.contains("MasterTrack")
                    if (name == "Manual" && top == "Tempo" && inMain && tempo == null) tempo = attr(t, "Value")?.toDoubleOrNull()
                    // the song's meter: the first time signature in the file (the one at bar 1)
                    if (num == null && top == "RemoteableTimeSignature" && name == "Numerator") num = attr(t, "Value")?.toIntOrNull()
                    if (den == null && top == "RemoteableTimeSignature" && name == "Denominator") den = attr(t, "Value")?.toIntOrNull()
                    if (name == "Name" && (top == "Vst3PluginInfo" || top == "AuPluginInfo")) attr(t, "Value")?.takeIf { it.isNotEmpty() }?.let { plugins.merge(it, 1, Int::plus) }
                    if (name == "PlugName" && top == "VstPluginInfo") attr(t, "Value")?.takeIf { it.isNotEmpty() }?.let { plugins.merge(it, 1, Int::plus) }
                    if (name == "Name" && top == "FileRef" && samples.size < 2000) attr(t, "Value")?.takeIf { it.isNotEmpty() }?.let { samples.add(it) }
                    if (!selfClose) stack.add(name)
                }
            }
            line = r.readLine()
        }
        if (version == null && tracks.isEmpty()) return null
        val secs = ArrayList<Section>()
        if (tracks.isNotEmpty()) secs.add(Section("Tracks (${tracks.size})", tracks))
        if (plugins.isNotEmpty()) secs.add(Section("Plug-ins", plugins.map { (k, v) -> if (v > 1) "$k ×$v" else k }))
        if (scenes > 0) secs.add(Section("Scenes", listOf("$scenes")))
        if (samples.isNotEmpty()) secs.add(Section("Samples used (${samples.size})", samples.take(40).toList() + if (samples.size > 40) listOf("…") else emptyList()))
        return Info("Ableton Live", version, tempo, if (num != null && den != null) "$num/$den" else null, secs)
    }

    // ── FL Studio ──

    private fun readN(s: InputStream, n: Int): ByteArray? { val b = ByteArray(n); var g = 0; while (g < n) { val r = s.read(b, g, n - g); if (r <= 0) return null; g += r }; return b }
    private fun skipN(s: InputStream, n: Long): Boolean { var l = n; while (l > 0) { val k = s.skip(l); if (k > 0) { l -= k; continue }; if (s.read() < 0) return false; l-- }; return true }
    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int) = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)
    private fun utf16(b: ByteArray) = String(b, Charsets.UTF_16LE).trimEnd('\u0000')
    private fun ascii(b: ByteArray) = String(b, Charsets.ISO_8859_1).trimEnd('\u0000')

    private fun flStudio(s: InputStream): Info? {
        val h = readN(s, 22) ?: return null
        if (String(h, 0, 4, Charsets.ISO_8859_1) != "FLhd" || String(h, 14, 4, Charsets.ISO_8859_1) != "FLdt") return null
        val channels = u16(h, 10)
        var version: String? = null; var tempo: Double? = null
        val chanNames = LinkedHashSet<String>(); val instruments = LinkedHashSet<String>(); val mixer = LinkedHashSet<String>(); val samples = LinkedHashSet<String>()
        val meta = ArrayList<Pair<String, String>>(); var wrappers = 0
        var guard = 0
        while (guard++ < 5_000_000) {
            val id = s.read(); if (id < 0) break
            val size: Long
            val data: ByteArray?
            when {
                id < 64 -> { size = 1; data = readN(s, 1) }
                id < 128 -> { size = 2; data = readN(s, 2) }
                id < 192 -> { size = 4; data = readN(s, 4) }
                else -> {
                    var sz = 0L; var sh = 0
                    while (true) { val b = s.read(); if (b < 0) return build(version, tempo, channels, chanNames, instruments, mixer, samples, meta, wrappers); sz = sz or ((b and 0x7F).toLong() shl sh); sh += 7; if (b and 0x80 == 0) break }
                    size = sz
                    if (sz > 8192) { if (!skipN(s, sz)) break; continue }
                    data = readN(s, sz.toInt())
                }
            }
            if (data == null) break
            when (id) {
                199 -> version = ascii(data)
                156 -> if (data.size == 4) tempo = u32(data, 0) / 1000.0
                66 -> if (tempo == null && data.size == 2) tempo = u16(data, 0).toDouble()
                192, 193 -> utf16(data).takeIf { it.isNotEmpty() }?.let { chanNames.add(it) }      // channel names as shown in the channel rack
                203 -> utf16(data).takeIf { it.isNotEmpty() }?.let { instruments.add(it) }          // the plug-in (or sampler) behind each channel
                194 -> utf16(data).takeIf { it.isNotEmpty() }?.let { meta.add("Title" to it) }
                206 -> utf16(data).takeIf { it.isNotEmpty() }?.let { meta.add("Artist" to it) }
                207 -> utf16(data).takeIf { it.isNotEmpty() }?.let { meta.add("Genre" to it) }
                201 -> if (utf16(data) == "Fruity Wrapper") wrappers++
                196 -> utf16(data).takeIf { it.isNotEmpty() }?.let { samples.add(it.replace('\\', '/').substringAfterLast('/')) }
                204 -> utf16(data).takeIf { it.isNotEmpty() && !Regex("(Insert|Track) \\d+").matches(it) }?.let { mixer.add(it) }
            }
        }
        return build(version, tempo, channels, chanNames, instruments, mixer, samples, meta, wrappers)
    }

    private fun build(version: String?, tempo: Double?, channels: Int, chan: Set<String>, instruments: Set<String>, mixer: Set<String>, samples: Set<String>, meta: List<Pair<String, String>>, wrappers: Int): Info {
        val secs = ArrayList<Section>()
        if (meta.isNotEmpty()) secs.add(Section("About", meta.map { "${it.first}: ${it.second}" }))
        secs.add(Section("Channels ($channels)", chan.take(60).toList().ifEmpty { listOf("$channels channels") }))
        if (mixer.isNotEmpty()) secs.add(Section("Mixer and playlist names", mixer.take(40).toList()))
        // a sampler channel carries its sample's name; only the names that are not samples or audio clips are plug-ins
        val stems = samples.map { it.substringBeforeLast('.').lowercase() }.toSet()
        val plug = instruments.filter { it !in chan && it.lowercase() !in stems && !it.startsWith("Track ") }
        if (plug.isNotEmpty() || wrappers > 0) secs.add(Section("Plug-ins", plug.take(40) + if (wrappers > plug.size) listOf("$wrappers VST / AU plug-ins in all") else emptyList()))
        if (samples.isNotEmpty()) secs.add(Section("Samples used (${samples.size})", samples.take(40).toList() + if (samples.size > 40) listOf("…") else emptyList()))
        return Info("FL Studio", version, tempo, null, secs)
    }

    // ── REAPER ──

    private fun reaper(s: InputStream): Info? {
        val r = s.bufferedReader(Charsets.UTF_8)
        var version: String? = null; var tempo: Double? = null; var meter: String? = null
        val tracks = ArrayList<String>(); val plugins = LinkedHashMap<String, Int>(); val samples = LinkedHashSet<String>(); var items = 0
        var depth = 0; var inTrackDepth = -1
        var line = r.readLine(); var first = true
        while (line != null) {
            val t = line.trim()
            if (first) { if (!t.startsWith("<REAPER_PROJECT")) return null; version = Regex("\"([^\"]+)\"").find(t)?.groupValues?.get(1); first = false }
            if (t.startsWith("<")) {
                depth++
                if (t.startsWith("<TRACK")) inTrackDepth = depth
                if (t.startsWith("<ITEM")) items++
                if (t.startsWith("<VST") || t.startsWith("<AU") || t.startsWith("<JS") || t.startsWith("<CLAP")) {
                    val q = Regex("\"([^\"]+)\"").find(t)?.groupValues?.get(1) ?: Regex("^<\\w+\\s+(\\S+)").find(t)?.groupValues?.get(1)
                    q?.let { plugins.merge(it.substringAfter(": ").trim(), 1, Int::plus) }
                }
            } else if (t == ">") { if (depth == inTrackDepth) inTrackDepth = -1; depth-- }
            else if (t.startsWith("TEMPO ") && tempo == null) { val p = t.split(' '); tempo = p.getOrNull(1)?.toDoubleOrNull(); if (p.size >= 4) meter = "${p[2]}/${p[3]}" }
            else if (t.startsWith("NAME ") && inTrackDepth == depth) tracks.add(t.removePrefix("NAME ").trim().trim('"').ifEmpty { "(unnamed)" })
            else if (t.startsWith("FILE ") && samples.size < 2000) samples.add(t.removePrefix("FILE ").trim().trim('"').replace('\\', '/').substringAfterLast('/'))
            line = r.readLine()
        }
        if (first) return null
        val secs = ArrayList<Section>()
        if (tracks.isNotEmpty()) secs.add(Section("Tracks (${tracks.size})", tracks))
        if (plugins.isNotEmpty()) secs.add(Section("Plug-ins", plugins.map { (k, v) -> if (v > 1) "$k ×$v" else k }))
        if (items > 0) secs.add(Section("Items", listOf("$items")))
        if (samples.isNotEmpty()) secs.add(Section("Media (${samples.size})", samples.take(40).toList() + if (samples.size > 40) listOf("…") else emptyList()))
        return Info("REAPER", version, tempo, meter, secs)
    }

    fun tempoText(i: Info) = i.tempo?.let { fmtTempo(it) }
}
