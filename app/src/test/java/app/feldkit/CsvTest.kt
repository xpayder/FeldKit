package app.feldkit

import app.feldkit.code.Csv
import org.junit.Assert.assertEquals
import org.junit.Test

class CsvTest {
    @Test fun writeAndParseRoundTrip() {
        val rows = listOf(listOf("1", "a,b", "say \"hi\""), listOf("2", "line\nbreak", ""), listOf("3", "", "ü"))
        val text = Csv.write(listOf("id", "name", "note"), rows)
        assertEquals(listOf(listOf("id", "name", "note")) + rows, Csv.parse(text))
    }
    @Test fun detectsSeparatorsAndHandlesEndings() {
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), Csv.parse("a;b\r\n1;2"))
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), Csv.parse("a\tb\n1\t2\n"))
        assertEquals(listOf(listOf("x", ""), listOf("", "y")), Csv.parse("x,\n,y"))
    }
}
