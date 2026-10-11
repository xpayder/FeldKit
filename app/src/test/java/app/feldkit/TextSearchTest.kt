package app.feldkit

import app.feldkit.code.TextSearch
import org.junit.Assert.assertEquals
import org.junit.Test

class TextSearchTest {
    private val text = listOf("Hello world", "hello again, HELLO", "x1 x22 x333")
    @Test fun plainCaseAndRegex() {
        assertEquals(3, TextSearch.find(text, "hello", false, false).size)
        assertEquals(1, TextSearch.find(text, "hello", true, false).size)
        val m = TextSearch.find(text, "x\\d+", false, true); assertEquals(listOf(0, 3, 7), m.map { it.start }); assertEquals(listOf(2, 6, 11), m.map { it.end })
        assertEquals(0, TextSearch.find(text, "([", false, true).size)           // invalid regex: no crash
        assertEquals(0, TextSearch.find(text, "", false, false).size)
        assertEquals(2, TextSearch.find(listOf("aaaa"), "aa", false, false).size) // non-overlapping
    }
}
