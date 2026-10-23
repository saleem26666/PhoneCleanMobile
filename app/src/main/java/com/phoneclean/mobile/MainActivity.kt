package com.phoneclean.mobile

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.media.MediaScannerConnection
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import android.net.Uri
import android.widget.ImageView
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF3B82F6), background = Color(0xFF0B1220))) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF0B1220)) {
                    PhoneCleanApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhoneCleanApp() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var scopeKey by remember { mutableStateOf("both") }
    var status by remember { mutableStateOf("Allow All files, phir Scan.") }
    var busy by remember { mutableStateOf(false) }
    var folderKind by remember { mutableStateOf("") }
    val all = remember { mutableStateListOf<PhoneFile>() }
    val shown = remember { mutableStateListOf<PhoneFile>() }
    val selected = remember { mutableStateListOf<String>() }
    val folders = remember { mutableStateListOf<FolderRow>() }
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var dupeMode by remember { mutableStateOf(false) }
    var workJob by remember { mutableStateOf<Job?>(null) }
    var preview by remember { mutableStateOf<PhoneFile?>(null) }
    var showMenu by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        ctx.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        val dest = DocumentFile.fromTreeUri(ctx, uri)
        if (dest == null) {
            Toast.makeText(ctx, "Folder nahi khula", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            busy = true
            var ok = 0
            selected.toList().forEach { path ->
                val src = File(path)
                if (!src.exists()) return@forEach
                val mime = mimeOf(src)
                val out = dest.createFile(mime, src.name) ?: return@forEach
                try {
                    ctx.contentResolver.openOutputStream(out.uri)?.use { os ->
                        FileInputStream(src).use { it.copyTo(os) }
                    }
                    ok++
                } catch (_: Exception) {
                }
            }
            busy = false
            status = "Copy done — $ok files"
            Toast.makeText(ctx, status, Toast.LENGTH_LONG).show()
        }
    }

    fun active(): List<PhoneFile> {
        var rows = all.toList()
        if (scopeKey != "both") rows = rows.filter { it.scope == scopeKey }
        selectedFolder?.let { rows = Engine.filesInFolders(rows, listOf(it)) }
        if (folderKind.isNotEmpty() && selectedFolder == null) {
            rows = rows.filter { Engine.folderKind(it.file.parent ?: "") == folderKind }
        }
        return Engine.filterQuery(rows, query)
    }

    fun refreshShown(extra: List<PhoneFile>? = null, asDupes: Boolean = false) {
        dupeMode = asDupes
        shown.clear()
        shown.addAll(extra ?: active())
    }

    fun transferSelected(toSd: Boolean, cut: Boolean) {
        val paths = selected.toList()
        if (paths.isEmpty()) {
            Toast.makeText(ctx, "Pehle files select karo", Toast.LENGTH_SHORT).show()
            return
        }
        if (toSd && Engine.sdRoot() == null) {
            Toast.makeText(ctx, "SD card nahi mili", Toast.LENGTH_LONG).show()
            return
        }
        if (busy) return
        val label = buildString {
            append(if (cut) "Cut" else "Copy")
            append(if (toSd) " Device → SD" else " SD → Phone")
        }
        scope.launch {
            busy = true
            status = "$label in progress…"
            val result = withContext(Dispatchers.IO) {
                Engine.transferDeviceSd(paths, toSd, cut) { status = it }
            }
            if (result.dests.isNotEmpty()) {
                MediaScannerConnection.scanFile(
                    ctx,
                    result.dests.map { it.path }.toTypedArray(),
                    null,
                    null,
                )
            }
            if (result.removed.isNotEmpty()) {
                val gone = result.removed.toSet()
                all.removeAll { it.path in gone }
            }
            result.dests.forEach { dest ->
                if (all.none { it.path == dest.path }) all.add(dest)
            }
            selected.clear()
            folders.clear()
            folders.addAll(Engine.collectFolders(all, scopeKey))
            refreshShown()
            val where = if (toSd) "SD card" else "Device"
            status = "$label done — ${result.ok} files $where pe"
            if (result.errors.isNotEmpty()) {
                status += "  |  ${result.errors.size} skip"
            }
            busy = false
            Toast.makeText(ctx, status, Toast.LENGTH_LONG).show()
        }
    }

    fun stopWork() {
        Engine.cancel()
        workJob?.cancel()
        busy = false
        status = "Stopped — Stop daba diya. Dobara Search/Scan karo."
    }

    fun askPermission() {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            try {
                ctx.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${ctx.packageName}"),
                    ),
                )
            } catch (_: Exception) {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
        val perms = mutableListOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT <= 29) perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= 33) {
            perms += listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
            )
        }
        permLauncher.launch(perms.toTypedArray())
    }

    fun hasStorage(): Boolean {
        return if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    fun doSearch() {
        if (!hasStorage()) {
            status = "Pehle Allow files — All files access ON karo"
            Toast.makeText(ctx, status, Toast.LENGTH_LONG).show()
            askPermission()
            return
        }
        val q = query.trim()
        Engine.cancel()
        workJob?.cancel()
        Engine.begin()
        busy = true
        status = if (q.isEmpty()) "Search in progress (saari files)…" else "Search in progress: $q"
        shown.clear()
        workJob = scope.launch {
            val indexed = if (all.isNotEmpty()) Engine.filterQuery(active(), q) else emptyList()
            if (indexed.isNotEmpty()) {
                shown.clear()
                shown.addAll(indexed.take(2000))
                status = "Index se ${indexed.size} — phone se confirm…"
            }
            val files = withContext(Dispatchers.IO) {
                MediaScan.fastSearch(ctx, q, scopeKey, { Engine.cancelled }) { msg, batch ->
                    scope.launch(Dispatchers.Main) {
                        status = msg
                        if (batch.isNotEmpty()) {
                            shown.clear()
                            shown.addAll(batch.take(2000))
                        }
                    }
                }
            }
            val merged = LinkedHashMap<String, PhoneFile>()
            indexed.forEach { merged[it.path] = it }
            files.forEach { f ->
                val old = merged[f.path]
                merged[f.path] = f.copy(mediaUri = f.mediaUri ?: old?.mediaUri)
            }
            val result = merged.values.toList()
            if (Engine.cancelled) {
                if (result.isNotEmpty()) {
                    result.forEach { f -> if (all.none { it.path == f.path }) all.add(f) }
                    refreshShown(result)
                }
                status = "Stopped — ${result.size} files"
                busy = false
                return@launch
            }
            result.forEach { f -> if (all.none { it.path == f.path }) all.add(f) }
            folders.clear()
            folders.addAll(Engine.collectFolders(all, scopeKey))
            refreshShown(result)
            status = if (result.isEmpty()) {
                "0 files — Allow files check karo, phir *.jpg Search"
            } else {
                "Search done — ${result.size} files"
            }
            busy = false
        }
    }

    fun doScan() {
        if (busy) return
        Engine.begin()
        busy = true
        status = "Scan in progress…"
        workJob = scope.launch {
            val files = withContext(Dispatchers.IO) {
                Engine.scan(scopeKey) { msg ->
                    scope.launch(Dispatchers.Main) { status = msg }
                }
            }
            if (Engine.cancelled) {
                if (files.isNotEmpty()) {
                    all.clear()
                    all.addAll(files)
                    refreshShown()
                }
                status = "Stopped — ${files.size} files"
                busy = false
                return@launch
            }
            all.clear()
            all.addAll(files)
            folders.clear()
            folders.addAll(Engine.collectFolders(files, scopeKey))
            selected.clear()
            selectedFolder = null
            refreshShown()
            status = "Scan done — ${files.size} files. Folder / Search use karo."
            busy = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("PhoneClean", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
            if (busy) {
                Button(
                    onClick = { stopWork() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9B2C2C)),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) { Text("Stop", fontSize = 13.sp) }
            }
            Button(
                onClick = { showMenu = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1D4ED8)),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) { Text("Menu", fontSize = 14.sp) }
        }
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        Text(status, color = Color(0xFFE8EEF6), fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp), maxLines = 2)
        val focus = LocalFocusManager.current
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("*.jpg   *.mp4") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    focus.clearFocus()
                    doSearch()
                }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                ),
            )
            Button(onClick = { doSearch() }, enabled = !busy) { Text("Search") }
        }
        Text(
            "${shown.size} files  |  ${selected.size} selected  |  tap = preview",
            color = Color(0xFF9EB0C7),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        LazyColumn(Modifier.weight(1f).padding(top = 4.dp)) {
            items(shown.take(2000), key = { it.mediaUri ?: it.path }) { f ->
                FileRow(
                    file = f,
                    checked = f.path in selected,
                    dupeMode = dupeMode,
                    onCheck = {
                        if (f.path in selected) selected.remove(f.path) else selected.add(f.path)
                    },
                    onOpen = { preview = f },
                )
            }
        }
    }
    preview?.let { pf ->
        PreviewDialog(file = pf, onClose = { preview = null }, onOpen = { openFile(ctx, pf) })
    }
    if (showMenu) {
        Dialog(onDismissRequest = { showMenu = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF152033))
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Menu", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Device" to "device", "SD card" to "sd", "Both" to "both").forEach { (label, key) ->
                        FilterChip(
                            selected = scopeKey == key,
                            onClick = {
                                scopeKey = key
                                if (all.isNotEmpty()) {
                                    folders.clear()
                                    folders.addAll(Engine.collectFolders(all, key))
                                    refreshShown()
                                }
                            },
                            label = { Text(label) },
                        )
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("" to "All", "WA Images" to "WA Img", "WA Video" to "WA Vid", "Camera" to "Camera", "Download" to "Download").forEach { (kind, label) ->
                        FilterChip(
                            selected = folderKind == kind && selectedFolder == null,
                            onClick = {
                                folderKind = kind
                                selectedFolder = null
                                if (all.isNotEmpty()) refreshShown()
                            },
                            label = { Text(label) },
                        )
                    }
                }
                if (folders.isNotEmpty()) {
                    Text("Folders", color = Color(0xFF9EB0C7), fontSize = 12.sp)
                    folders.take(40).forEach { f ->
                        Text(
                            "${f.name}  (${f.count})  ${f.scope}",
                            color = if (selectedFolder == f.path) Color(0xFF60A5FA) else Color.White,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedFolder = f.path
                                    folderKind = ""
                                    refreshShown()
                                    status = "${shown.size} files — ${f.name}"
                                    showMenu = false
                                }
                                .padding(vertical = 4.dp),
                        )
                    }
                }
                SmallBtn("Allow files", Color(0xFF334155)) {
                    showMenu = false
                    askPermission()
                }
                SmallBtn("Scan", Color(0xFF1D4ED8)) {
                    showMenu = false
                    doScan()
                }
                SmallBtn("Duplicates", Color(0xFF6D28D9)) {
                    showMenu = false
                    if (all.isEmpty()) {
                        status = "Pehle Scan"
                    } else {
                        val rows = Engine.duplicates(active())
                        refreshShown(rows, asDupes = true)
                        status = "Duplicates: ${rows.size} files — KEEP / copy. Delete extras."
                    }
                }
                SmallBtn("Delete extras", Color(0xFFB45309)) {
                    showMenu = false
                    val extras = shown.filter { dupeMode && !it.keep }
                    if (extras.isEmpty()) {
                        Toast.makeText(ctx, "Pehle Duplicates dabaao", Toast.LENGTH_SHORT).show()
                    } else {
                        var n = 0
                        extras.forEach { if (it.file.delete()) n++ }
                        all.removeAll { f -> extras.any { it.path == f.path } }
                        refreshShown(Engine.duplicates(active()), asDupes = true)
                        status = "Extras deleted: $n"
                    }
                }
                SmallBtn("Delete selected", Color(0xFF9B2C2C)) {
                    showMenu = false
                    val paths = selected.toList()
                    if (paths.isEmpty()) {
                        Toast.makeText(ctx, "Files select karo", Toast.LENGTH_SHORT).show()
                    } else {
                        var n = 0
                        paths.forEach { if (File(it).delete()) n++ }
                        all.removeAll { it.path in paths.toSet() }
                        selected.clear()
                        refreshShown()
                        status = "Deleted $n files"
                    }
                }
                SmallBtn("Copy folder", Color(0xFF0B6E4F)) {
                    showMenu = false
                    if (selected.isEmpty()) {
                        Toast.makeText(ctx, "Files select karo", Toast.LENGTH_SHORT).show()
                    } else {
                        treeLauncher.launch(null)
                    }
                }
                SmallBtn("Copy → SD", Color(0xFF0F766E)) {
                    showMenu = false
                    transferSelected(toSd = true, cut = false)
                }
                SmallBtn("Cut → SD", Color(0xFFB45309)) {
                    showMenu = false
                    transferSelected(toSd = true, cut = true)
                }
                SmallBtn("Copy → Phone", Color(0xFF1D4ED8)) {
                    showMenu = false
                    transferSelected(toSd = false, cut = false)
                }
                SmallBtn("Cut → Phone", Color(0xFF9A3412)) {
                    showMenu = false
                    transferSelected(toSd = false, cut = true)
                }
                SmallBtn("Select all", Color(0xFF334155)) {
                    selected.clear()
                    selected.addAll(shown.take(2000).map { it.path })
                    status = "${selected.size} selected"
                    showMenu = false
                }
                Button(onClick = { showMenu = false }, modifier = Modifier.fillMaxWidth()) { Text("Close") }
            }
        }
    }
}

@Composable
private fun SmallBtn(text: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = color),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 8.dp),
    ) { Text(text, fontSize = 14.sp) }
}

@Composable
private fun FileRow(
    file: PhoneFile,
    checked: Boolean,
    dupeMode: Boolean,
    onCheck: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1B2330))
            .clickable { onOpen() }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onCheck() })
        FileThumb(file, Modifier.size(88.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, Color(0xFF3B82F6), RoundedCornerShape(8.dp)))
        Column(Modifier.padding(start = 8.dp).weight(1f)) {
            val tag = if (!dupeMode) "" else if (file.keep) "KEEP  " else "copy  "
            Text(tag + file.name, color = Color.White, fontSize = 15.sp, maxLines = 1)
            Text(
                "${Engine.fmtSize(file.size)}  •  ${file.scope}  •  ${file.file.parentFile?.name ?: ""}",
                color = Color(0xFF9EB0C7),
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun FileThumb(file: PhoneFile, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var bmp by remember(file.path, file.mediaUri, file.name) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file.path, file.mediaUri, file.name) {
        bmp = withContext(Dispatchers.IO) { Thumbs.loadThumb(ctx, file) }
    }
    Box(modifier.background(Color(0xFF4B5C73)), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { c ->
                ImageView(c).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(0xFF4B5C73.toInt())
                }
            },
            update = { iv -> iv.setImageBitmap(bmp) },
            modifier = Modifier.fillMaxSize(),
        )
        if (bmp == null) {
            Text(
                file.ext.removePrefix(".").ifBlank { "IMG" }.uppercase(),
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        if (Engine.isVideo(file.ext)) {
            Text("▶", color = Color.White, fontSize = 16.sp)
        }
    }
}

@Composable
private fun PreviewDialog(file: PhoneFile, onClose: () -> Unit, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    var bmp by remember(file.path, file.mediaUri) { mutableStateOf<Bitmap?>(null) }
    val src = remember(file.path, file.mediaUri) { Thumbs.resolveUri(ctx, file) }
    LaunchedEffect(file.path, file.mediaUri) {
        bmp = withContext(Dispatchers.IO) { Thumbs.loadPreview(ctx, file) }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xF0101826))
                .padding(12.dp),
        ) {
            Text(file.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("${Engine.fmtSize(file.size)}  •  ${file.path}", color = Color(0xFF9EB0C7), fontSize = 12.sp)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                val frame = bmp
                if (Engine.isVideo(file.ext) && src != null) {
                    AndroidView(
                        factory = { c ->
                            VideoView(c).apply {
                                setVideoURI(src)
                                setOnPreparedListener { it.isLooping = true; start() }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    AndroidView(
                        factory = { c ->
                            ImageView(c).apply {
                                scaleType = ImageView.ScaleType.FIT_CENTER
                                setBackgroundColor(0xFF000000.toInt())
                            }
                        },
                        update = { iv -> iv.setImageBitmap(frame) },
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (frame == null) {
                        Text("Preview load…", color = Color.White, fontSize = 14.sp)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onClose, modifier = Modifier.weight(1f)) { Text("Close") }
                Button(
                    onClick = onOpen,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                ) { Text("Open app") }
            }
        }
    }
}

private fun mimeOf(file: File): String {
    val ext = file.extension.lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
}

private fun openFile(ctx: android.content.Context, file: PhoneFile) {
    try {
        val uri = Thumbs.resolveUri(ctx, file)
            ?: FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file.file)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(file.file)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(intent, file.name))
    } catch (e: Exception) {
        Toast.makeText(ctx, "Open fail: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
