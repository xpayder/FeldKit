package app.feldkit.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Mono float PCM of the first seconds of an audio file, plus the file's full duration when known. */
/** Peak loudness per [AudioPcm.BUCKET_MS] ms bucket (0..1) and the file's duration. */
class Envelope(val peaks: FloatArray, val durationSec: Double)

class Pcm(val samples: FloatArray, val sampleRate: Int, val durationSec: Double)

/** Decodes any format Android can decode (and plain WAV / AIFF) to mono floats for analysis. */
object AudioPcm {
    fun decode(ctx: Context, uri: Uri, maxSeconds: Int = 200): Pcm? {
        val ext = uri.getQueryParameter("path")?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (ext == "aif" || ext == "aiff" || ext == "aifc") aiff(ctx, uri, maxSeconds)?.let { return it }
        return mediaCodec(ctx, uri, maxSeconds)
    }

    /**
     * Loudness envelope of the whole file: the largest absolute sample in every [BUCKET_MS] milliseconds, streamed so that a long
     * file costs no more memory than a short one (AIFF is read through the player's WAV adapter and is limited to ten minutes).
     */
    fun envelope(ctx: Context, uri: Uri): Envelope? {
        val ext = uri.getQueryParameter("path")?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (ext == "aif" || ext == "aiff" || ext == "aifc") {
            val p = aiff(ctx, uri, 600) ?: return null
            val per = (p.sampleRate * BUCKET_MS / 1000).coerceAtLeast(1)
            val n = (p.samples.size + per - 1) / per
            return Envelope(FloatArray(n) { b -> var m = 0f; for (i in b * per until minOf((b + 1) * per, p.samples.size)) m = maxOf(m, kotlin.math.abs(p.samples[i])); m }, p.durationSec)
        }
        val list = ArrayList<Float>(); var cur = 0f; var cnt = 0
        val r = mediaCodec(ctx, uri, 6 * 3600) { v, rate ->
            val a = kotlin.math.abs(v); if (a > cur) cur = a
            if (++cnt >= rate * BUCKET_MS / 1000) { list.add(cur); cur = 0f; cnt = 0 }
        } ?: return null
        if (cnt > 0) list.add(cur)
        return Envelope(list.toFloatArray(), r.durationSec)
    }

    const val BUCKET_MS = 20

    private fun mediaCodec(ctx: Context, uri: Uri, maxSeconds: Int, sink: ((Float, Int) -> Unit)? = null): Pcm? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            var track = -1; var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) { val f = ex.getTrackFormat(i); if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { track = i; fmt = f; break } }
            if (track < 0 || fmt == null) return null
            ex.selectTrack(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else -1L
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var float = false
            var out = FloatArray(if (sink != null) 0 else rate * 30)
            var n = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false; var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val sz = ex.readSampleData(buf, 0)
                        if (sz < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(ii, 0, sz, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    oi >= 0 -> {
                        val buf = codec.getOutputBuffer(oi)!!
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        val bo = buf.order(ByteOrder.nativeOrder())
                        val frames = if (float) info.size / (4 * channels) else info.size / (2 * channels)
                        if (sink == null && n + frames > out.size) out = out.copyOf(maxOf(out.size * 2, n + frames))
                        for (f in 0 until frames) {
                            var s = 0f
                            for (c in 0 until channels) s += if (float) bo.getFloat() else bo.getShort() / 32768f
                            if (sink != null) { sink(s / channels, rate); n++ } else out[n++] = s / channels
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 || n.toLong() >= rate.toLong() * maxSeconds) outputDone = true
                    }
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val nf = codec.outputFormat
                        rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        float = nf.containsKey(MediaFormat.KEY_PCM_ENCODING) && nf.getInteger(MediaFormat.KEY_PCM_ENCODING) == 4
                        if (sink == null && out.size < rate * 30) out = out.copyOf(rate * 30)
                    }
                }
            }
            if (if (sink != null) n == 0 else n < rate) return null
            val full = if (durationUs > 0) durationUs / 1e6 else n.toDouble() / rate
            return Pcm(if (sink != null) FloatArray(0) else out.copyOf(n), rate, full)
        } catch (_: Throwable) {
            return null
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { ex.release() } catch (_: Throwable) {}
        }
    }

    /** AIFF through the same adapter the player uses (it presents the file as WAV), then straight PCM parsing. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun aiff(ctx: Context, uri: Uri, maxSeconds: Int): Pcm? {
        val ds = app.feldkit.ui.screens.DriveDataSource(ctx)
        try {
            ds.open(androidx.media3.datasource.DataSpec(uri))
            val head = ByteArray(44); var h = 0
            while (h < 44) { val r = ds.read(head, h, 44 - h); if (r <= 0) return null; h += r }
            val b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            val fmtTag = b.getShort(20).toInt(); val ch = b.getShort(22).toInt(); val rate = b.getInt(24); val bits = b.getShort(34).toInt()
            val dataLen = b.getInt(40).toLong() and 0xFFFFFFFFL
            val frameBytes = ch * bits / 8
            if (frameBytes <= 0 || rate <= 0) return null
            val total = dataLen / frameBytes
            val want = minOf(total, rate.toLong() * maxSeconds).toInt()
            val out = FloatArray(want)
            val buf = ByteArray(frameBytes * 4096); var done = 0
            while (done < want) {
                var got = 0; val need = minOf(4096, want - done) * frameBytes
                while (got < need) { val r = ds.read(buf, got, need - got); if (r <= 0) break; got += r }
                val frames = got / frameBytes; if (frames == 0) break
                val bb = ByteBuffer.wrap(buf, 0, got).order(ByteOrder.LITTLE_ENDIAN)
                for (f in 0 until frames) {
                    var s = 0f
                    for (c in 0 until ch) s += when {
                        fmtTag == 3 && bits == 32 -> bb.getFloat()
                        bits == 8 -> ((bb.get().toInt() and 0xFF) - 128) / 128f
                        bits == 16 -> bb.getShort() / 32768f
                        bits == 24 -> { val v = (bb.get().toInt() and 0xFF) or ((bb.get().toInt() and 0xFF) shl 8) or (bb.get().toInt() shl 16); v / 8388608f }
                        else -> bb.getInt() / 2147483648f
                    }
                    out[done + f] = s / ch
                }
                done += frames
            }
            return Pcm(out.copyOf(done), rate, total.toDouble() / rate)
        } catch (_: Throwable) { return null } finally { try { ds.close() } catch (_: Throwable) {} }
    }
}
