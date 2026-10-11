package app.feldkit.storage.filesystem.ext

/** The checksums ext4 keeps over its metadata: CRC-32C (metadata_csum) and the older CRC-16 (uninit_bg / gdt_csum). */
object ExtCrc {
    private val T32 = IntArray(256).also { t ->
        for (i in 0..255) { var c = i; repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0x82F63B78.toInt() else c ushr 1 }; t[i] = c }
    }
    private val T16 = IntArray(256).also { t ->
        for (i in 0..255) { var c = i; repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xA001 else c ushr 1 }; t[i] = c }
    }

    /** ext4's `ext4_chksum`: a raw CRC-32C update (reflected 0x82F63B78) that continues from [seed], no initial or final inversion. */
    fun crc32c(seed: Int, b: ByteArray, off: Int = 0, len: Int = b.size - off): Int {
        var c = seed
        for (i in off until off + len) c = T32[(c xor b[i].toInt()) and 0xFF] xor (c ushr 8)
        return c
    }

    fun crc32cLe32(seed: Int, v: Long): Int = crc32c(seed, byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte()))

    /** Linux `crc16` (CRC-16/ANSI, reflected 0xA001), continuing from [seed]. */
    fun crc16(seed: Int, b: ByteArray, off: Int = 0, len: Int = b.size - off): Int {
        var c = seed and 0xFFFF
        for (i in off until off + len) c = (c ushr 8) xor T16[(c xor b[i].toInt()) and 0xFF]
        return c
    }
}
