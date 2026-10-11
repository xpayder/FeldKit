package app.feldkit.audio

import android.content.Context
import android.net.Uri
import java.util.concurrent.ConcurrentHashMap

/** What the info sheet shows about a file's tempo. */
class TempoInfo(
    val result: TempoAnalyzer.Result?,
    val tag: AudioTags.Tag?,
    /** For MIDI: the tempo map was read from the file (exact), not measured. */
    val fromFile: Boolean = false,
    val variable: Boolean = false,
    val unsupported: Boolean = false,
    /** Musical key (null for drums and noise, or when nothing tonal was found). */
    val key: KeyAnalyzer.Result? = null,
)

object TempoService {
    private val cache = ConcurrentHashMap<String, TempoInfo>()

    /** Blocking: call from a background dispatcher. */
    fun analyze(ctx: Context, uri: Uri): TempoInfo {
        val key = uri.toString()
        cache[key]?.let { return it }
        val ext = uri.getQueryParameter("path")?.substringAfterLast('.', "")?.lowercase().orEmpty()
        val info = if (ext == "mid" || ext == "midi" || ext == "kar") midi(ctx, uri) else measured(ctx, uri)
        cache[key] = info
        return info
    }

    private fun measured(ctx: Context, uri: Uri): TempoInfo {
        val tag = try { AudioTags.readBpm(ctx, uri) } catch (_: Throwable) { null }
        val pcm = AudioPcm.decode(ctx, uri) ?: return TempoInfo(null, tag, unsupported = true)
        val r = try { TempoAnalyzer.analyze(pcm.samples, pcm.sampleRate) } catch (_: Throwable) { null }
        val key = try { KeyAnalyzer.analyze(pcm.samples, pcm.sampleRate) } catch (_: Throwable) { null }
        return TempoInfo(r, tag, key = key)
    }

    private fun midi(ctx: Context, uri: Uri): TempoInfo {
        val bytes = try { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (_: Throwable) { null } ?: return TempoInfo(null, null, unsupported = true)
        val song = app.feldkit.audio.midi.Smf.parse(bytes) ?: return TempoInfo(null, null, unsupported = true)
        val bpm = song.tempoChanges.firstOrNull()?.let { 60_000_000.0 / it.microsPerQuarter } ?: 120.0
        val variable = song.tempoChanges.map { it.microsPerQuarter }.distinct().size > 1
        val weights = DoubleArray(12)
        for (n in song.notes) if (n.channel != 9) weights[n.key % 12] += (song.seconds(n.endTick) - song.seconds(n.tick)).coerceAtLeast(0.01)
        return TempoInfo(TempoAnalyzer.Result(Math.round(bpm * 100.0) / 100.0, 1.0, !variable, 0.0, emptyList()), null, fromFile = true, variable = variable, key = KeyAnalyzer.fromNotes(weights))
    }
}
