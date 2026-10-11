package app.feldkit.code

/** CSV (RFC 4180) writing and a tolerant reader (comma, semicolon, tab; quoted fields with embedded separators and line breaks). */
object Csv {
    fun cell(s: String): String = if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    fun row(cells: List<String>): String = cells.joinToString(",") { cell(it) }
    fun write(header: List<String>, rows: List<List<String>>): String = buildString {
        append(row(header)).append("\r\n"); for (r in rows) append(row(r)).append("\r\n")
    }

    /** The separator a header line most likely uses. */
    fun detectSeparator(sample: String): Char {
        val first = sample.lineSequence().firstOrNull().orEmpty()
        return listOf(',', ';', '\t').maxByOrNull { c -> first.count { it == c } }?.takeIf { first.contains(it) } ?: ','
    }

    fun parse(text: String, sep: Char = detectSeparator(text)): List<List<String>> {
        val rows = ArrayList<List<String>>(); var cur = ArrayList<String>(); val sb = StringBuilder()
        var i = 0; var quoted = false; var any = false
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') { if (i + 1 < text.length && text[i + 1] == '"') { sb.append('"'); i++ } else quoted = false } else sb.append(c)
            } else when {
                c == '"' && sb.isEmpty() -> { quoted = true; any = true }
                c == sep -> { cur.add(sb.toString()); sb.setLength(0); any = true }
                c == '\n' || c == '\r' -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    if (any || sb.isNotEmpty() || cur.isNotEmpty()) { cur.add(sb.toString()); rows.add(cur) }
                    cur = ArrayList(); sb.setLength(0); any = false
                }
                else -> { sb.append(c); any = true }
            }
            i++
        }
        if (any || sb.isNotEmpty() || cur.isNotEmpty()) { cur.add(sb.toString()); rows.add(cur) }
        return rows
    }
}
