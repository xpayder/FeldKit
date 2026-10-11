package app.feldkit

import app.feldkit.code.TextDoc
import org.junit.Assert.assertEquals
import org.junit.Test

class TextDisplayTest {
    @Test fun utf16LeBomIsDecoded() {
        val b = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Привет".toByteArray(Charsets.UTF_16LE)
        assertEquals("Привет", TextDoc.forDisplay(b, b.size, false))
    }
    @Test fun utf8BomIsStripped() {
        val b = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "日本".toByteArray()
        assertEquals("日本", TextDoc.forDisplay(b, b.size, false))
    }
    @Test fun cutMultiByteCharacterIsDropped() {
        val full = "aб日".toByteArray()
        assertEquals("aб", TextDoc.forDisplay(full, full.size - 1, true))
    }
}
