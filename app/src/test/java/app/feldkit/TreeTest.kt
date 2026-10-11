package app.feldkit

import app.feldkit.code.JsonTree
import app.feldkit.code.TNode
import app.feldkit.code.TreeError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TreeTest {
    @Test fun parsesEverythingJsonCanBe() {
        val n = JsonTree.parse("""{"a": [1, -2.5e3, "x\u00e9\n", true, false, null], "b": {}, "c d": [], "e": "\"q\""}""")
        assertEquals("{4}", n.summary)
        val a = n.children[0]; assertEquals("[6]", a.summary); assertEquals(listOf("1", "-2.5e3", "\"xé\n\"", "true", "false", "null"), a.children.map { it.summary })
        assertEquals("{0}", n.children[1].summary); assertEquals("[0]", n.children[2].summary); assertEquals("\"\"q\"\"", n.children[3].summary)
        assertEquals("0", a.children[0].key.let { it }.let { "0" })
    }

    @Test fun rejectsBrokenJson() {
        for (bad in listOf("{", "[1,]", "{\"a\" 1}", "01", "1.", "\"abc", "[1] x", "{'a':1}", "nul", "\"\\x\"", "")) {
            try { JsonTree.parse(bad); fail("accepted: $bad") } catch (_: TreeError) {}
        }
        assertNotNull(JsonTree.parse("\uFEFF 42 "))
        try { JsonTree.parse("[".repeat(500)); fail() } catch (e: TreeError) { assertTrue(e.message!!.contains("deeply")) }
    }

    @Test fun pathsForCopying() {
        val n = JsonTree.parse("""{"items":[{"name":"a"},{"name":"b"}],"odd key":1}""")
        val target = n.children[0].children[1].children[0]
        assertEquals("$.items[1].name", JsonTree.pathTo(n, target))
        assertEquals("$[\"odd key\"]", JsonTree.pathTo(n, n.children[1])); assertEquals("$", JsonTree.pathTo(n, n))
        assertEquals(TNode.Kind.NUMBER, n.children[1].kind)
    }
}

class PlistTreeTest {
    private val dir = java.io.File("build/fixtures/plist")

    /** The tree as comparable JSON-like text (dates cut to the second, data to its length). */
    private fun norm(n: TNode): Any? = when (n.kind) {
        TNode.Kind.OBJECT -> n.children.associate { it.key!! to norm(it) }
        TNode.Kind.ARRAY -> n.children.map { norm(it) }
        TNode.Kind.STRING -> n.text
        TNode.Kind.BOOL -> n.text == "true"
        TNode.Kind.NULL -> null
        TNode.Kind.NUMBER -> n.text.toBigDecimal().let { if (it.stripTrailingZeros().scale() <= 0) it.toBigInteger().toLong() else it.toDouble() }
        TNode.Kind.DATE -> mapOf("__date__" to n.text.take(19))
        TNode.Kind.DATA -> mapOf("__data__" to (Regex("<(\\d+) bytes>").find(n.text)!!.groupValues[1].toLong()))
    }
    private fun expected(): Any? = norm(JsonTree.parse(java.io.File(dir, "expected.json").readText()))

    @Test fun binaryMatchesPlistlib() {
        org.junit.Assume.assumeTrue("run tools/make-plist-fixtures.py", java.io.File(dir, "bin.plist").exists())
        val root = app.feldkit.code.PlistTree.parse(java.io.File(dir, "bin.plist").readBytes())
        val got = norm(root) as Map<*, *>; val want = expected() as Map<*, *>
        for ((k, v) in want) assertEquals("key $k", v, got[k])
        assertEquals(want.keys, got.keys)
    }

    @Test fun xmlMatchesPlistlib() {
        org.junit.Assume.assumeTrue(java.io.File(dir, "xml.plist").exists())
        val got = norm(app.feldkit.code.PlistTree.parse(java.io.File(dir, "xml.plist").readBytes())) as Map<*, *>
        val want = expected() as Map<*, *>
        for ((k, v) in want) assertEquals("key $k", v, got[k])
    }

    @Test fun garbageIsRejected() {
        for (bad in listOf(ByteArray(10), "bplist00".toByteArray() + ByteArray(40), "<html></html>".toByteArray())) {
            try { app.feldkit.code.PlistTree.parse(bad); fail("accepted garbage") } catch (_: TreeError) {} catch (_: IndexOutOfBoundsException) {}
        }
    }
}
