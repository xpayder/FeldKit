package app.feldkit.ui.screens

import app.feldkit.ui.components.GlassOutlinedButton
import app.feldkit.ui.components.GlassButton
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.feldkit.storage.filesystem.FileSystemEntry
import app.feldkit.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

private val Mono = FontFamily.Monospace

/** Plain-text / source viewer: first 512 KB, selectable, line-numbered for code. */
@Composable
fun TextViewer(uri: Uri, modifier: Modifier = Modifier, lineNumbers: Boolean, edit: TextEditSupport? = null) = app.feldkit.ui.components.ViewerPanel(modifier) { TextViewerBody(uri, Modifier.fillMaxSize(), lineNumbers, edit) }

/** What the viewer needs to offer editing of a file on a writable drive. */
class TextEditSupport(
    val entry: FileSystemEntry,
    /** Writes the new bytes safely; null on success, otherwise the reason it failed. */
    val save: suspend (ByteArray) -> String?,
    val previousVersion: () -> java.io.File?,
    val onSaved: () -> Unit,
)

@Composable
private fun TextViewerBody(uri: Uri, modifier: Modifier, lineNumbers: Boolean, edit: TextEditSupport?) {
    val ctx = LocalContext.current
    var lines by remember(uri) { mutableStateOf<List<String>?>(null) }
    var truncated by remember(uri) { mutableStateOf(false) }
    var styled by remember(uri) { mutableStateOf<List<androidx.compose.ui.text.AnnotatedString>?>(null) }
    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try {
                val cap = 512 * 1024
                val buf = ByteArray(cap); var n = 0
                ctx.contentResolver.openInputStream(uri)?.use { ins -> while (n < cap) { val r = ins.read(buf, n, cap - n); if (r <= 0) break; n += r }; truncated = ins.read() >= 0 }
                val all = String(buf, 0, n, Charsets.UTF_8).split('\n')
                lines = all
                val name = uri.getQueryParameter("path")?.substringAfterLast('/').orEmpty()
                val lang = app.feldkit.code.Highlighter.languageOf(name, all.firstOrNull().orEmpty())
                if (lang != null) styled = app.feldkit.code.Highlighter.highlight(lang, all).mapIndexed { i, spans ->
                    val line = all[i].trimEnd('\r').ifEmpty { " " }
                    androidx.compose.ui.text.buildAnnotatedString {
                        append(line)
                        for (sp in spans) addStyle(styleOf(sp.kind), sp.start.coerceAtMost(line.length), sp.end.coerceAtMost(line.length))
                    }
                }
            } catch (e: Exception) { lines = listOf("Cannot read file: ${e.message}") }
        }
    }
    val l = lines
    if (l == null) { Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }; return }

    // ── editing ──
    var doc by remember(uri) { mutableStateOf<app.feldkit.code.TextDoc?>(null) }
    var history by remember(uri) { mutableStateOf<app.feldkit.code.EditHistory?>(null) }
    var field by remember(uri) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("")) }
    var confirmSave by remember(uri) { mutableStateOf(false) }
    var confirmDiscard by remember(uri) { mutableStateOf(false) }
    var saving by remember(uri) { mutableStateOf(false) }
    var editMessage by remember(uri) { mutableStateOf<String?>(null) }
    val editScope = rememberCoroutineScope()
    val editing = doc != null
    val dirty = doc != null && history != null && history!!.current != doc!!.text
    fun startEditing() {
        editScope.launch {
            val bytes = withContext(Dispatchers.IO) { try { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (_: Exception) { null } }
            val d = bytes?.let { app.feldkit.code.TextDoc.decode(it) }
            if (d == null) editMessage = "This file cannot be edited as text (binary, not UTF-8, or too large)."
            else { doc = d; history = app.feldkit.code.EditHistory(d.text); field = androidx.compose.ui.text.input.TextFieldValue(d.text) }
        }
    }
    fun applyEdit(v: androidx.compose.ui.text.input.TextFieldValue) { field = v; history?.edit(v.text, System.currentTimeMillis()) }
    fun setFromHistory(t: String?) { if (t != null) field = androidx.compose.ui.text.input.TextFieldValue(t, androidx.compose.ui.text.TextRange(t.length)) }
    androidx.activity.compose.BackHandler(enabled = editing) { if (dirty) confirmDiscard = true else { doc = null; history = null } }

    if (editing) {
        Column(modifier) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { setFromHistory(history?.undo()) }, enabled = history?.canUndo == true) { Icon(Icons.Default.Undo, "Undo", tint = if (history?.canUndo == true) TextPrimary else TextDisabled) }
                IconButton(onClick = { setFromHistory(history?.redo()) }, enabled = history?.canRedo == true) { Icon(Icons.Default.Redo, "Redo", tint = if (history?.canRedo == true) TextPrimary else TextDisabled) }
                if (edit?.previousVersion?.invoke() != null) IconButton(onClick = {
                    edit?.previousVersion?.invoke()?.let { f -> app.feldkit.code.TextDoc.decode(f.readBytes())?.let { applyEdit(androidx.compose.ui.text.input.TextFieldValue(it.text)) } }
                }) { Icon(Icons.Default.History, "Load the version from before the last save", tint = TextSecondary) }
                Spacer(Modifier.weight(1f))
                Text(if (dirty) "edited" else "no changes", color = if (dirty) AccentAmber else TextTertiary, fontSize = 12.sp, modifier = Modifier.padding(end = 6.dp))
                IconButton(onClick = { if (dirty) confirmDiscard = true else { doc = null; history = null } }) { Icon(Icons.Default.Close, "Stop editing", tint = TextSecondary) }
                IconButton(onClick = { confirmSave = true }, enabled = dirty && !saving) { Icon(Icons.Default.Check, "Save", tint = if (dirty && !saving) Accent else TextDisabled) }
            }
            androidx.compose.foundation.text.BasicTextField(
                field, { applyEdit(it) }, textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 13.sp, fontFamily = Mono, lineHeight = 18.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent),
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
        if (confirmSave) app.feldkit.ui.components.GlassDialog(
            onDismissRequest = { confirmSave = false },
            title = { Text("Save changes?", color = TextPrimary) },
            text = { Text("\"${edit?.entry?.name}\" on the drive will be replaced. The new version is written next to it and checked byte for byte first, and the previous version stays available inside FeldKit.", color = TextSecondary) },
            confirmButton = { TextButton(onClick = {
                confirmSave = false; saving = true
                editScope.launch {
                    val msg = edit?.save?.invoke(doc!!.encode(history!!.current)) ?: "Editing is not available here"
                    saving = false
                    if (msg == null) edit?.onSaved?.invoke() else editMessage = msg
                }
            }) { Text("Save", color = Accent) } },
            dismissButton = { TextButton(onClick = { confirmSave = false }) { Text("Cancel", color = TextTertiary) } },
        )
        if (confirmDiscard) app.feldkit.ui.components.GlassDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?", color = TextPrimary) },
            text = { Text("Your edits to this file will be lost.", color = TextSecondary) },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; doc = null; history = null }) { Text("Discard", color = AccentRed) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing", color = TextTertiary) } },
        )
        editMessage?.let { m -> app.feldkit.ui.components.GlassDialog(onDismissRequest = { editMessage = null }, title = { Text("Not saved", color = TextPrimary) }, text = { Text(m, color = TextSecondary) }, confirmButton = { TextButton(onClick = { editMessage = null }) { Text("OK", color = Accent) } }) }
        return
    }
    editMessage?.let { m -> app.feldkit.ui.components.GlassDialog(onDismissRequest = { editMessage = null }, title = { Text("Cannot edit", color = TextPrimary) }, text = { Text(m, color = TextSecondary) }, confirmButton = { TextButton(onClick = { editMessage = null }) { Text("OK", color = Accent) } }) }

    // ── search ──
    var searching by remember(uri) { mutableStateOf(false) }
    var query by remember(uri) { mutableStateOf("") }
    var matchCase by remember(uri) { mutableStateOf(false) }
    var useRegex by remember(uri) { mutableStateOf(false) }
    var current by remember(uri) { mutableIntStateOf(0) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val gotoLine = remember(query) { Regex("^:(\\d+)$").find(query.trim())?.groupValues?.get(1)?.toIntOrNull() }
    val matches by produceState<List<app.feldkit.code.TextSearch.Match>>(emptyList(), l, query, matchCase, useRegex) {
        value = if (query.isEmpty() || gotoLine != null) emptyList() else withContext(Dispatchers.Default) { app.feldkit.code.TextSearch.find(l, query, matchCase, useRegex) }
        current = 0
    }
    val byLine = remember(matches) { matches.withIndex().groupBy({ it.value.line }, { it }) }
    LaunchedEffect(matches, current) { matches.getOrNull(current)?.let { listState.animateScrollToItem(it.line) } }
    LaunchedEffect(gotoLine) { gotoLine?.let { listState.animateScrollToItem((it - 1).coerceIn(0, l.lastIndex)) } }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(searching) { if (searching) focus.requestFocus() }

    Box(modifier) {
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 6.dp), state = listState, contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = if (searching) 62.dp else 52.dp, bottom = 14.dp)) {
                itemsIndexed(l) { i, line ->
                    Row {
                        if (lineNumbers) Text("${i + 1}", color = TextDisabled, fontSize = 11.sp, fontFamily = Mono, lineHeight = 17.sp, modifier = Modifier.width(36.dp).padding(end = 8.dp))
                        val plain = line.trimEnd('\r').ifEmpty { " " }
                        val base = styled?.getOrNull(i) ?: androidx.compose.ui.text.AnnotatedString(plain)
                        val hits = byLine[i]
                        val text = if (hits == null) base else androidx.compose.ui.text.buildAnnotatedString {
                            append(base)
                            for (h in hits) addStyle(androidx.compose.ui.text.SpanStyle(background = if (h.index == current) Accent.copy(alpha = 0.55f) else AccentAmber.copy(alpha = 0.30f)), h.value.start.coerceAtMost(plain.length), h.value.end.coerceAtMost(plain.length))
                        }
                        Text(text, color = TextPrimary, fontSize = 12.sp, fontFamily = Mono, lineHeight = 17.sp)
                    }
                }
                if (truncated) item { Text("Showing the first 512 KB. Use “Open with” for the full file.", color = AccentOrange, fontSize = 12.sp, modifier = Modifier.padding(vertical = 16.dp)) }
            }
        }
        if (!searching) {
            Row(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                if (edit != null && !truncated) IconButton(onClick = { startEditing() }) { Icon(Icons.Default.Edit, "Edit", tint = TextSecondary) }
                IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, "Search in file", tint = TextSecondary) }
            }
        } else {
            Row(
                Modifier.align(Alignment.TopCenter).padding(8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xE60B1016)).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    query, { query = it }, singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 14.sp, fontFamily = Mono),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent), modifier = Modifier.weight(1f).focusRequester(focus).padding(horizontal = 6.dp, vertical = 10.dp),
                    decorationBox = { inner -> if (query.isEmpty()) Text("Find, or :123 for a line", color = TextTertiary, fontSize = 14.sp); inner() }
                )
                Text(if (gotoLine != null) "line $gotoLine" else if (query.isEmpty()) "" else if (matches.isEmpty()) "0" else "${current + 1}/${matches.size}", color = TextTertiary, fontSize = 12.sp, fontFamily = Mono)
                Text("Aa", color = if (matchCase) DeepNavy else TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp).clip(RoundedCornerShape(8.dp)).background(if (matchCase) Accent else Color.Transparent).clickable { matchCase = !matchCase }.padding(horizontal = 8.dp, vertical = 6.dp))
                Text(".*", color = if (useRegex) DeepNavy else TextSecondary, fontSize = 12.sp, fontFamily = Mono, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (useRegex) Accent else Color.Transparent).clickable { useRegex = !useRegex }.padding(horizontal = 8.dp, vertical = 6.dp))
                IconButton(onClick = { if (matches.isNotEmpty()) current = (current - 1 + matches.size) % matches.size }, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.KeyboardArrowUp, "Previous", tint = TextPrimary) }
                IconButton(onClick = { if (matches.isNotEmpty()) current = (current + 1) % matches.size }, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.KeyboardArrowDown, "Next", tint = TextPrimary) }
                IconButton(onClick = { searching = false; query = "" }, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.Close, "Close search", tint = TextSecondary) }
            }
        }
    }
}

internal fun styleOf(k: app.feldkit.code.Highlighter.Kind): androidx.compose.ui.text.SpanStyle = when (k) {
    app.feldkit.code.Highlighter.Kind.KEYWORD -> androidx.compose.ui.text.SpanStyle(color = Accent)
    app.feldkit.code.Highlighter.Kind.STRING -> androidx.compose.ui.text.SpanStyle(color = AccentSand)
    app.feldkit.code.Highlighter.Kind.COMMENT -> androidx.compose.ui.text.SpanStyle(color = TextTertiary, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
    app.feldkit.code.Highlighter.Kind.NUMBER -> androidx.compose.ui.text.SpanStyle(color = AccentOrange)
    app.feldkit.code.Highlighter.Kind.TYPE -> androidx.compose.ui.text.SpanStyle(color = AccentLight)
    app.feldkit.code.Highlighter.Kind.FUNCTION -> androidx.compose.ui.text.SpanStyle(color = AccentGreen)
    app.feldkit.code.Highlighter.Kind.ANNOTATION -> androidx.compose.ui.text.SpanStyle(color = AccentAmber)
    app.feldkit.code.Highlighter.Kind.TAG -> androidx.compose.ui.text.SpanStyle(color = Accent)
    app.feldkit.code.Highlighter.Kind.ATTRIBUTE -> androidx.compose.ui.text.SpanStyle(color = AccentLight)
    app.feldkit.code.Highlighter.Kind.KEY -> androidx.compose.ui.text.SpanStyle(color = AccentLight)
    app.feldkit.code.Highlighter.Kind.HEADING -> androidx.compose.ui.text.SpanStyle(color = Accent, fontWeight = FontWeight.SemiBold)
    app.feldkit.code.Highlighter.Kind.OPERATOR -> androidx.compose.ui.text.SpanStyle(color = AccentAmber)
}

/** PDF viewer on [PdfRenderer]: pages rendered on demand, pinch to change the zoom, scrolls both ways. */
@Composable
fun PdfViewer(uri: Uri, modifier: Modifier = Modifier, onFail: () -> Unit) {
    val ctx = LocalContext.current
    var renderer by remember(uri) { mutableStateOf<PdfRenderer?>(null) }
    val lock = remember { Mutex() }
    var zoom by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try { renderer = ctx.contentResolver.openFileDescriptor(uri, "r")?.let { PdfRenderer(it) } } catch (e: Exception) { onFail() }
        }
    }
    DisposableEffect(uri) { onDispose { try { renderer?.close() } catch (_: Exception) {} } }
    val r = renderer
    if (r == null) { Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }; return }
    BoxWithConstraints(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    if (ev.changes.count { it.pressed } > 1) {
                        zoom = (zoom * ev.calculateZoom()).coerceIn(1f, 3f)
                        ev.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                } while (ev.changes.any { it.pressed })
            }
        }
    ) {
        val density = LocalDensity.current
        val pageW = (maxWidth - 24.dp) * zoom
        val px = with(density) { pageW.roundToPx() }
        Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
            LazyColumn(Modifier.padding(horizontal = 12.dp).width(pageW), contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(r.pageCount) { index ->
                    var bmp by remember(index, px) { mutableStateOf<Bitmap?>(null) }
                    var ratio by remember(index) { mutableFloatStateOf(0.707f) }
                    LaunchedEffect(index, px) {
                        withContext(Dispatchers.IO) {
                            lock.withLock {
                                try {
                                    r.openPage(index).use { page ->
                                        ratio = page.width.toFloat() / page.height
                                        val b = Bitmap.createBitmap(px, (px / ratio).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                                        b.eraseColor(AColor.WHITE)
                                        page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                        bmp = b
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(10.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                        bmp?.let { Image(it.asImageBitmap(), "Page ${index + 1}", Modifier.fillMaxSize()) } ?: CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
        app.feldkit.ui.components.GlassPill("${(zoom * 100).toInt()}%  ·  ${r.pageCount} page${if (r.pageCount != 1) "s" else ""}", Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp))
    }
}

/** Card for formats FeldKit can't render itself (office documents, rar/7z/dmg, unknown types): hand them to another app. */
@Composable
fun OpenWithView(entry: FileSystemEntry, hint: String?, onOpen: () -> Unit, onShare: () -> Unit, modifier: Modifier = Modifier, onHex: (() -> Unit)? = null) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(88.dp).glassOrSolid(RoundedCornerShape(26.dp), GlassLevel.Card), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.InsertDriveFile, null, tint = Accent, modifier = Modifier.size(42.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text(entry.name, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text("${entry.extension.uppercase().ifEmpty { "FILE" }} · ${entry.formattedSize}", color = TextTertiary, fontSize = 13.sp, fontFamily = Mono)
        if (hint != null) { Spacer(Modifier.height(10.dp)); Text(hint, color = TextSecondary, fontSize = 13.sp) }
        Spacer(Modifier.height(24.dp))
        GlassButton(onClick = onOpen, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = DeepNavy)) {
            Icon(Icons.Default.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text("Open with…", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(10.dp))
        GlassOutlinedButton(onClick = onShare, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Icon(Icons.Default.Share, null, tint = TextPrimary); Spacer(Modifier.width(8.dp)); Text("Share", color = TextPrimary)
        }
        if (onHex != null) TextButton(onClick = onHex) { Text("View as hex", color = Accent) }
    }
}

/** Overview of a music project (Ableton Live, FL Studio, REAPER): DAW and version, tempo, tracks, plug-ins and samples, without the DAW. */
@Composable
fun ProjectViewer(entry: FileSystemEntry, uri: Uri, onOpen: () -> Unit, onShare: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var loaded by remember(uri) { mutableStateOf(false) }
    val info by produceState<app.feldkit.audio.DawProject.Info?>(null, uri) { value = withContext(Dispatchers.IO) { app.feldkit.audio.DawProject.read(ctx, uri) }; loaded = true }
    val i = info
    if (loaded && i == null) { OpenWithView(entry, "FeldKit could not read the inside of this project.", onOpen, onShare, modifier); return }
    app.feldkit.ui.components.ViewerPanel(modifier) {
        if (i == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(12.dp)); Text("Reading the project…", color = TextTertiary, fontSize = 13.sp)
            }
        } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp)) {
            item {
                Text(i.daw + (i.version?.let { "  $it" } ?: ""), color = Accent, fontSize = 13.sp, fontFamily = Mono)
                Text(entry.name.substringBeforeLast('.'), color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    app.feldkit.audio.DawProject.tempoText(i)?.let { Stat("$it BPM") }
                    i.meter?.let { Stat(it) }
                    Stat(entry.formattedSize)
                }
                Spacer(Modifier.height(10.dp))
            }
            i.sections.forEach { sec ->
                item(key = sec.title) { Text(sec.title.uppercase(), color = Accent, fontSize = 11.sp, fontFamily = Mono, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)) }
                items(sec.items) { line -> Text(line, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.padding(vertical = 3.dp)) }
            }
            item {
                Spacer(Modifier.height(18.dp))
                GlassOutlinedButton(onClick = onOpen, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(48.dp)) { Icon(Icons.Default.OpenInNew, null, tint = TextPrimary); Spacer(Modifier.width(8.dp)); Text("Open with…", color = TextPrimary) }
            }
        }
    }
}

@Composable
private fun Stat(text: String) {
    Text(text, color = TextPrimary, fontSize = 13.sp, fontFamily = Mono, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Fill2).padding(horizontal = 10.dp, vertical = 6.dp))
}
