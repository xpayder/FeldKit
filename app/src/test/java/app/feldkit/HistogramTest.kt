package app.feldkit

import app.feldkit.photo.Histogram
import org.junit.Assert.assertEquals
import org.junit.Test

class HistogramTest {
    @Test fun clippingAndChannels() {
        val px = IntArray(1000) { when { it < 100 -> 0xFF000000.toInt(); it < 130 -> 0xFFFFFFFF.toInt(); else -> 0xFF8040C0.toInt() } }
        val h = Histogram.of(px)
        assertEquals(0.10, h.shadowClip, 1e-9); assertEquals(0.03, h.highlightClip, 1e-9)
        assertEquals(870, h.r[0x80]); assertEquals(870, h.g[0x40]); assertEquals(870, h.b[0xC0])
        assertEquals(1000, h.luma.sum())
    }
}
