package app.feldkit.ui.screens

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.feldkit.code.Csv
import app.feldkit.storage.filesystem.FileSystemEntry
import app.feldkit.ui.components.GlassOutlinedButton
import app.feldkit.ui.components.ViewerPanel
import app.feldkit.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private class DbTable(val name: String, val type: String, val sql: String?)
private class Grid(val columns: List<String>, val rows: List<List<String>>, val more: Boolean)

private const val PAGE = 100
private const val MAX_DB_BYTES = 400L shl 20

private fun cellText(c: Cursor, i: Int): String = when (c.getType(i)) {
    Cursor.FIELD_TYPE_NULL -> "NULL"
    Cursor.FIELD_TYPE_BLOB -> { val b = c.getBlob(i); "BLOB ${b.size} B" + if (b.size >= 4) "  " + b.take(6).joinToString("") { "%02x".format(it) } else "" }
    Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i).toString()
    else -> c.getString(i)
}

private fun runQuery(db: SQLiteDatabase, sql: String, limit: Int, offset: Int = 0): Grid {
    // read-only database: a statement that wants to change anything fails by itself; a trailing LIMIT keeps the result bounded
    val body = sql.trim().trimEnd(';')
    val q = if (Regex("\\blimit\\b", RegexOption.IGNORE_CASE).containsMatchIn(body)) body else "$body LIMIT ${limit + 1} OFFSET $offset"
    db.rawQuery(q, null).use { c ->
        val cols = (0 until c.columnCount).map { c.getColumnName(it) }
        val rows = ArrayList<List<String>>()
        while (c.moveToNext() && rows.size < limit) rows.add((0 until c.columnCount).map { cellText(c, it) })
        return Grid(cols, rows, c.count > limit)
    }
}

/** Browser for SQLite databases (also Core Data stores): tables and views, schema, rows page by page, SELECT queries, CSV export. Read-only. */
@Composable
fun SqliteViewer(entry: FileSystemEntry, uri: Uri, onOpen: () -> Unit, onShare: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember(uri) { mutableStateOf<String?>(null) }
    var db by remember(uri) { mutableStateOf<SQLiteDatabase?>(null) }
    var tables by remember(uri) { mutableStateOf<List<DbTable>>(emptyList()) }
    var selected by remember(uri) { mutableStateOf<DbTable?>(null) }
    var mode by remember(uri) { mutableStateOf("Data") }
    var grid by remember(uri) { mutableStateOf<Grid?>(null) }
    var page by remember(uri) { mutableIntStateOf(0) }
    var query by remember(uri) { mutableStateOf("SELECT * FROM sqlite_master") }
    var queryError by remember(uri) { mutableStateOf<String?>(null) }
    var status by remember(uri) { mutableStateOf<String?>(null) }
    var rowCount by remember(uri) { mutableStateOf<Long?>(null) }

    // the drive's file is copied to the cache (SQLite needs a real file), opened read-only and removed again afterwards
    DisposableEffect(uri) {
        var tmp: File? = null
        val job = scope.launch(Dispatchers.IO) {
            try {
                if (entry.size > MAX_DB_BYTES) { error = "This database is larger than ${MAX_DB_BYTES shr 20} MB. Use \"Open with\" for it."; return@launch }
                val f = File(ctx.cacheDir, "sqlite-view-${System.nanoTime()}.db"); tmp = f
                ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o, 1 shl 16) } } ?: run { error = "Cannot read the file"; return@launch }
                val head = f.inputStream().use { it.readNBytes(16) }
                if (String(head, Charsets.ISO_8859_1) != "SQLite format 3\u0000") { error = "This is not a SQLite 3 database (it may be encrypted)."; return@launch }
                val d = SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY)
                val t = ArrayList<DbTable>()
                d.rawQuery("SELECT name, type, sql FROM sqlite_master WHERE type IN ('table','view') AND name NOT LIKE 'sqlite_%' ORDER BY type, name", null).use { c -> while (c.moveToNext()) t.add(DbTable(c.getString(0), c.getString(1), c.getString(2))) }
                tables = t; db = d; selected = t.firstOrNull()
            } catch (e: Throwable) { error = "Cannot open the database: ${e.message}" }
        }
        onDispose { job.cancel(); try { db?.close() } catch (_: Throwable) {}; tmp?.delete() }
    }

    fun quote(n: String) = "\"" + n.replace("\"", "\"\"") + "\""
    fun load(t: DbTable, p: Int) { val d = db ?: return; scope.launch { try { grid = withContext(Dispatchers.IO) { runQuery(d, "SELECT * FROM ${quote(t.name)}", PAGE, p * PAGE) }; page = p } catch (e: Throwable) { error = e.message } } }
    LaunchedEffect(selected, db) {
        val t = selected; val d = db ?: return@LaunchedEffect
        if (t != null && mode == "Data") { load(t, 0); rowCount = withContext(Dispatchers.IO) { try { d.rawQuery("SELECT COUNT(*) FROM ${quote(t.name)}", null).use { if (it.moveToFirst()) it.getLong(0) else null } } catch (_: Throwable) { null } } }
    }

    if (error != null && db == null) { OpenWithView(entry, error, onOpen, onShare, modifier); return }
    ViewerPanel(modifier) {
        if (db == null) { CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.align(Alignment.Center).size(28.dp)); return@ViewerPanel }
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Text("SQLite · ${tables.size} table${if (tables.size != 1) "s" else ""}/views · ${entry.formattedSize}", color = Accent, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            LazyRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(tables) { t ->
                    val on = t === selected
                    Text((if (t.type == "view") "view " else "") + t.name, color = if (on) DeepNavy else TextPrimary, fontSize = 13.sp, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) Accent else Fill2).clickable { selected = t; if (mode == "Query") mode = "Data" }.padding(horizontal = 12.dp, vertical = 7.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                for (m in listOf("Data", "Schema", "Query")) Text(m, color = if (mode == m) Accent else TextTertiary, fontSize = 13.sp, fontWeight = if (mode == m) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { mode = m; if (m == "Data") selected?.let { load(it, 0) } }.padding(horizontal = 10.dp, vertical = 6.dp))
                Spacer(Modifier.weight(1f))
                rowCount?.let { if (mode == "Data") Text("$it rows", color = TextTertiary, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
            }
            when (mode) {
                "Schema" -> Text(selected?.sql ?: "", color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()))
                "Query" -> Column(Modifier.fillMaxSize()) {
                    BasicTextField(query, { query = it }, textStyle = TextStyle(color = TextPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace), cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(Fill2).padding(12.dp))
                    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassOutlinedButton(onClick = {
                            val d = db ?: return@GlassOutlinedButton
                            scope.launch { try { grid = withContext(Dispatchers.IO) { runQuery(d, query, 500) }; queryError = null } catch (e: Throwable) { queryError = e.message; grid = null } }
                        }) { Text("Run", color = TextPrimary) }
                        GlassOutlinedButton(onClick = {
                            val g = grid ?: return@GlassOutlinedButton
                            scope.launch { status = withContext(Dispatchers.IO) { exportCsv(entry.name, "query", g) } }
                        }, enabled = grid != null) { Text("Export CSV", color = TextPrimary) }
                    }
                    queryError?.let { Text(it, color = AccentOrange, fontSize = 12.sp) }
                    GridView(grid, Modifier.weight(1f))
                }
                else -> Column(Modifier.fillMaxSize()) {
                    GridView(grid, Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        IconButton(onClick = { selected?.let { load(it, page - 1) } }, enabled = page > 0) { Icon(Icons.Default.ChevronLeft, "Previous page", tint = if (page > 0) TextPrimary else TextDisabled) }
                        Text("${page * PAGE + 1}–${page * PAGE + (grid?.rows?.size ?: 0)}", color = TextSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        IconButton(onClick = { selected?.let { load(it, page + 1) } }, enabled = grid?.more == true) { Icon(Icons.Default.ChevronRight, "Next page", tint = if (grid?.more == true) TextPrimary else TextDisabled) }
                        Spacer(Modifier.weight(1f))
                        GlassOutlinedButton(onClick = {
                            val d = db ?: return@GlassOutlinedButton; val t = selected ?: return@GlassOutlinedButton
                            scope.launch { status = withContext(Dispatchers.IO) { try { exportCsv(entry.name, t.name, runQuery(d, "SELECT * FROM ${quote(t.name)}", 200_000)) } catch (e: Throwable) { "Export failed: ${e.message}" } } }
                        }) { Text("Export CSV", color = TextPrimary) }
                    }
                }
            }
            status?.let { Text(it, color = Accent, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}

/** Writes the grid as CSV into Downloads/FeldKit and returns a message for the user. */
private fun exportCsv(dbName: String, what: String, g: Grid): String {
    val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "FeldKit").also { it.mkdirs() }
    val f = File(dir, "${dbName.substringBeforeLast('.')} - $what.csv")
    f.writeText(Csv.write(g.columns, g.rows.map { r -> r.map { if (it == "NULL") "" else it } }))      // NULL becomes an empty cell
    return "Saved ${g.rows.size} rows to Downloads/FeldKit/${f.name}" + if (g.more) " (the first ${g.rows.size} only)" else ""
}

@Composable
private fun GridView(g: Grid?, modifier: Modifier) {
    if (g == null) return
    // column width from the longest of the header and the first rows (monospace 12 sp is about 7.4 dp per character)
    val widths = remember(g) { g.columns.indices.map { c -> (maxOf(g.columns[c].length, g.rows.take(40).maxOfOrNull { it.getOrNull(c)?.length ?: 0 } ?: 0) * 7.4f + 20f).coerceIn(56f, 240f).dp } }
    Box(modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState())) {
        LazyColumn(Modifier.width(widths.fold(0.dp) { a, b -> a + b }.coerceAtLeast(40.dp))) {
            item {
                Row { g.columns.forEachIndexed { i, n -> Text(n, color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(widths[i]).padding(end = 8.dp, bottom = 6.dp)) } }
            }
            items(g.rows) { r ->
                Row(Modifier.padding(vertical = 3.dp)) { r.forEachIndexed { i, v -> Text(v, color = if (v == "NULL") TextDisabled else TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(widths[i]).padding(end = 8.dp)) } }
            }
            if (g.rows.isEmpty()) item { Text("No rows", color = TextTertiary, fontSize = 13.sp) }
        }
    }
}
