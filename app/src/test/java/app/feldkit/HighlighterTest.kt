package app.feldkit

import app.feldkit.code.Highlighter
import app.feldkit.code.Highlighter.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HighlighterTest {
    private fun kinds(lang: String, text: String): List<List<Pair<String, Kind>>> {
        val lines = text.split('\n')
        return Highlighter.highlight(lang, lines).mapIndexed { i, sp -> sp.map { lines[i].substring(it.start, it.end) to it.kind } }
    }

    @Test fun kotlinBasics() {
        val k = kinds("kt", "fun main(args: Array<String>) { // hi\n  val x = 42 + \"a\\\"b\"\n  /* multi\n line */ return\n}")
        assertEquals(listOf("fun" to Kind.KEYWORD, "main" to Kind.FUNCTION, "Array" to Kind.TYPE, "String" to Kind.TYPE, "// hi" to Kind.COMMENT), k[0])
        assertEquals(listOf("val" to Kind.KEYWORD, "42" to Kind.NUMBER, "\"a\\\"b\"" to Kind.STRING), k[1])
        assertEquals(listOf("/* multi" to Kind.COMMENT), k[2])
        assertEquals(listOf(" line */" to Kind.COMMENT, "return" to Kind.KEYWORD), k[3])
    }

    @Test fun pythonTripleQuotesAndHash() {
        val k = kinds("py", "def f(x):  # note\n    \"\"\"doc\n    more\"\"\"\n    return x1 + 0x1F")
        assertEquals("def" to Kind.KEYWORD, k[0][0]); assertEquals("# note" to Kind.COMMENT, k[0].last())
        assertEquals(listOf("\"\"\"doc" to Kind.STRING), k[1]); assertEquals(listOf("    more\"\"\"" to Kind.STRING), k[2])
        assertEquals(listOf("return" to Kind.KEYWORD, "0x1F" to Kind.NUMBER), k[3])         // x1 is an identifier, not a number
    }

    @Test fun jsonKeysAndValues() {
        val k = kinds("json", "{\"name\": \"FeldKit\", \"n\": 3, \"ok\": true}")
        assertEquals(listOf("\"name\"" to Kind.KEY, "\"FeldKit\"" to Kind.STRING, "\"n\"" to Kind.KEY, "3" to Kind.NUMBER, "\"ok\"" to Kind.KEY, "true" to Kind.KEYWORD), k[0])
    }

    @Test fun markupAndMarkdownAndYaml() {
        assertEquals(listOf("<a" to Kind.TAG, "href" to Kind.ATTRIBUTE, "\"x\"" to Kind.STRING, ">" to Kind.TAG, "</p" to Kind.TAG, ">" to Kind.TAG), kinds("html", "<a href=\"x\">hi</p>")[0].let { it })
        assertEquals(listOf("# Title" to Kind.HEADING), kinds("md", "# Title")[0])
        assertEquals(listOf("```" to Kind.COMMENT), kinds("md", "```")[0])
        val y = kinds("yml", "name: demo  # c\nitems:\n  - a: 1")
        assertEquals(listOf("name" to Kind.KEY, "# c" to Kind.COMMENT), y[0].filter { it.second != Kind.STRING })
        assertEquals(listOf("-" to Kind.OPERATOR, "a" to Kind.KEY, "1" to Kind.NUMBER), y[2])
    }

    @Test fun cDirectivesAndSqlCaseInsensitive() {
        assertEquals("#include" to Kind.ANNOTATION, kinds("c", "#include <stdio.h>")[0][0])
        assertEquals(listOf("SELECT" to Kind.KEYWORD, "FROM" to Kind.KEYWORD, "'x'" to Kind.STRING, "-- c" to Kind.COMMENT), kinds("sql", "SELECT a FROM t WHERE 'x' -- c")[0].filter { it.first != "WHERE" })
    }

    @Test fun languageDetection() {
        assertEquals("kt", Highlighter.languageOf("Main.kt")); assertEquals("makefile", Highlighter.languageOf("Makefile")); assertEquals("dockerfile", Highlighter.languageOf("Dockerfile"))
        assertEquals("py", Highlighter.languageOf("run", "#!/usr/bin/env python3")); assertNull(Highlighter.languageOf("notes.xyz"))
    }
}
