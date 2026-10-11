package app.feldkit.ui.screens

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.feldkit.code.Csv
import app.feldkit.code.Highlighter
import app.feldkit.code.JsonTree
import app.feldkit.code.Markdown
import app.feldkit.code.PlistTree
import app.feldkit.code.TNode
import app.feldkit.code.TreeError
import app.feldkit.storage.filesystem.FileSystemEntry
import app.feldkit.ui.components.ViewerPanel
import app.feldkit.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val StructuredKinds = setOf("json", "plist", "csv", "tsv", "md", "markdown")

/** JSON and property lists as a tree, CSV / TSV as a table and Markdown rendered, each with a switch to the plain source. */
@Composable
fun StructuredTextViewer(entry: FileSystemEntry, uri: Uri, kind: String, modifier: Modifier = Modifier, edit: TextEditSupport? = null) {
    var source by remember(uri) { mutableStateOf(false) }
    Box(modifier) {
        if (source) TextViewer(uri, Modifier.fillMaxSize(), lineNumbers = false, edit = edit)
        else ViewerPanel(Modifier.fillMaxSize()) {
            when (kind) {
                "json", "plist" -> TreeView(uri, kind) { source = true }
                "csv", "tsv" -> CsvView(uri, if (kind == "tsv") '\t' else null)
                else -> MarkdownView(uri)
            }
        }
        Row(Modifier.align(Alignment.TopStart).padding(start = 26.dp, top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((label, on) in listOf((if (kind == "md" || kind == "markdown") "Rendered" else if (kind == "csv" || kind == "tsv") "Table" else "Tree") to !source, "Source" to source)) {
                Text(label, color = if (on) DeepNavy else TextSecondary, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) Accent else Fill2).clickable { source = label == "Source" }.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }
}

private suspend fun readText(ctx: android.content.Context, uri: Uri, cap: Int): Pair<ByteArray, Boolean>? = withContext(Dispatchers.IO) {
    try {
        val buf = ByteArray(cap + 1); var n = 0
        ctx.contentResolver.openInputStream(uri)?.use { s -> while (n < buf.size) { val r = s.read(buf, n, buf.size - n); if (r <= 0) break; n += r } } ?: return@withContext null
        buf.copyOf(minOf(n, cap)) to (n > cap)
    } catch (_: Exception) { null }
}

@Composable
private fun Loading() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(26.dp)) }

// ── tree ──

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeView(uri: Uri, kind: String, onBad: () -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    var root by remember(uri) { mutableStateOf<TNode?>(null) }
    var problem by remember(uri) { mutableStateOf<String?>(null) }
    val expanded = remember(uri) { mutableStateMapOf<TNode, Boolean>() }
    LaunchedEffect(uri) {
        val (bytes, cut) = readText(ctx, uri, 4 shl 20) ?: run { problem = "Cannot read the file"; return@LaunchedEffect }
        if (cut) { problem = "This file is larger than 4 MB; the tree view shows smaller files. Use Source."; return@LaunchedEffect }
        try {
            val r = withContext(Dispatchers.Default) { if (kind == "plist") PlistTree.parse(bytes) else JsonTree.parse(String(bytes, Charsets.UTF_8)) }
            expanded[r] = true; r.children.take(30).forEach { if (it.isContainer) expanded[it] = true }; root = r
        } catch (e: TreeError) { problem = "Not valid ${if (kind == "plist") "property list" else "JSON"}: ${e.message}" } catch (e: Exception) { problem = "Cannot read it: ${e.message}" }
    }
    val r = root
    if (problem != null) { Column(Modifier.fillMaxSize().padding(24.dp).padding(top = 40.dp)) { Text(problem!!, color = AccentOrange, fontSize = 14.sp); TextButton(onClick = onBad) { Text("Show the source", color = Accent) } }; return }
    if (r == null) { Loading(); return }
    class Row(val node: TNode, val depth: Int)
    val rows = ArrayList<Row>()
    fun walk(n: TNode, d: Int) { rows.add(Row(n, d)); if (n.isContainer && expanded[n] == true && rows.size < 30000) n.children.forEach { walk(it, d + 1) } }
    walk(r, 0)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 52.dp, bottom = 16.dp)) {
        items(rows.size) { idx ->
            val (n, d) = rows[idx].let { it.node to it.depth }
            Row(
                Modifier.fillMaxWidth().combinedClickable(onClick = { if (n.isContainer) expanded[n] = expanded[n] != true }, onLongClick = {
                    JsonTree.pathTo(r, n)?.let { clip.setText(AnnotatedString(it)); android.widget.Toast.makeText(ctx, "Copied $it", android.widget.Toast.LENGTH_SHORT).show() }
                }).padding(start = (d * 14).dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.Top
            ) {
                if (n.isContainer) Icon(if (expanded[n] == true) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null, tint = TextTertiary, modifier = Modifier.size(18.dp)) else Spacer(Modifier.width(18.dp))
                val keyText = n.key?.let { if (n.kind != TNode.Kind.OBJECT || true) "$it: " else it } ?: ""
                val valueColor = when (n.kind) { TNode.Kind.STRING -> AccentSand; TNode.Kind.NUMBER -> AccentOrange; TNode.Kind.BOOL, TNode.Kind.NULL -> Accent; TNode.Kind.DATE -> AccentGreen; TNode.Kind.DATA -> TextTertiary; else -> TextTertiary }
                Text(buildAnnotatedString {
                    withStyle(SpanStyle(color = AccentLight)) { append(keyText) }
                    withStyle(SpanStyle(color = valueColor)) { append(n.summary.take(600)) }
                }, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

// ── CSV ──

@Composable
private fun CsvView(uri: Uri, sep: Char?) {
    val ctx = LocalContext.current
    var grid by remember(uri) { mutableStateOf<Grid?>(null) }
    var note by remember(uri) { mutableStateOf<String?>(null) }
    LaunchedEffect(uri) {
        val (bytes, cut) = readText(ctx, uri, 2 shl 20) ?: return@LaunchedEffect
        val rows = withContext(Dispatchers.Default) { val t = String(bytes, Charsets.UTF_8).removePrefix("﻿"); if (sep != null) Csv.parse(t, sep) else Csv.parse(t) }
        val width = rows.maxOfOrNull { it.size } ?: 0
        val padded = rows.map { r -> if (r.size < width) r + List(width - r.size) { "" } else r }
        val shown = padded.drop(1).take(5000)
        grid = Grid(padded.firstOrNull() ?: emptyList(), shown, false)
        note = "${rows.size - 1} rows" + if (cut) " (file cut at 2 MB)" else if (rows.size - 1 > 5000) " (first 5000 shown)" else ""
    }
    val g = grid
    if (g == null) { Loading(); return }
    Column(Modifier.fillMaxSize().padding(14.dp).padding(top = 40.dp)) {
        Text("${g.columns.size} columns · $note", color = TextTertiary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        GridView(g, Modifier.weight(1f))
    }
}

// ── Markdown ──

private fun fenceLang(l: String) = when (l.lowercase()) {
    "kotlin" -> "kt"; "python" -> "py"; "javascript" -> "js"; "typescript" -> "ts"; "bash", "shell", "zsh" -> "sh"; "yaml" -> "yml"; "c++" -> "cpp"; "csharp", "c#" -> "cs"
    "rust" -> "rs"; "ruby" -> "rb"; "golang" -> "go"; "objc", "objective-c" -> "m"; "text", "" -> ""; else -> l.lowercase()
}

private fun inlineText(text: String, base: SpanStyle = SpanStyle()): AnnotatedString = buildAnnotatedString {
    for (s in Markdown.inline(text)) {
        val st = base.merge(SpanStyle(
            fontWeight = if (s.bold) FontWeight.Bold else null, fontStyle = if (s.italic) FontStyle.Italic else null,
            fontFamily = if (s.code) FontFamily.Monospace else null, background = if (s.code) androidx.compose.ui.graphics.Color(0x26FFFFFF) else androidx.compose.ui.graphics.Color.Unspecified,
            textDecoration = if (s.strike) TextDecoration.LineThrough else null,
        ))
        if (s.link != null) withLink(LinkAnnotation.Url(s.link, TextLinkStyles(SpanStyle(color = Accent, textDecoration = TextDecoration.Underline)))) { withStyle(st) { append(s.text) } }
        else withStyle(st) { append(s.text) }
    }
}

@Composable
private fun MarkdownView(uri: Uri) {
    val ctx = LocalContext.current
    var blocks by remember(uri) { mutableStateOf<List<Markdown.Block>?>(null) }
    var cut by remember(uri) { mutableStateOf(false) }
    LaunchedEffect(uri) {
        val (bytes, c) = readText(ctx, uri, 1 shl 20) ?: return@LaunchedEffect
        cut = c; blocks = withContext(Dispatchers.Default) { Markdown.blocks(String(bytes, Charsets.UTF_8).removePrefix("﻿")) }
    }
    val b = blocks
    if (b == null) { Loading(); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 56.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(b) { blk ->
            when (blk) {
                is Markdown.Block.Heading -> Text(inlineText(blk.text), color = if (blk.level <= 2) Accent else TextPrimary, fontSize = when (blk.level) { 1 -> 24.sp; 2 -> 20.sp; 3 -> 17.sp; else -> 15.sp }, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = if (blk.level <= 2) 8.dp else 4.dp))
                is Markdown.Block.Para -> Text(inlineText(blk.text), color = TextPrimary, fontSize = 15.sp, lineHeight = 22.sp)
                is Markdown.Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) { Box(Modifier.width(3.dp).fillMaxHeight().background(Accent.copy(alpha = 0.6f))); Text(inlineText(blk.text), color = TextSecondary, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(start = 12.dp)) }
                is Markdown.Block.Item -> Row(Modifier.padding(start = (blk.depth * 18).dp)) {
                    if (blk.checked != null) Icon(if (blk.checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, null, tint = if (blk.checked) Accent else TextTertiary, modifier = Modifier.size(20.dp).padding(top = 2.dp))
                    else Text(if (blk.ordered) "${blk.number}." else "•", color = TextTertiary, fontSize = 15.sp, modifier = Modifier.width(22.dp))
                    Text(inlineText(blk.text), color = TextPrimary, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(start = if (blk.checked != null) 6.dp else 0.dp))
                }
                is Markdown.Block.Code -> {
                    val lang = Highlighter.languageOf("x." + fenceLang(blk.lang))
                    val lines = blk.text.split('\n')
                    val spans = remember(blk) { lang?.let { Highlighter.highlight(it, lines) } }
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Fill2).horizontalScroll(rememberScrollState()).padding(12.dp)) {
                        lines.forEachIndexed { i, l ->
                            Text(buildAnnotatedString { append(l.ifEmpty { " " }); spans?.getOrNull(i)?.forEach { addStyle(styleOf(it.kind), it.start.coerceAtMost(l.length.coerceAtLeast(1)), it.end.coerceAtMost(l.length.coerceAtLeast(1))) } }, color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 17.sp, softWrap = false)
                        }
                    }
                }
                is Markdown.Block.Table -> {
                    val cols = blk.header.size
                    val widths = (0 until cols).map { c -> (maxOf(blk.header[c].length, blk.rows.maxOfOrNull { it.getOrNull(c)?.length ?: 0 } ?: 0) * 8f + 24f).coerceIn(48f, 260f).dp }
                    Column(Modifier.horizontalScroll(rememberScrollState())) {
                        Row { blk.header.forEachIndexed { i, h -> Text(inlineText(h), color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(widths[i]).padding(end = 10.dp, bottom = 6.dp)) } }
                        HorizontalDivider(color = GlassBorderFaint)
                        blk.rows.forEach { r -> Row(Modifier.padding(top = 6.dp)) { r.forEachIndexed { i, c -> if (i < cols) Text(inlineText(c), color = TextPrimary, fontSize = 13.sp, modifier = Modifier.width(widths[i]).padding(end = 10.dp)) } } }
                    }
                }
                Markdown.Block.Rule -> HorizontalDivider(color = GlassBorderFaint, modifier = Modifier.padding(vertical = 6.dp))
            }
        }
        if (cut) item { Text("Showing the first 1 MB.", color = AccentOrange, fontSize = 12.sp) }
    }
}
