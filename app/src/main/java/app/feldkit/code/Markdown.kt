package app.feldkit.code

/** A small CommonMark / GFM subset: enough to read READMEs and notes on a phone (headings, lists, tasks, quotes, code, tables, emphasis, links). */
object Markdown {
    sealed class Block {
        class Heading(val level: Int, val text: String) : Block()
        class Para(val text: String) : Block()
        class Code(val lang: String, val text: String) : Block()
        class Quote(val text: String) : Block()
        class Item(val ordered: Boolean, val number: Int, val depth: Int, val text: String, val checked: Boolean?) : Block()
        class Table(val header: List<String>, val rows: List<List<String>>) : Block()
        object Rule : Block()
    }

    class Seg(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false, val strike: Boolean = false, val link: String? = null)

    private val ITEM = Regex("""^(\s*)([-*+]|\d+[.)])\s+(.*)$""")
    private val RULE = Regex("""^\s{0,3}([-*_])(\s*\1){2,}\s*$""")
    private val TABLE_SEP = Regex("""^\s*\|?\s*:?-{1,}:?\s*(\|\s*:?-{1,}:?\s*)*\|?\s*$""")

    private fun cells(line: String): List<String> = line.trim().removePrefix("|").removeSuffix("|").split(Regex("(?<!\\\\)\\|")).map { it.trim().replace("\\|", "|") }

    fun blocks(text: String): List<Block> {
        val lines = text.replace("\r\n", "\n").split('\n')
        val out = ArrayList<Block>(); var i = 0
        val para = StringBuilder()
        fun flush() { if (para.isNotBlank()) out.add(Block.Para(para.toString().trim())); para.setLength(0) }
        while (i < lines.size) {
            val line = lines[i]; val t = line.trimStart()
            when {
                t.startsWith("```") || t.startsWith("~~~") -> {
                    flush(); val fence = t.take(3); val lang = t.drop(3).trim().substringBefore(' ')
                    val body = StringBuilder(); i++
                    while (i < lines.size && !lines[i].trimStart().startsWith(fence)) { body.append(lines[i]).append('\n'); i++ }
                    out.add(Block.Code(lang, body.toString().trimEnd('\n')))
                }
                line.isBlank() -> flush()
                Regex("^#{1,6}(\\s|$)").containsMatchIn(t) && line.length - t.length < 4 -> { flush(); val lv = t.takeWhile { it == '#' }.length; out.add(Block.Heading(lv, t.drop(lv).trim().trimEnd('#').trim())) }
                RULE.matches(line) && !(para.isNotEmpty() && t.startsWith("-") && !t.contains(' ')) -> { flush(); out.add(Block.Rule) }
                (t.startsWith("===") || (t.startsWith("---") && t.all { it == '-' })) && para.isNotBlank() -> { out.add(Block.Heading(if (t.startsWith("=")) 1 else 2, para.toString().trim())); para.setLength(0) }
                t.startsWith(">") -> { flush(); val q = StringBuilder(); while (i < lines.size && lines[i].trimStart().startsWith(">")) { q.append(lines[i].trimStart().removePrefix(">").removePrefix(" ")).append('\n'); i++ }; i--; out.add(Block.Quote(q.toString().trim())) }
                line.contains('|') && i + 1 < lines.size && TABLE_SEP.matches(lines[i + 1]) && lines[i + 1].contains('-') -> {
                    flush(); val header = cells(line); val rows = ArrayList<List<String>>(); i += 2
                    while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) { rows.add(cells(lines[i]).let { r -> if (r.size < header.size) r + List(header.size - r.size) { "" } else r }); i++ }
                    i--; out.add(Block.Table(header, rows))
                }
                ITEM.matches(line) && !RULE.matches(line) -> {
                    flush(); val m = ITEM.find(line)!!; val marker = m.groupValues[2]; var body = m.groupValues[3]; var checked: Boolean? = null
                    Regex("""^\[([ xX])]\s+(.*)$""").find(body)?.let { checked = it.groupValues[1] != " "; body = it.groupValues[2] }
                    out.add(Block.Item(marker[0].isDigit(), marker.takeWhile { it.isDigit() }.toIntOrNull() ?: 0, m.groupValues[1].length / 2, body, checked))
                }
                t.startsWith("<!--") -> { while (i < lines.size && !lines[i].contains("-->")) i++ }
                else -> { if (para.isNotEmpty()) para.append(' '); para.append(t) }
            }
            i++
        }
        flush(); return out
    }

    /** Inline spans of one paragraph: `code`, **bold**, *italic*, ~~strike~~, [links](url), <autolinks>, images as their alt text. */
    fun inline(src: String): List<Seg> {
        val out = ArrayList<Seg>(); val sb = StringBuilder()
        var bold = false; var italic = false; var strike = false
        fun emit(link: String? = null) { if (sb.isNotEmpty()) { out.add(Seg(sb.toString(), bold, italic, false, strike, link)); sb.setLength(0) } }
        var i = 0; val n = src.length
        while (i < n) {
            val c = src[i]
            when {
                c == '\\' && i + 1 < n && src[i + 1] in "\\`*_{}[]()#+-.!|~<>" -> { sb.append(src[i + 1]); i += 2 }
                c == '`' -> { val e = src.indexOf('`', i + 1); if (e > i) { emit(); out.add(Seg(src.substring(i + 1, e), code = true)); i = e + 1 } else { sb.append(c); i++ } }
                c == '!' && src.startsWith("![", i) -> { val m = Regex("""^!\[([^\]]*)]\(([^)]*)\)""").find(src.substring(i)); if (m != null) { sb.append("[image: ").append(m.groupValues[1].ifEmpty { m.groupValues[2].substringAfterLast('/') }).append("]"); i += m.value.length } else { sb.append(c); i++ } }
                c == '[' -> { val m = Regex("""^\[([^\]]*)]\(([^)\s]*)(?:\s+"[^"]*")?\)""").find(src.substring(i)); if (m != null) { emit(); out.add(Seg(m.groupValues[1].ifEmpty { m.groupValues[2] }, bold, italic, false, strike, m.groupValues[2])); i += m.value.length } else { sb.append(c); i++ } }
                c == '<' -> { val m = Regex("""^<((?:https?://|mailto:)[^>\s]+)>""").find(src.substring(i)); if (m != null) { emit(); out.add(Seg(m.groupValues[1], link = m.groupValues[1])); i += m.value.length } else { sb.append(c); i++ } }
                src.startsWith("**", i) || src.startsWith("__", i) -> { if (bold || src.indexOf(src.substring(i, i + 2), i + 2) > 0) { emit(); bold = !bold }; i += 2 }
                src.startsWith("~~", i) -> { if (strike || src.indexOf("~~", i + 2) > 0) { emit(); strike = !strike }; i += 2 }
                (c == '*' || (c == '_' && (i == 0 || !src[i - 1].isLetterOrDigit()))) -> { if (italic || src.indexOf(c, i + 1) > 0) { emit(); italic = !italic } else sb.append(c); i++ }
                else -> { sb.append(c); i++ }
            }
        }
        emit(); return out
    }
}
