package app.feldkit.offload

import app.feldkit.hash.HashAlgo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Facts about a finished copy job, for the written report. */
class ReportInfo(
    val source: String,
    val device: String,
    val startedAt: Long,
    val seconds: Double,
    val destinations: List<String>,
    val algos: List<HashAlgo>,
    val verified: Boolean,
    val results: List<FileResult>,
)

/** CSV and PDF reports of a copy job: what was copied where, with sizes, checksums and the result of the read-back. */
object Report {
    private fun csvCell(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    private fun statusText(d: DestResult) = when (d.status) {
        DestStatus.VERIFIED -> "verified"; DestStatus.COPIED -> "copied (not read back)"; DestStatus.MISMATCH -> "MISMATCH"; else -> "ERROR" + (d.message?.let { ": $it" } ?: "")
    }
    private fun fmtBytes(b: Long): String = when {
        b >= 1L shl 30 -> "%.2f GB".format(Locale.US, b / 1073741824.0)
        b >= 1L shl 20 -> "%.1f MB".format(Locale.US, b / 1048576.0)
        b >= 1L shl 10 -> "%.0f KB".format(Locale.US, b / 1024.0)
        else -> "$b B"
    }
    private fun date(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

    fun csv(i: ReportInfo): String {
        val sb = StringBuilder()
        sb.append("file,size_bytes,modified")
        for (d in i.destinations) sb.append(',').append(csvCell("result: $d"))
        for (a in i.algos) sb.append(',').append(a.name.lowercase())
        sb.append('\n')
        for (r in i.results) {
            sb.append(csvCell(r.path)).append(',').append(r.size).append(',').append(if (r.modified > 0) date(r.modified) else "")
            for (d in i.destinations) sb.append(',').append(csvCell(r.error?.let { "source error: $it" } ?: r.perDest.firstOrNull { it.destination == d }?.let { statusText(it) } ?: ""))
            for (a in i.algos) sb.append(',').append(r.hashes?.get(a) ?: "")
            sb.append('\n')
        }
        return sb.toString()
    }

    class Summary(val files: Int, val bytes: Long, val ok: Int, val problems: Int)
    fun summary(i: ReportInfo) = Summary(i.results.size, i.results.sumOf { it.size }, i.results.count { it.ok }, i.results.count { !it.ok })

    /** A4 PDF: summary page header, then the file table (names shortened, one short checksum per file), paginated. */
    fun pdf(i: ReportInfo): ByteArray {
        val doc = android.graphics.pdf.PdfDocument()
        val w = 595; val h = 842; val m = 40f
        val ink = android.graphics.Paint().apply { color = 0xFF111111.toInt(); textSize = 10f; isAntiAlias = true; typeface = android.graphics.Typeface.SANS_SERIF }
        val mono = android.graphics.Paint(ink).apply { typeface = android.graphics.Typeface.MONOSPACE; textSize = 8f }
        val grey = android.graphics.Paint(ink).apply { color = 0xFF666666.toInt() }
        val red = android.graphics.Paint(ink).apply { color = 0xFFB00020.toInt() }
        val title = android.graphics.Paint(ink).apply { textSize = 18f; typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD) }
        val s = summary(i)
        var pageNo = 0
        var page: android.graphics.pdf.PdfDocument.Page? = null
        var c: android.graphics.Canvas? = null
        var y = 0f
        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(w, h, pageNo).create()); c = page!!.canvas
            c!!.drawText("FeldKit copy report", m, h - 22f, grey); c!!.drawText("page $pageNo", w - m - 40f, h - 22f, grey)
            y = m + 8
        }
        newPage()
        c!!.drawText("Copy report", m, y + 10, title); y += 34
        fun line(label: String, value: String, p: android.graphics.Paint = ink) { c!!.drawText(label, m, y, grey); c!!.drawText(value.take(90), m + 110f, y, p); y += 15f }
        line("Date", date(i.startedAt)); line("Source", i.source); line("Device", i.device)
        i.destinations.forEachIndexed { k, d -> line(if (k == 0) "Copied to" else "", d) }
        line("Checksums", i.algos.joinToString(", ") { it.name }); line("Read back", if (i.verified) "every copy compared with the source" else "not requested")
        line("Files", "${s.files}  (${fmtBytes(s.bytes)})")
        line("Time", "%.1f s, %s/s".format(Locale.US, i.seconds, fmtBytes((s.bytes * i.destinations.size.coerceAtLeast(1) / i.seconds.coerceAtLeast(0.001)).toLong())) + " written overall")
        line("Result", if (s.problems == 0) "all ${s.ok} files OK" else "${s.problems} file(s) need attention", if (s.problems == 0) ink else red)
        y += 10
        c!!.drawLine(m, y, w - m, y, grey); y += 14
        val a0 = i.algos.firstOrNull()
        c!!.drawText("File", m, y, grey); c!!.drawText("Size", m + 250f, y, grey); c!!.drawText("Result", m + 320f, y, grey); a0?.let { c!!.drawText(it.name, m + 410f, y, grey) }; y += 12
        for (r in i.results) {
            if (y > h - 50) { newPage(); c!!.drawText("File", m, y, grey); c!!.drawText("Size", m + 250f, y, grey); c!!.drawText("Result", m + 320f, y, grey); y += 14 }
            val res = r.error?.let { "source error" } ?: if (r.ok) (if (i.verified) "OK" else "copied") else r.perDest.firstOrNull { it.status == DestStatus.MISMATCH || it.status == DestStatus.ERROR }?.let { statusText(it).take(18) } ?: "check"
            var name = r.path; while (name.length > 4 && ink.measureText(name) > 245f) name = name.drop(2).let { "…" + it.drop(1) }
            val p = if (r.ok) ink else red
            c!!.drawText(name, m, y, p); c!!.drawText(fmtBytes(r.size), m + 250f, y, p); c!!.drawText(res, m + 320f, y, p)
            a0?.let { c!!.drawText((r.hashes?.get(it) ?: "").take(26), m + 410f, y, mono) }
            y += 12f
        }
        page?.let { doc.finishPage(it) }
        val out = java.io.ByteArrayOutputStream(); doc.writeTo(out); doc.close()
        return out.toByteArray()
    }

    /** Writes `FeldKit report <stamp>.csv` and `.pdf` into the job folder of every destination. */
    fun write(dests: List<Destination>, jobFolder: String?, info: ReportInfo) {
        val folder = if (jobFolder.isNullOrEmpty()) emptyList() else listOf(jobFolder)
        val stamp = Sidecars.stamp()
        val csv = try { csv(info).toByteArray() } catch (e: Exception) { android.util.Log.w("FeldKit", "csv report failed", e); null }
        val pdf = try { pdf(info) } catch (e: Throwable) { android.util.Log.w("FeldKit", "pdf report failed", e); null }
        for (d in dests) {
            for ((ext, bytes) in listOf("csv" to csv, "pdf" to pdf)) {
                if (bytes == null) continue
                try { val sink = d.dir(folder).create("FeldKit report $stamp.$ext"); sink.write(bytes, 0, bytes.size); sink.finish() } catch (e: Exception) { android.util.Log.w("FeldKit", "report not written", e) }
            }
        }
    }
}
