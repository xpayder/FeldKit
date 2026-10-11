package app.feldkit.code

/** A parsed document as a tree, the common shape of JSON and property lists for the tree viewer. */
class TNode(val key: String?, val kind: Kind, val text: String, val children: List<TNode> = emptyList()) {
    enum class Kind { OBJECT, ARRAY, STRING, NUMBER, BOOL, NULL, DATE, DATA }
    val isContainer get() = kind == Kind.OBJECT || kind == Kind.ARRAY
    /** "{3}" / "[12]" / the scalar's text. */
    val summary: String get() = when (kind) { Kind.OBJECT -> "{${children.size}}"; Kind.ARRAY -> "[${children.size}]"; Kind.STRING -> "\"${text}\""; else -> text }
}

class TreeError(message: String, val position: Int) : Exception(message)

/** RFC 8259 JSON parser into [TNode]s: no dependencies, accepts a UTF-8 BOM, rejects trailing garbage, limits nesting depth. */
object JsonTree {
    fun parse(text: String): TNode {
        val p = Parser(text.removePrefix("\uFEFF")); p.ws()
        val n = p.value(null, 0); p.ws()
        if (p.i < p.s.length) throw TreeError("Unexpected text after the value", p.i)
        return n
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++ }
        fun fail(m: String): Nothing = throw TreeError(m, i)

        fun value(key: String?, depth: Int): TNode {
            if (depth > 200) fail("Nested too deeply")
            if (i >= s.length) fail("Unexpected end")
            return when (val c = s[i]) {
                '{' -> {
                    i++; ws(); val kids = ArrayList<TNode>()
                    if (peek() == '}') { i++ } else while (true) {
                        ws(); if (peek() != '"') fail("Expected a key")
                        val k = string(); ws(); if (peek() != ':') fail("Expected ':'"); i++; ws()
                        kids.add(value(k, depth + 1)); ws()
                        when (peek()) { ',' -> i++; '}' -> { i++; break }; else -> fail("Expected ',' or '}'") }
                    }
                    TNode(key, TNode.Kind.OBJECT, "", kids)
                }
                '[' -> {
                    i++; ws(); val kids = ArrayList<TNode>()
                    if (peek() == ']') { i++ } else while (true) {
                        ws(); kids.add(value(kids.size.toString(), depth + 1)); ws()
                        when (peek()) { ',' -> i++; ']' -> { i++; break }; else -> fail("Expected ',' or ']'") }
                    }
                    TNode(key, TNode.Kind.ARRAY, "", kids)
                }
                '"' -> TNode(key, TNode.Kind.STRING, string())
                't' -> { lit("true"); TNode(key, TNode.Kind.BOOL, "true") }
                'f' -> { lit("false"); TNode(key, TNode.Kind.BOOL, "false") }
                'n' -> { lit("null"); TNode(key, TNode.Kind.NULL, "null") }
                else -> if (c == '-' || c.isDigit()) number(key) else fail("Unexpected character '$c'")
            }
        }

        fun peek(): Char = if (i < s.length) s[i] else '\u0000'
        fun lit(w: String) { if (!s.startsWith(w, i)) fail("Expected $w"); i += w.length }

        fun number(key: String?): TNode {
            val st = i
            if (peek() == '-') i++
            if (peek() == '0') i++ else if (peek() in '1'..'9') while (peek().isDigit()) i++ else fail("Bad number")
            if (peek() == '.') { i++; if (!peek().isDigit()) fail("Bad number"); while (peek().isDigit()) i++ }
            if (peek() == 'e' || peek() == 'E') { i++; if (peek() == '+' || peek() == '-') i++; if (!peek().isDigit()) fail("Bad number"); while (peek().isDigit()) i++ }
            return TNode(key, TNode.Kind.NUMBER, s.substring(st, i))
        }

        fun string(): String {
            i++; val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("Unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) fail("Unterminated string")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> { if (i + 4 > s.length) fail("Bad escape"); sb.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: fail("Bad escape")); i += 4 }
                            else -> fail("Bad escape")
                        }
                    }
                    c < ' ' -> fail("Control character in string")
                    else -> sb.append(c)
                }
            }
        }
    }

    /** A JSONPath-like address of [target] inside [root] (for "copy path"), e.g. `$.items[2].name`; null if not found. */
    fun pathTo(root: TNode, target: TNode): String? {
        fun go(n: TNode, path: String): String? {
            if (n === target) return path
            for (c in n.children) {
                val p = if (n.kind == TNode.Kind.ARRAY) "$path[${c.key}]" else if (c.key!!.all { it.isLetterOrDigit() || it == '_' }) "$path.${c.key}" else "$path[\"${c.key}\"]"
                go(c, p)?.let { return it }
            }
            return null
        }
        return go(root, "$")
    }
}

/** Property lists: Apple's binary `bplist00` and the XML form. */
object PlistTree {
    fun parse(bytes: ByteArray): TNode =
        if (bytes.size > 8 && String(bytes, 0, 8, Charsets.ISO_8859_1) == "bplist00") Binary(bytes).root()
        else Xml(String(bytes, Charsets.UTF_8)).root()

    private class Binary(val b: ByteArray) {
        val offSize: Int; val refSize: Int; val count: Int; val top: Int; val table: Int
        init {
            if (b.size < 40) throw TreeError("Too short for a binary plist", 0)
            val t = b.size - 32
            offSize = b[t + 6].toInt() and 0xFF; refSize = b[t + 7].toInt() and 0xFF
            count = be(t + 8, 8).toInt(); top = be(t + 16, 8).toInt(); table = be(t + 24, 8).toInt()
            if (offSize !in 1..8 || refSize !in 1..8 || count <= 0 || table + count.toLong() * offSize > b.size) throw TreeError("Damaged binary plist", t)
        }
        fun be(at: Int, n: Int): Long { var v = 0L; for (k in 0 until n) v = (v shl 8) or (b[at + k].toLong() and 0xFF); return v }
        fun offset(obj: Int): Int = be(table + obj * offSize, offSize).toInt()
        fun root() = node(top, null, 0)

        fun lenAt(p: Int, low: Int): Pair<Int, Int> {      // (length, bytes consumed after the marker)
            if (low != 0xF) return low to 0
            val m = b[p + 1].toInt() and 0xFF; val n = 1 shl (m and 0xF)
            return be(p + 2, n).toInt() to (1 + n)
        }

        fun node(obj: Int, key: String?, depth: Int): TNode {
            if (depth > 100 || obj !in 0 until count) throw TreeError("Bad object reference", obj)
            val p = offset(obj); val m = b[p].toInt() and 0xFF; val hi = m shr 4; val lo = m and 0xF
            return when (hi) {
                0x0 -> when (m) { 0x08 -> TNode(key, TNode.Kind.BOOL, "false"); 0x09 -> TNode(key, TNode.Kind.BOOL, "true"); else -> TNode(key, TNode.Kind.NULL, "null") }
                0x1 -> { val n = 1 shl lo; val v = be(p + 1, n); TNode(key, TNode.Kind.NUMBER, (if (n == 8) v else v).toString()) }
                0x2 -> { val v = if (lo == 2) java.lang.Float.intBitsToFloat(be(p + 1, 4).toInt()).toDouble() else java.lang.Double.longBitsToDouble(be(p + 1, 8)); TNode(key, TNode.Kind.NUMBER, fmt(v)) }
                0x3 -> { val secs = java.lang.Double.longBitsToDouble(be(p + 1, 8)); TNode(key, TNode.Kind.DATE, java.time.Instant.ofEpochMilli(978307200000L + (secs * 1000).toLong()).toString()) }
                0x4 -> { val (n, c) = lenAt(p, lo); TNode(key, TNode.Kind.DATA, "<${n} bytes> " + (0 until minOf(n, 16)).joinToString("") { "%02x".format(b[p + 1 + c + it]) } + if (n > 16) "…" else "") }
                0x5 -> { val (n, c) = lenAt(p, lo); TNode(key, TNode.Kind.STRING, String(b, p + 1 + c, n, Charsets.ISO_8859_1)) }
                0x6 -> { val (n, c) = lenAt(p, lo); TNode(key, TNode.Kind.STRING, String(b, p + 1 + c, n * 2, Charsets.UTF_16BE)) }
                0x8 -> TNode(key, TNode.Kind.NUMBER, "UID ${be(p + 1, lo + 1)}")
                0xA, 0xC -> { val (n, c) = lenAt(p, lo); TNode(key, TNode.Kind.ARRAY, "", (0 until n).map { node(be(p + 1 + c + it * refSize, refSize).toInt(), it.toString(), depth + 1) }) }
                0xD -> {
                    val (n, c) = lenAt(p, lo); val base = p + 1 + c
                    TNode(key, TNode.Kind.OBJECT, "", (0 until n).map { k ->
                        val kn = node(be(base + k * refSize, refSize).toInt(), null, depth + 1)
                        node(be(base + (n + k) * refSize, refSize).toInt(), kn.text, depth + 1)
                    })
                }
                else -> throw TreeError("Unknown object type 0x%02x".format(m), p)
            }
        }
        fun fmt(v: Double) = if (v == Math.rint(v) && Math.abs(v) < 1e15) "%.1f".format(java.util.Locale.US, v) else v.toString()
    }

    private class Xml(val s: String) {
        private val tok = Regex("""<!--.*?-->|<\?.*?\?>|<!DOCTYPE[^>]*>|<(/?)([A-Za-z]+)[^>]*?(/?)>|([^<]+)""", RegexOption.DOT_MATCHES_ALL)
        private val toks = tok.findAll(s).toList().filter { !it.value.startsWith("<!") && !it.value.startsWith("<?") }
        private var k = 0
        fun unescape(x: String) = x.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
        fun root(): TNode {
            while (k < toks.size && !(toks[k].groupValues[2] == "plist" && toks[k].groupValues[1].isEmpty())) k++
            if (k >= toks.size) throw TreeError("Not a property list", 0)
            k++; skipWs(); val n = value(null, 0); return n
        }
        fun skipWs() { while (k < toks.size && toks[k].groupValues[4].isNotEmpty() && toks[k].groupValues[4].isBlank()) k++ }
        fun text(): String { var out = ""; while (k < toks.size && toks[k].groupValues[4].isNotEmpty()) { out += toks[k].groupValues[4]; k++ }; return unescape(out.trim()) }
        fun close() { if (k < toks.size && toks[k].groupValues[1].isNotEmpty()) k++ }
        fun value(key: String?, depth: Int): TNode {
            if (depth > 100) throw TreeError("Nested too deeply", 0)
            skipWs(); if (k >= toks.size) throw TreeError("Unexpected end", s.length)
            val t = toks[k]; val name = t.groupValues[2]; val selfClose = t.groupValues[3].isNotEmpty(); k++
            return when (name) {
                "dict" -> { val kids = ArrayList<TNode>(); if (!selfClose) { while (true) { skipWs(); if (k >= toks.size || toks[k].groupValues[1].isNotEmpty()) break; if (toks[k].groupValues[2] != "key") throw TreeError("Expected <key>", k); k++; val kn = text(); close(); kids.add(value(kn, depth + 1)) }; close() }; TNode(key, TNode.Kind.OBJECT, "", kids) }
                "array" -> { val kids = ArrayList<TNode>(); if (!selfClose) { while (true) { skipWs(); if (k >= toks.size || toks[k].groupValues[1].isNotEmpty()) break; kids.add(value(kids.size.toString(), depth + 1)) }; close() }; TNode(key, TNode.Kind.ARRAY, "", kids) }
                "string" -> { val v = if (selfClose) "" else text().also { close() }; TNode(key, TNode.Kind.STRING, v) }
                "integer" -> { val v = text(); close(); TNode(key, TNode.Kind.NUMBER, v) }
                "real" -> { val v = text(); close(); TNode(key, TNode.Kind.NUMBER, v) }
                "true" -> TNode(key, TNode.Kind.BOOL, "true")
                "false" -> TNode(key, TNode.Kind.BOOL, "false")
                "date" -> { val v = text(); close(); TNode(key, TNode.Kind.DATE, v) }
                "data" -> { val v = text(); close(); val n = try { java.util.Base64.getMimeDecoder().decode(v).size } catch (_: Exception) { v.length * 3 / 4 }; TNode(key, TNode.Kind.DATA, "<$n bytes>") }
                else -> throw TreeError("Unexpected <$name>", k)
            }
        }
    }
}
