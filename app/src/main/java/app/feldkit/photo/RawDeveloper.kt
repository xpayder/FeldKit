package app.feldkit.photo

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import java.nio.ByteBuffer

/**
 * Real development of camera RAW files with LibRaw (decode, demosaic, camera white balance, sRGB): the "Develop" button of the RAW viewer.
 * Slower than the embedded preview but shows the actual sensor data, and works for the formats LibRaw knows (most cameras since 1990s).
 */
object RawDeveloper {
    class Result(val bitmap: Bitmap, val info: List<Pair<String, String>>, val halfSize: Boolean)

    private val loaded: Boolean by lazy { try { System.loadLibrary("feldraw"); true } catch (_: Throwable) { false } }
    /** Pictures above this many bytes are developed at half the resolution (a quarter of the memory), to fit in the phone. */
    private const val HALF_SIZE_ABOVE_BYTES = 60L shl 20

    @JvmStatic private external fun nativeDevelop(buf: ByteBuffer, length: Long, halfSize: Int, err: Array<String?>): Long
    @JvmStatic private external fun nativeSize(h: Long): IntArray
    @JvmStatic private external fun nativeInfo(h: Long): Array<String?>
    @JvmStatic private external fun nativeFill(h: Long, bitmap: Bitmap): Boolean
    @JvmStatic private external fun nativeFree(h: Long)
    @JvmStatic private external fun nativeThumb(buf: ByteBuffer, length: Long, err: Array<String?>): Long
    @JvmStatic private external fun nativeIsJpeg(h: Long): Boolean
    @JvmStatic private external fun nativeJpeg(h: Long): ByteArray

    /** Reads the whole file into native memory (a direct buffer), not the Java heap. */
    private fun readAll(ctx: Context, uri: Uri, sizeHint: Long): ByteBuffer {
        val cap = if (sizeHint > 0) sizeHint.toInt() else 160 shl 20
        var buf = ByteBuffer.allocateDirect(cap)
        ctx.contentResolver.openInputStream(uri)?.use { s ->
            val chunk = ByteArray(1 shl 20)
            while (true) {
                val n = s.read(chunk); if (n <= 0) break
                if (buf.remaining() < n) { val bigger = ByteBuffer.allocateDirect(buf.capacity() * 2); buf.flip(); bigger.put(buf); buf = bigger }
                buf.put(chunk, 0, n)
            }
        } ?: error("Cannot read the file")
        buf.flip(); return buf
    }

    /** The camera's thumbnail through LibRaw, for files whose thumbnail Android cannot show by itself; null when there is none. */
    fun thumbnail(ctx: Context, uri: Uri, sizeHint: Long): Bitmap? {
        if (!loaded) return null
        return try {
            val buf = readAll(ctx, uri, sizeHint)
            val err = arrayOfNulls<String>(1)
            val h = nativeThumb(buf, buf.limit().toLong(), err)
            if (h == 0L) return null
            try {
                if (nativeIsJpeg(h)) nativeJpeg(h).let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
                else { val (w, hh) = nativeSize(h).let { it[0] to it[1] }; Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888).also { if (!nativeFill(h, it)) return null } }
            } finally { nativeFree(h) }
        } catch (_: Throwable) { null }
    }

    /** Blocking; run on a background thread. Throws [IllegalStateException] with a readable message on failure. */
    fun develop(ctx: Context, uri: Uri, sizeHint: Long, forceHalf: Boolean = false): Result {
        check(loaded) { "The RAW engine is not available on this phone" }
        val buf = readAll(ctx, uri, sizeHint); val length = buf.limit().toLong()
        val half = forceHalf || length > HALF_SIZE_ABOVE_BYTES
        val err = arrayOfNulls<String>(1)
        val h = nativeDevelop(buf, length, if (half) 1 else 0, err)
        check(h != 0L) { err[0] ?: "Cannot develop this file" }
        try {
            val (w, hh) = nativeSize(h).let { it[0] to it[1] }
            val bmp = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888)
            check(nativeFill(h, bmp)) { "Cannot build the picture" }
            val i = nativeInfo(h)
            fun v(k: Int) = i.getOrNull(k)?.takeIf { it.isNotBlank() && it != "0" }
            val info = listOfNotNull(
                listOfNotNull(v(0), v(1)).joinToString(" ").ifEmpty { null }?.let { "Camera" to it }, v(2)?.let { "Lens" to it },
                v(3)?.let { "ISO" to it }, v(4)?.toDoubleOrNull()?.let { s -> "Shutter" to if (s >= 1) "%.1f s".format(java.util.Locale.US, s) else "1/${Math.round(1.0 / s)} s" },
                v(5)?.let { "Aperture" to "f/$it" }, v(6)?.let { "Focal length" to "$it mm" }, v(7)?.let { "Sensor data" to it },
                "Developed" to "$w × $hh" + if (half) " (half size to fit the phone's memory)" else "",
            )
            return Result(bmp, info, half)
        } finally { nativeFree(h) }
    }
}
