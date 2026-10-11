package app.feldkit.code

/** Text of a file as the editor sees it, plus what is needed to write it back the way it was: BOM, encoding and line endings. */
class TextDoc(val text: String, private val bom: ByteArray, val crlf: Boolean, val utf16: Boolean, val bigEndian: Boolean) {
    /** The file's bytes for [edited] text, in the original encoding, BOM and line-ending style. */
    fun encode(edited: String): ByteArray {
        val body = edited.replace("\r\n", "\n").let { if (crlf) it.replace("\n", "\r\n") else it }
        val bytes = when { utf16 && bigEndian -> body.toByteArray(Charsets.UTF_16BE); utf16 -> body.toByteArray(Charsets.UTF_16LE); else -> body.toByteArray(Charsets.UTF_8) }
        return bom + bytes
    }

    companion object {
        const val MAX_BYTES = 2 shl 20

        /** Null when the file is not editable text (binary data, invalid UTF-8, or too big). */
        fun decode(b: ByteArray): TextDoc? {
            if (b.size > MAX_BYTES) return null
            val utf16le = b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()
            val utf16be = b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()
            val utf8bom = b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()
            val bom = when { utf16le || utf16be -> b.copyOf(2); utf8bom -> b.copyOf(3); else -> ByteArray(0) }
            val raw: String = when {
                utf16le -> String(b, 2, b.size - 2, Charsets.UTF_16LE)
                utf16be -> String(b, 2, b.size - 2, Charsets.UTF_16BE)
                else -> {
                    if (b.any { it == 0.toByte() }) return null                                  // NUL bytes: binary
                    val dec = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    try { dec.decode(java.nio.ByteBuffer.wrap(b, bom.size, b.size - bom.size)).toString() } catch (_: java.nio.charset.CharacterCodingException) { return null }
                }
            }
            val newlines = raw.count { it == '\n' }; val crlf = newlines > 0 && Regex("\r\n").findAll(raw).count() * 2 >= newlines
            return TextDoc(raw.replace("\r\n", "\n"), bom, crlf, utf16le || utf16be, utf16be)
        }
    }
}

/** Undo / redo for the editor: snapshots, coalesced so that typing a word is one step, bounded in memory. */
class EditHistory(initial: String, private val maxChars: Long = 24_000_000L) {
    private val past = ArrayDeque<String>()
    private val future = ArrayDeque<String>()
    var current: String = initial; private set
    private var lastPushMs = 0L
    private var chars = initial.length.toLong()

    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()

    /** Records [text] as the new state. Edits closer than [coalesceMs] to the previous one replace its snapshot. */
    fun edit(text: String, nowMs: Long, coalesceMs: Long = 800) {
        if (text == current) return
        if (past.isEmpty() || nowMs - lastPushMs > coalesceMs) { past.addLast(current); chars += current.length }
        current = text; lastPushMs = nowMs; future.clear()
        while (chars > maxChars && past.size > 1) chars -= past.removeFirst().length
    }

    fun undo(): String? { val p = past.removeLastOrNull() ?: return null; future.addLast(current); current = p; lastPushMs = 0; return current }
    fun redo(): String? { val f = future.removeLastOrNull() ?: return null; past.addLast(current); current = f; lastPushMs = 0; return current }
}
