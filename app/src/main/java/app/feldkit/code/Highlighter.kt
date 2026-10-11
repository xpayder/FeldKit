package app.feldkit.code

/**
 * A small syntax highlighter: no dependencies, one pass over the text with a little state carried between lines (block comments,
 * triple-quoted strings). It does not parse; it recognises comments, strings, numbers, keywords, types, annotations, markup tags and
 * a few language-specific shapes (JSON / YAML keys, Markdown headings), which is what makes source readable on a phone.
 */
object Highlighter {
    enum class Kind { KEYWORD, STRING, COMMENT, NUMBER, TYPE, FUNCTION, ANNOTATION, TAG, ATTRIBUTE, KEY, HEADING, OPERATOR }
    class Span(val start: Int, val end: Int, val kind: Kind)

    private class Lang(
        val keywords: Set<String> = emptySet(),
        val lineComments: List<String> = emptyList(),
        val blockComment: Pair<String, String>? = null,
        val stringQuotes: String = "\"'",
        val tripleQuotes: Boolean = false,
        val caseInsensitive: Boolean = false,
        val backtickStrings: Boolean = false,
        val annotations: Boolean = true,
        val hashAnnotations: Boolean = false,   // "#include", "#define" and friends
        val kind: String = "code",              // code | json | markup | markdown | keyvalue
    )

    private val C_LIKE_KW = setOf("if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue", "return", "goto", "typedef", "struct", "union", "enum", "static", "const", "volatile", "extern", "inline", "sizeof", "void", "int", "long", "short", "char", "float", "double", "unsigned", "signed", "bool", "true", "false", "null", "nullptr", "class", "public", "private", "protected", "new", "delete", "this", "namespace", "using", "template", "typename", "virtual", "override", "final", "try", "catch", "throw", "auto", "operator", "friend", "explicit", "mutable", "constexpr")
    private val JAVA_KW = setOf("abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null", "var", "record", "sealed", "permits", "yield")
    private val KOTLIN_KW = setOf("abstract", "actual", "annotation", "as", "break", "by", "catch", "class", "companion", "const", "constructor", "continue", "crossinline", "data", "do", "else", "enum", "expect", "external", "false", "final", "finally", "for", "fun", "get", "if", "import", "in", "infix", "init", "inline", "inner", "interface", "internal", "is", "lateinit", "noinline", "null", "object", "open", "operator", "out", "override", "package", "private", "protected", "public", "reified", "return", "sealed", "set", "super", "suspend", "tailrec", "this", "throw", "true", "try", "typealias", "val", "var", "vararg", "when", "where", "while")
    private val PY_KW = setOf("False", "None", "True", "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif", "else", "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise", "return", "try", "while", "with", "yield", "self", "match", "case")
    private val JS_KW = setOf("async", "await", "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete", "do", "else", "export", "extends", "finally", "for", "function", "if", "import", "in", "instanceof", "let", "new", "of", "return", "static", "super", "switch", "this", "throw", "try", "typeof", "var", "void", "while", "with", "yield", "true", "false", "null", "undefined", "interface", "type", "enum", "implements", "public", "private", "protected", "readonly", "abstract", "as", "from", "namespace", "declare")
    private val RUST_KW = setOf("as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum", "extern", "false", "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod", "move", "mut", "pub", "ref", "return", "self", "Self", "static", "struct", "super", "trait", "true", "type", "unsafe", "use", "where", "while")
    private val GO_KW = setOf("break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough", "for", "func", "go", "goto", "if", "import", "interface", "map", "package", "range", "return", "select", "struct", "switch", "type", "var", "true", "false", "nil", "iota")
    private val SWIFT_KW = setOf("associatedtype", "class", "deinit", "enum", "extension", "fileprivate", "func", "import", "init", "inout", "internal", "let", "open", "operator", "private", "protocol", "public", "static", "struct", "subscript", "typealias", "var", "break", "case", "continue", "default", "defer", "do", "else", "fallthrough", "for", "guard", "if", "in", "repeat", "return", "switch", "where", "while", "as", "catch", "false", "is", "nil", "super", "self", "throw", "throws", "true", "try", "async", "await", "some", "any")
    private val SH_KW = setOf("if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case", "esac", "in", "function", "return", "exit", "local", "export", "readonly", "set", "unset", "source", "alias", "echo", "cd", "eval", "exec", "shift", "trap", "true", "false")
    private val RUBY_KW = setOf("alias", "and", "begin", "break", "case", "class", "def", "defined?", "do", "else", "elsif", "end", "ensure", "false", "for", "if", "in", "module", "next", "nil", "not", "or", "redo", "rescue", "retry", "return", "self", "super", "then", "true", "undef", "unless", "until", "when", "while", "yield", "require", "include", "attr_accessor")
    private val PHP_KW = setOf("abstract", "and", "array", "as", "break", "case", "catch", "class", "clone", "const", "continue", "declare", "default", "do", "echo", "else", "elseif", "empty", "extends", "final", "finally", "for", "foreach", "function", "global", "if", "implements", "include", "interface", "isset", "namespace", "new", "or", "print", "private", "protected", "public", "require", "return", "static", "switch", "throw", "trait", "try", "use", "var", "while", "true", "false", "null")
    private val CSHARP_KW = JAVA_KW + setOf("abstract", "as", "base", "bool", "checked", "delegate", "event", "explicit", "fixed", "foreach", "implicit", "in", "internal", "is", "lock", "namespace", "object", "operator", "out", "override", "params", "readonly", "ref", "sealed", "sizeof", "stackalloc", "string", "struct", "typeof", "unchecked", "unsafe", "using", "virtual", "async", "await", "get", "set", "partial")
    private val LUA_KW = setOf("and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto", "if", "in", "local", "nil", "not", "or", "repeat", "return", "then", "true", "until", "while")
    private val SQL_KW = setOf("select", "from", "where", "and", "or", "not", "in", "is", "null", "like", "between", "join", "left", "right", "inner", "outer", "full", "cross", "on", "as", "group", "by", "order", "having", "limit", "offset", "union", "all", "distinct", "insert", "into", "values", "update", "set", "delete", "create", "table", "alter", "drop", "index", "view", "primary", "key", "foreign", "references", "default", "unique", "check", "constraint", "begin", "commit", "rollback", "transaction", "case", "when", "then", "else", "end", "exists", "asc", "desc", "pragma", "if", "virtual", "integer", "text", "real", "blob", "varchar", "int", "boolean", "date", "timestamp")
    private val JSON_KW = setOf("true", "false", "null")
    private val MAKE_KW = setOf("ifeq", "ifneq", "ifdef", "ifndef", "else", "endif", "include", "define", "endef", "export", "override", "all", "clean", "install", ".PHONY")
    private val DOCKER_KW = setOf("FROM", "RUN", "CMD", "LABEL", "EXPOSE", "ENV", "ADD", "COPY", "ENTRYPOINT", "VOLUME", "USER", "WORKDIR", "ARG", "ONBUILD", "STOPSIGNAL", "HEALTHCHECK", "SHELL", "AS")
    private val CSS_KW = setOf("important", "inherit", "initial", "none", "auto", "block", "inline", "flex", "grid", "absolute", "relative", "fixed", "solid", "transparent")

    private val c = Lang(C_LIKE_KW, listOf("//"), "/*" to "*/", hashAnnotations = true, annotations = false)
    private val langs: Map<String, Lang> = buildMap {
        for (e in listOf("c", "cc", "cpp", "cxx", "h", "hpp", "m", "mm")) put(e, c)
        for (e in listOf("java")) put(e, Lang(JAVA_KW, listOf("//"), "/*" to "*/"))
        for (e in listOf("kt", "kts", "gradle")) put(e, Lang(KOTLIN_KW, listOf("//"), "/*" to "*/", tripleQuotes = true))
        put("cs", Lang(CSHARP_KW, listOf("//"), "/*" to "*/"))
        for (e in listOf("js", "jsx", "ts", "tsx", "mjs", "cjs")) put(e, Lang(JS_KW, listOf("//"), "/*" to "*/", backtickStrings = true))
        put("py", Lang(PY_KW, listOf("#"), null, tripleQuotes = true))
        put("rs", Lang(RUST_KW, listOf("//"), "/*" to "*/", annotations = true, stringQuotes = "\""))
        put("go", Lang(GO_KW, listOf("//"), "/*" to "*/", backtickStrings = true, annotations = false))
        put("swift", Lang(SWIFT_KW, listOf("//"), "/*" to "*/", tripleQuotes = true))
        for (e in listOf("sh", "bash", "zsh")) put(e, Lang(SH_KW, listOf("#"), null, annotations = false))
        put("rb", Lang(RUBY_KW, listOf("#"), null, annotations = false))
        put("php", Lang(PHP_KW, listOf("//", "#"), "/*" to "*/", annotations = false))
        put("lua", Lang(LUA_KW, listOf("--"), "--[[" to "]]", annotations = false))
        put("dart", Lang(JAVA_KW + setOf("final", "late", "required", "factory", "mixin", "with", "async", "await", "dynamic"), listOf("//"), "/*" to "*/"))
        put("scala", Lang(JAVA_KW + setOf("def", "val", "lazy", "object", "trait", "match", "case", "implicit", "given", "using"), listOf("//"), "/*" to "*/", tripleQuotes = true))
        put("pl", Lang(setOf("my", "our", "sub", "if", "else", "elsif", "foreach", "for", "while", "return", "use", "package", "print"), listOf("#"), null, annotations = false))
        put("r", Lang(setOf("function", "if", "else", "for", "while", "repeat", "break", "next", "return", "TRUE", "FALSE", "NULL", "NA", "in", "library"), listOf("#"), null, annotations = false))
        put("sql", Lang(SQL_KW, listOf("--"), "/*" to "*/", caseInsensitive = true, annotations = false, stringQuotes = "'\""))
        put("css", Lang(CSS_KW, emptyList(), "/*" to "*/", annotations = false))
        put("json", Lang(JSON_KW, emptyList(), null, stringQuotes = "\"", annotations = false, kind = "json"))
        for (e in listOf("xml", "html", "htm", "plist", "svg", "xhtml", "xsd")) put(e, Lang(kind = "markup", stringQuotes = "\"'"))
        for (e in listOf("md", "markdown")) put(e, Lang(kind = "markdown"))
        for (e in listOf("yaml", "yml")) put(e, Lang(setOf("true", "false", "null", "yes", "no"), listOf("#"), null, annotations = false, kind = "keyvalue"))
        put("toml", Lang(setOf("true", "false"), listOf("#"), null, annotations = false, kind = "keyvalue"))
        for (e in listOf("ini", "conf", "cfg", "properties", "env", "gitignore")) put(e, Lang(emptySet(), listOf("#", ";"), null, annotations = false, kind = "keyvalue"))
        put("makefile", Lang(MAKE_KW, listOf("#"), null, annotations = false))
        put("dockerfile", Lang(DOCKER_KW, listOf("#"), null, annotations = false))
    }

    /** Language for a file name (extension, or well-known names like Makefile / Dockerfile), or null for plain text. */
    fun languageOf(fileName: String, firstLine: String = ""): String? {
        val n = fileName.lowercase()
        val ext = n.substringAfterLast('.', "")
        if (ext in langs) return ext
        if (n == "makefile" || n.startsWith("makefile.") || n.endsWith(".mk")) return "makefile"
        if (n == "dockerfile" || n.startsWith("dockerfile.")) return "dockerfile"
        if (n == "podfile" || n == "gemfile" || n == "rakefile") return "rb"
        if (n.startsWith(".") && n.endsWith("rc")) return "sh"
        if (firstLine.startsWith("#!")) return when {
            "python" in firstLine -> "py"; "node" in firstLine -> "js"; "ruby" in firstLine -> "rb"; "perl" in firstLine -> "pl"; "php" in firstLine -> "php"
            "sh" in firstLine || "bash" in firstLine || "zsh" in firstLine -> "sh"; else -> null
        }
        return null
    }

    /** Spans per line for [lines] in language [lang] (a key from [languageOf]); empty lists for an unknown language. */
    fun highlight(lang: String?, lines: List<String>): List<List<Span>> {
        val l = langs[lang] ?: return lines.map { emptyList() }
        return when (l.kind) {
            "json" -> codeLines(l, lines, jsonKeys = true)
            "markup" -> markup(lines)
            "markdown" -> markdown(lines)
            "keyvalue" -> keyValue(l, lines)
            else -> codeLines(l, lines)
        }
    }

    private fun isIdStart(ch: Char) = ch.isLetter() || ch == '_' || ch == '$'
    private fun isId(ch: Char) = ch.isLetterOrDigit() || ch == '_' || ch == '$'

    // ── generic code ──

    private fun codeLines(l: Lang, lines: List<String>, jsonKeys: Boolean = false): List<List<Span>> {
        val out = ArrayList<List<Span>>(lines.size)
        var inBlock = false                       // inside /* ... */
        var tripleQuote: String? = null           // inside """ ... """
        var backtick = false
        for (raw in lines) {
            val line = raw.trimEnd('\r')
            val spans = ArrayList<Span>()
            var i = 0
            val n = line.length
            fun add(a: Int, b: Int, k: Kind) { if (b > a) spans.add(Span(a, b, k)) }
            while (i < n) {
                if (inBlock) {
                    val end = line.indexOf(l.blockComment!!.second, i)
                    if (end < 0) { add(i, n, Kind.COMMENT); i = n } else { add(i, end + l.blockComment.second.length, Kind.COMMENT); i = end + l.blockComment.second.length; inBlock = false }
                    continue
                }
                if (tripleQuote != null) {
                    val end = line.indexOf(tripleQuote, i)
                    if (end < 0) { add(i, n, Kind.STRING); i = n } else { add(i, end + 3, Kind.STRING); i = end + 3; tripleQuote = null }
                    continue
                }
                if (backtick) {
                    var j = i; while (j < n && line[j] != '`') { if (line[j] == '\\') j++; j++ }
                    if (j >= n) { add(i, n, Kind.STRING); i = n } else { add(i, j + 1, Kind.STRING); i = j + 1; backtick = false }
                    continue
                }
                val ch = line[i]
                // comments
                val lc = l.lineComments.firstOrNull { line.startsWith(it, i) }
                if (lc != null && !(lc == "#" && l.hashAnnotations)) { add(i, n, Kind.COMMENT); i = n; continue }
                if (l.blockComment != null && line.startsWith(l.blockComment.first, i)) {
                    val end = line.indexOf(l.blockComment.second, i + l.blockComment.first.length)
                    if (end < 0) { add(i, n, Kind.COMMENT); i = n; inBlock = true } else { add(i, end + l.blockComment.second.length, Kind.COMMENT); i = end + l.blockComment.second.length }
                    continue
                }
                // preprocessor / hash directives
                if (l.hashAnnotations && ch == '#' && line.substring(0, i).isBlank()) { var j = i + 1; while (j < n && isId(line[j])) j++; add(i, j, Kind.ANNOTATION); i = j; continue }
                // strings
                if (l.tripleQuotes && (ch == '"' || ch == '\'') && line.startsWith("$ch$ch$ch", i)) {
                    val q = "$ch$ch$ch"; val end = line.indexOf(q, i + 3)
                    if (end < 0) { add(i, n, Kind.STRING); i = n; tripleQuote = q } else { add(i, end + 3, Kind.STRING); i = end + 3 }
                    continue
                }
                if (ch == '`' && l.backtickStrings) {
                    var j = i + 1; while (j < n && line[j] != '`') { if (line[j] == '\\') j++; j++ }
                    if (j >= n) { add(i, n, Kind.STRING); i = n; backtick = true } else { add(i, j + 1, Kind.STRING); i = j + 1 }
                    continue
                }
                if (l.stringQuotes.indexOf(ch) >= 0) {
                    var j = i + 1
                    while (j < n && line[j] != ch) { if (line[j] == '\\') j++; j++ }
                    val e = minOf(j + 1, n)
                    // a quoted word followed by ":" is an object key in JSON
                    val isKey = jsonKeys && line.substring(e).trimStart().startsWith(":")
                    add(i, e, if (isKey) Kind.KEY else Kind.STRING); i = e; continue
                }
                // numbers
                if (ch.isDigit() || (ch == '.' && i + 1 < n && line[i + 1].isDigit() && (i == 0 || !isId(line[i - 1])))) {
                    if (i > 0 && isId(line[i - 1])) { i++; continue }
                    var j = i; while (j < n && (line[j].isLetterOrDigit() || line[j] == '.' || line[j] == '_')) { if ((line[j] == 'e' || line[j] == 'E') && j + 1 < n && (line[j + 1] == '-' || line[j + 1] == '+')) j++; j++ }
                    add(i, j, Kind.NUMBER); i = j; continue
                }
                // annotations / decorators
                if (l.annotations && ch == '@' && i + 1 < n && isIdStart(line[i + 1])) { var j = i + 1; while (j < n && (isId(line[j]) || line[j] == '.')) j++; add(i, j, Kind.ANNOTATION); i = j; continue }
                // identifiers
                if (isIdStart(ch)) {
                    var j = i; while (j < n && isId(line[j])) j++
                    val w = line.substring(i, j)
                    val key = if (l.caseInsensitive) w.lowercase() else w
                    when {
                        key in l.keywords -> add(i, j, Kind.KEYWORD)
                        w[0].isUpperCase() && w.length > 1 && w.any { it.isLowerCase() } -> add(i, j, Kind.TYPE)
                        j < n && line[j] == '(' -> add(i, j, Kind.FUNCTION)
                    }
                    i = j; continue
                }
                i++
            }
            out.add(spans)
        }
        return out
    }

    // ── XML / HTML ──

    private fun markup(lines: List<String>): List<List<Span>> {
        val out = ArrayList<List<Span>>(lines.size)
        var inComment = false
        for (raw in lines) {
            val line = raw.trimEnd('\r'); val spans = ArrayList<Span>(); val n = line.length; var i = 0
            fun add(a: Int, b: Int, k: Kind) { if (b > a) spans.add(Span(a, b, k)) }
            while (i < n) {
                if (inComment) { val e = line.indexOf("-->", i); if (e < 0) { add(i, n, Kind.COMMENT); i = n } else { add(i, e + 3, Kind.COMMENT); i = e + 3; inComment = false }; continue }
                if (line.startsWith("<!--", i)) { val e = line.indexOf("-->", i + 4); if (e < 0) { add(i, n, Kind.COMMENT); i = n; inComment = true } else { add(i, e + 3, Kind.COMMENT); i = e + 3 }; continue }
                if (line[i] == '<') {
                    var j = i + 1; if (j < n && (line[j] == '/' || line[j] == '?' || line[j] == '!')) j++
                    val nameStart = j; while (j < n && (isId(line[j]) || line[j] == '-' || line[j] == ':' || line[j] == '.')) j++
                    add(i, j, Kind.TAG)
                    // attributes until the tag closes
                    while (j < n && line[j] != '>') {
                        if (line[j] == '"' || line[j] == '\'') { val q = line[j]; var k = j + 1; while (k < n && line[k] != q) k++; add(j, minOf(k + 1, n), Kind.STRING); j = minOf(k + 1, n) }
                        else if (isIdStart(line[j])) { var k = j; while (k < n && (isId(line[k]) || line[k] == '-' || line[k] == ':')) k++; add(j, k, Kind.ATTRIBUTE); j = k }
                        else j++
                    }
                    if (j < n) { add(j - (if (j > 0 && line[j - 1] == '/') 1 else 0), j + 1, Kind.TAG); j++ }
                    i = maxOf(j, nameStart); continue
                }
                i++
            }
            out.add(spans)
        }
        return out
    }

    // ── Markdown ──

    private fun markdown(lines: List<String>): List<List<Span>> {
        val out = ArrayList<List<Span>>(lines.size)
        var fence = false
        for (raw in lines) {
            val line = raw.trimEnd('\r'); val spans = ArrayList<Span>()
            val t = line.trimStart()
            when {
                t.startsWith("```") || t.startsWith("~~~") -> { spans.add(Span(0, line.length, Kind.COMMENT)); fence = !fence }
                fence -> spans.add(Span(0, line.length, Kind.STRING))
                t.startsWith("#") && t.takeWhile { it == '#' }.length in 1..6 && (t.length == t.takeWhile { it == '#' }.length || t[t.takeWhile { it == '#' }.length] == ' ') -> spans.add(Span(0, line.length, Kind.HEADING))
                t.startsWith(">") -> spans.add(Span(0, line.length, Kind.COMMENT))
                else -> {
                    Regex("""^\s*([-*+]|\d+\.)\s""").find(line)?.let { spans.add(Span(it.range.first, it.range.last, Kind.OPERATOR)) }
                    for (m in Regex("""`[^`]+`""").findAll(line)) spans.add(Span(m.range.first, m.range.last + 1, Kind.STRING))
                    for (m in Regex("""\[[^\]]*]\([^)]*\)""").findAll(line)) spans.add(Span(m.range.first, m.range.last + 1, Kind.FUNCTION))
                    spans.sortBy { it.start }
                }
            }
            out.add(spans)
        }
        return out
    }

    // ── YAML / TOML / INI / .env ──

    private fun keyValue(l: Lang, lines: List<String>): List<List<Span>> {
        val out = ArrayList<List<Span>>(lines.size)
        for (raw in lines) {
            val line = raw.trimEnd('\r'); val spans = ArrayList<Span>(); val t = line.trimStart(); val ind = line.length - t.length
            when {
                l.lineComments.any { t.startsWith(it) } -> spans.add(Span(ind, line.length, Kind.COMMENT))
                t.startsWith("[") && t.contains("]") -> spans.add(Span(ind, line.length, Kind.HEADING))
                else -> {
                    var rest = ind
                    // list dash in YAML
                    if (t.startsWith("- ")) { spans.add(Span(ind, ind + 1, Kind.OPERATOR)); rest = ind + 2 }
                    val sep = Regex("""^[A-Za-z0-9_.\-"' /]+?\s*[:=]""").find(line.substring(rest))
                    var valueStart = rest
                    if (sep != null) {
                        val keyEnd = rest + sep.value.length - 1
                        spans.add(Span(rest, keyEnd, Kind.KEY)); valueStart = keyEnd + 1
                    }
                    val value = line.substring(valueStart)
                    val trimmed = value.trim(); val vOff = valueStart + (value.length - value.trimStart().length)
                    if (trimmed.isNotEmpty()) {
                        val hash = l.lineComments.firstOrNull { value.contains(" $it") }?.let { value.indexOf(" $it") + 1 }
                        val body = if (hash != null) value.substring(0, hash).trim() else trimmed
                        when {
                            body.startsWith("\"") || body.startsWith("'") -> spans.add(Span(vOff, vOff + body.length, Kind.STRING))
                            body.toDoubleOrNull() != null -> spans.add(Span(vOff, vOff + body.length, Kind.NUMBER))
                            body in l.keywords -> spans.add(Span(vOff, vOff + body.length, Kind.KEYWORD))
                        }
                        if (hash != null) spans.add(Span(valueStart + hash, line.length, Kind.COMMENT))
                    }
                }
            }
            out.add(spans)
        }
        return out
    }
}
