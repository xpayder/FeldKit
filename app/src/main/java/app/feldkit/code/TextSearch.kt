package app.feldkit.code

/** Find-in-text over a list of lines: plain text or regular expression, with or without case. */
object TextSearch {
    class Match(val line: Int, val start: Int, val end: Int)

    /** All matches (capped at [limit]); an invalid regular expression gives no matches. */
    fun find(lines: List<String>, query: String, matchCase: Boolean, regex: Boolean, limit: Int = 5000): List<Match> {
        if (query.isEmpty()) return emptyList()
        val out = ArrayList<Match>()
        val re = if (regex) try { Regex(query, if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)) } catch (_: Exception) { return emptyList() } else null
        for ((i, raw) in lines.withIndex()) {
            val line = raw.trimEnd('\r')
            if (re != null) {
                for (m in re.findAll(line)) { if (m.value.isEmpty()) continue; out.add(Match(i, m.range.first, m.range.last + 1)); if (out.size >= limit) return out }
            } else {
                var from = 0
                while (true) {
                    val k = line.indexOf(query, from, ignoreCase = !matchCase); if (k < 0) break
                    out.add(Match(i, k, k + query.length)); if (out.size >= limit) return out
                    from = k + maxOf(1, query.length)
                }
            }
        }
        return out
    }
}
