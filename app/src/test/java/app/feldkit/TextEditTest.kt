package app.feldkit

import app.feldkit.code.EditHistory
import app.feldkit.code.TextDoc
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextEditTest {
    @Test fun roundTripKeepsBomAndLineEndings() {
        for (src in listOf("plain\nunix\n", "win\r\nfile\r\n", "no newline", "")) {
            val bytes = src.toByteArray(); val d = TextDoc.decode(bytes)!!
            assertArrayEquals(src, bytes, d.encode(d.text))
        }
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "héllo\r\n".toByteArray()
        assertArrayEquals(bom, TextDoc.decode(bom)!!.let { it.encode(it.text) })
        val u16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "tab\tx\r\n".toByteArray(Charsets.UTF_16LE)
        val d = TextDoc.decode(u16)!!; assertEquals("tab\tx\n", d.text); assertArrayEquals(u16, d.encode(d.text))
    }

    @Test fun editedTextUsesTheOriginalLineEndings() {
        val d = TextDoc.decode("a\r\nb\r\n".toByteArray())!!
        assertEquals("a\r\nB\r\nc", String(d.encode("a\nB\nc")))
        assertEquals("a\nb\nx\n", String(TextDoc.decode("a\nb\n".toByteArray())!!.encode("a\nb\nx\n")))
    }

    @Test fun refusesBinaryAndInvalidAndHuge() {
        assertNull(TextDoc.decode(byteArrayOf(1, 2, 0, 3))); assertNull(TextDoc.decode(byteArrayOf(0xC3.toByte(), 0x28))); assertNull(TextDoc.decode(ByteArray(TextDoc.MAX_BYTES + 1) { 'a'.code.toByte() }))
        assertNotNull(TextDoc.decode("ok".toByteArray()))
    }

    @Test fun historyCoalescesUndoesAndRedoes() {
        val h = EditHistory("a")
        h.edit("ab", 0); h.edit("abc", 100); h.edit("abcd", 200)             // one burst
        assertEquals("a", h.undo()); assertTrue(h.canRedo); assertEquals("abcd", h.redo())
        h.edit("abcde", 5000)                                                 // later: its own step
        assertEquals("abcd", h.undo()); assertEquals("a", h.undo()); assertNull(h.undo())
        h.edit("z", 9000); assertTrue(!h.canRedo)                             // a new edit drops the redo branch
    }

    @Test fun historyIsBoundedInMemory() {
        val h = EditHistory("x", maxChars = 1000)
        for (i in 1..50) h.edit("y".repeat(i * 10), i * 10_000L)
        var steps = 0; while (h.undo() != null) steps++
        assertTrue("kept $steps steps", steps in 1..49)
    }
}
