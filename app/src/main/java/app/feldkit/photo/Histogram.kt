package app.feldkit.photo

/** RGB and luminance histograms (256 bins) of a picture, with the share of clipped shadows and highlights. */
class Histogram(val r: IntArray, val g: IntArray, val b: IntArray, val luma: IntArray, val pixels: Int) {
    /** Share (0..1) of pixels whose luminance is at the very bottom (0..2) of the range. */
    val shadowClip: Double get() = if (pixels == 0) 0.0 else (luma[0] + luma[1] + luma[2]).toDouble() / pixels
    /** Share (0..1) of pixels at the very top (253..255) of the range. */
    val highlightClip: Double get() = if (pixels == 0) 0.0 else (luma[253] + luma[254] + luma[255]).toDouble() / pixels

    companion object {
        /** From ARGB pixels (as produced by Bitmap.getPixels). Luminance uses the Rec. 709 weights. */
        fun of(argb: IntArray): Histogram {
            val r = IntArray(256); val g = IntArray(256); val b = IntArray(256); val l = IntArray(256)
            for (p in argb) {
                val rr = (p shr 16) and 0xFF; val gg = (p shr 8) and 0xFF; val bb = p and 0xFF
                r[rr]++; g[gg]++; b[bb]++
                l[((2126 * rr + 7152 * gg + 722 * bb + 5000) / 10000).coerceIn(0, 255)]++
            }
            return Histogram(r, g, b, l, argb.size)
        }
    }
}
