package app.feldkit

import app.feldkit.code.Markdown
import app.feldkit.code.Markdown.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    @Test fun blocks() {
        val b = Markdown.blocks("# Title\n\nSome *text*\nacross lines.\n\n- one\n  - nested\n1. first\n- [x] done\n\n> quoted\n> more\n\n```kotlin\nval x = 1\n```\n\n---\n\n| a | b |\n|---|:-:|\n| 1 | 2 |\n| 3 |\n\nSetext\n===")
        assertEquals(listOf("Heading", "Para", "Item", "Item", "Item", "Item", "Quote", "Code", "Rule", "Table", "Heading"), b.map { it::class.simpleName })
        assertEquals("Some *text* across lines.", (b[1] as Block.Para).text)
        val nested = b[3] as Block.Item; assertEquals(1, nested.depth); assertEquals("nested", nested.text)
        assertTrue((b[4] as Block.Item).ordered); assertEquals(true, (b[5] as Block.Item).checked)
        assertEquals("quoted\nmore", (b[6] as Block.Quote).text)
        assertEquals("kotlin" to "val x = 1", (b[7] as Block.Code).let { it.lang to it.text })
        val t = b[9] as Block.Table; assertEquals(listOf("a", "b"), t.header); assertEquals(listOf(listOf("1", "2"), listOf("3", "")), t.rows)
        assertEquals(1, (b[10] as Block.Heading).level)
    }

    @Test fun inline() {
        val s = Markdown.inline("a **bold** and *it* `code` ~~gone~~ [link](http://x.y) <http://z> \\* ![pic](a/b.png)")
        assertEquals(listOf("a ", "bold", " and ", "it", " ", "code", " ", "gone", " ", "link", " ", "http://z", " * [image: pic]"), s.map { it.text })
        assertTrue(s[1].bold); assertTrue(s[3].italic); assertTrue(s[5].code); assertTrue(s[7].strike); assertEquals("http://x.y", s[9].link); assertEquals("http://z", s[11].link)
        assertEquals(listOf("snake_case_name"), Markdown.inline("snake_case_name").map { it.text })      // underscores inside words stay
        assertEquals(listOf("2 * 3 = 6"), Markdown.inline("2 * 3 = 6").map { it.text })
    }
}
