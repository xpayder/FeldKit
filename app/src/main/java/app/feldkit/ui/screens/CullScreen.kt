package app.feldkit.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.feldkit.photo.Xmp
import app.feldkit.provider.DriveFileProvider
import app.feldkit.storage.filesystem.FileSystemEntry
import app.feldkit.ui.components.GlassMenu
import app.feldkit.ui.components.GlassMenuItem
import app.feldkit.ui.components.Zoomable
import app.feldkit.ui.theme.*
import app.feldkit.ui.viewmodel.FileBrowserViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Rating (-1 rejected, 0 none, 1..5 stars), colour label and the "pick" flag of one photo. */
data class CullMark(val rating: Int = 0, val label: String? = null, val pick: Boolean = false)

private enum class CullFilter(val title: String) {
    ALL("All"), PICKED("Picked"), UNRATED("Not rated"), THREE("3 stars and up"), REJECTED("Rejected");
    fun matches(m: CullMark) = when (this) { ALL -> true; PICKED -> m.pick; UNRATED -> m.rating == 0; THREE -> m.rating >= 3; REJECTED -> m.rating < 0 }
}

private fun labelColor(l: String?) = when (l) { "Red" -> Color(0xFFE5645A); "Yellow" -> Color(0xFFE8C45C); "Green" -> Color(0xFF6FCB8A); "Blue" -> Color(0xFF5B9DE8); "Purple" -> Color(0xFFA77CE0); else -> Color.Transparent }

private fun prefsKey(volume: String, e: FileSystemEntry) = "$volume|${e.path}"
private fun load(p: android.content.SharedPreferences, key: String): CullMark? = p.getString(key, null)?.split(',')?.takeIf { it.size == 3 }?.let { CullMark(it[0].toIntOrNull() ?: 0, it[1].ifEmpty { null }, it[2] == "1") }
private fun save(p: android.content.SharedPreferences, key: String, m: CullMark) {
    if (m == CullMark()) p.edit().remove(key).apply() else p.edit().putString(key, "${m.rating},${m.label ?: ""},${if (m.pick) 1 else 0}").apply()
}

/**
 * Full-screen photo culling: swipe through the folder's pictures, give stars, reject, pick and label. Marks are kept in the app and,
 * when the drive can be written, also saved as XMP sidecars next to the photos (xmp:Rating / xmp:Label: read by Lightroom, Bridge,
 * Capture One). An existing sidecar is edited in place; its other settings stay untouched.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CullScreen(
    images: List<FileSystemEntry>, folder: List<FileSystemEntry>, volumeName: String, viewModel: FileBrowserViewModel, writable: Boolean,
    onSaveShown: (List<FileSystemEntry>) -> Unit, onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("cull", Context.MODE_PRIVATE) }
    val marks = remember { mutableStateMapOf<String, CullMark>() }
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf(CullFilter.ALL) }
    var filterMenu by remember { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var xmpNote by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onClose)

    // marks kept in the app first, then whatever the drive's XMP sidecars say (they are the shared truth)
    LaunchedEffect(images) {
        for (e in images) load(prefs, prefsKey(volumeName, e))?.let { marks[e.path] = it }
        val side = folder.filter { it.extension == "xmp" }.associateBy { it.name.substringBeforeLast('.').lowercase() }
        withContext(Dispatchers.IO) {
            for (e in images) {
                val s = side[e.name.substringBeforeLast('.').lowercase()] ?: continue
                val d = viewModel.readXmp(s) ?: continue
                val cur = marks[e.path] ?: CullMark()
                marks[e.path] = cur.copy(rating = d.rating ?: cur.rating, label = d.label ?: cur.label)
            }
        }
        ready = true
    }

    val shown = remember(filter, ready) { images.filter { filter.matches(marks[it.path] ?: CullMark()) } }
    val pager = rememberPagerState { shown.size.coerceAtLeast(1) }
    val cur = shown.getOrNull(pager.currentPage)
    val mark = cur?.let { marks[it.path] } ?: CullMark()

    fun change(f: (CullMark) -> CullMark, xmp: Boolean) {
        val e = cur ?: return
        val old = marks[e.path] ?: CullMark(); val n = f(old)
        marks[e.path] = n; save(prefs, prefsKey(volumeName, e), n)
        if (xmp && writable && (n.rating != old.rating || n.label != old.label)) scope.launch {
            val ok = viewModel.writeXmp(e, n.rating, n.label)
            xmpNote = if (ok) null else "Could not write the XMP sidecar"
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF06090D))) {
        if (shown.isEmpty()) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (!ready) "Reading marks…" else "No photos match this filter", color = TextTertiary, fontSize = 15.sp)
            }
        } else HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed, key = { shown[it.coerceIn(0, shown.lastIndex)].path }) { page ->
            val e = shown[page.coerceIn(0, shown.lastIndex)]
            val uri = remember(e.inodeOid, e.path) { DriveFileProvider.buildUri(e.inodeOid, e.path) }
            Zoomable(Modifier.fillMaxSize(), onTap = { chrome = !chrome }, onZoomedChange = { if (page == pager.currentPage) zoomed = it }) {
                if (e.isRaw || e.isPsd) RawImage(uri, thumb = false, modifier = Modifier.fillMaxSize(), psd = e.isPsd)
                else AsyncImage(ImageRequest.Builder(ctx).data(uri).crossfade(true).build(), e.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }

        if (chrome) {
            Row(
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp).fillMaxWidth().glassScrim(RoundedCornerShape(22.dp)).padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = TextPrimary) }
                Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                    Text("${if (shown.isEmpty()) 0 else pager.currentPage + 1} / ${shown.size}", color = TextPrimary, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
                    Text(cur?.name ?: "", color = TextTertiary, fontSize = 11.sp, maxLines = 1)
                }
                Box {
                    Text(filter.title, color = if (filter == CullFilter.ALL) TextPrimary else DeepNavy, fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (filter == CullFilter.ALL) Fill2 else Accent).clickable { filterMenu = true }.padding(horizontal = 12.dp, vertical = 8.dp))
                    if (filterMenu) GlassMenu(onDismissRequest = { filterMenu = false }) {
                        CullFilter.values().forEach { f -> GlassMenuItem(f.title + "  (${images.count { f.matches(marks[it.path] ?: CullMark()) }})", tint = if (f == filter) Accent else TextPrimary) { filter = f; filterMenu = false } }
                    }
                }
                IconButton(onClick = { onSaveShown(shown) }, enabled = shown.isNotEmpty()) { Icon(Icons.Default.Download, "Save these photos", tint = if (shown.isNotEmpty()) TextPrimary else TextDisabled) }
            }

            Column(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp).fillMaxWidth().glassScrim(RoundedCornerShape(26.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Xmp.LABELS.forEach { l ->
                        Box(
                            Modifier.size(26.dp).clip(CircleShape).background(labelColor(l)).clickable { change({ m -> m.copy(label = if (m.label == l) null else l) }, true) },
                            contentAlignment = Alignment.Center
                        ) { if (mark.label == l) Icon(Icons.Default.Check, null, tint = Color(0xFF0B1016), modifier = Modifier.size(16.dp)) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { change({ m -> m.copy(rating = if (m.rating < 0) 0 else -1) }, true) }) {
                        Icon(Icons.Default.Block, "Reject", tint = if (mark.rating < 0) AccentRed else TextSecondary, modifier = Modifier.size(28.dp))
                    }
                    for (s in 1..5) IconButton(onClick = { change({ m -> m.copy(rating = if (m.rating == s) 0 else s) }, true) }) {
                        Icon(if (mark.rating >= s) Icons.Default.Star else Icons.Outlined.StarBorder, "$s stars", tint = if (mark.rating >= s) AccentAmber else TextSecondary, modifier = Modifier.size(32.dp))
                    }
                    IconButton(onClick = { change({ m -> m.copy(pick = !m.pick) }, false) }) {
                        Icon(Icons.Default.Flag, "Pick", tint = if (mark.pick) Accent else TextSecondary, modifier = Modifier.size(28.dp))
                    }
                }
                Text(
                    xmpNote ?: if (writable) "Ratings and labels are saved as .xmp sidecars on the drive" else "The drive is read-only: marks stay in FeldKit",
                    color = if (xmpNote != null) AccentOrange else TextTertiary, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}
