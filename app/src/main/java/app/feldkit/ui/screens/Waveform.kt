package app.feldkit.ui.screens

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.feldkit.audio.AudioPcm
import app.feldkit.audio.Envelope
import app.feldkit.ui.theme.Accent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Envelope of an audio file, computed once in the background and kept for the session. */
object WaveformCache {
    private val cache = ConcurrentHashMap<String, Envelope>()
    private val failed = ConcurrentHashMap.newKeySet<String>()
    fun peek(uri: Uri): Envelope? = cache[uri.toString()]
    fun load(ctx: android.content.Context, uri: Uri): Envelope? {
        val key = uri.toString()
        cache[key]?.let { return it }
        if (key in failed) return null
        val e = try { AudioPcm.envelope(ctx, uri) } catch (_: Throwable) { null }
        if (e == null || e.peaks.isEmpty()) { failed.add(key); return null }
        if (cache.size > 24) cache.clear()
        cache[key] = e
        return e
    }
}

@Composable
fun rememberEnvelope(uri: Uri?): Envelope? {
    val ctx = LocalContext.current
    val state = produceState<Envelope?>(uri?.let { WaveformCache.peek(it) }, uri) {
        if (uri != null && value == null) value = withContext(Dispatchers.IO) { WaveformCache.load(ctx, uri) }
    }
    return state.value
}

/**
 * Waveform that doubles as the seek bar: tap to jump, drag to scrub ([onScrub] while moving, [onSeek] on release). The played part is drawn in the accent colour.
 * [loopA] / [loopB] (0..1) shade the A-B region when set.
 */
@Composable
fun Waveform(
    envelope: Envelope,
    fraction: Float,
    onSeek: (Float) -> Unit,
    onScrub: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
    loopA: Float? = null,
    loopB: Float? = null,
    onScrubbing: (Boolean) -> Unit = {},
) {
    var width by remember { mutableIntStateOf(1) }
    Box(
        modifier.fillMaxWidth().height(height)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(envelope) { detectTapGestures { o -> onSeek((o.x / width).coerceIn(0f, 1f)) } }
            .pointerInput(envelope) {
                var last = 0f
                detectDragGestures(
                    onDragStart = { o -> last = (o.x / width).coerceIn(0f, 1f); onScrubbing(true); onScrub(last) },
                    onDrag = { c, _ -> last = (c.position.x / width).coerceIn(0f, 1f); onScrub(last) },
                    onDragEnd = { onSeek(last); onScrubbing(false) }, onDragCancel = { onScrubbing(false) },
                )
            }
    ) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val barW = 3.dp.toPx(); val gap = 2.dp.toPx(); val step = barW + gap
            val n = (size.width / step).toInt().coerceAtLeast(1)
            val per = envelope.peaks.size.toFloat() / n
            val mid = size.height / 2
            // loudness of a bar: the mean of its 20 ms peaks (a plain maximum would make every bar of a dense loop equally tall)
            val bars = FloatArray(n) { i ->
                val from = (i * per).toInt(); val to = maxOf(from + 1, ((i + 1) * per).toInt()).coerceAtMost(envelope.peaks.size)
                var sum = 0f; for (k in from until to) sum += envelope.peaks[k]
                sum / (to - from).coerceAtLeast(1)
            }
            val top = (bars.maxOrNull() ?: 1f).coerceAtLeast(1e-4f)
            if (loopA != null && loopB != null && loopB > loopA) drawRect(Accent.copy(alpha = 0.16f), Offset(loopA * size.width, 0f), Size((loopB - loopA) * size.width, size.height))
            for (i in 0 until n) {
                val h = (bars[i] / top * size.height).coerceAtLeast(3.dp.toPx())
                val x = i * step
                val played = (x + barW / 2) / size.width <= fraction
                drawRoundRect(if (played) Accent else Color(0x4DFFFFFF), Offset(x, mid - h / 2), Size(barW, h), CornerRadius(barW / 2))
            }
            fun mark(f: Float?) { if (f != null) drawRect(Accent, Offset(f * size.width - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height)) }
            mark(loopA); mark(loopB)
            // playhead
            drawRect(Color.White, Offset(fraction.coerceIn(0f, 1f) * size.width - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
        }
    }
}
