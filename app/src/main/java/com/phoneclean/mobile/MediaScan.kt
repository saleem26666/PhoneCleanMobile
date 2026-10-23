package com.phoneclean.mobile

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.Locale

object MediaScan {
    private val imageExt = setOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".heic")
    private val videoExt = setOf(".mp4", ".mkv", ".avi", ".mov", ".webm", ".3gp", ".m4v")

    fun queryExts(query: String): Set<String>? {
        val q = query.trim().lowercase(Locale.US).removePrefix("*")
        if (q.isEmpty()) return null
        val m = Regex("""^\.?([a-z0-9]{1,10})$""").matchEntire(q)
        if (m != null) {
            val e = ".${m.groupValues[1].trimStart('.')}"
            return when (e) {
                ".jpg", ".jpeg" -> setOf(".jpg", ".jpeg")
                else -> setOf(e)
            }
        }
        return null
    }

    fun fastSearch(
        ctx: Context,
        query: String,
        scope: String,
        cancelled: () -> Boolean,
        onProgress: (String, List<PhoneFile>) -> Unit,
    ): List<PhoneFile> {
        val seen = LinkedHashMap<String, PhoneFile>()
        val exts = queryExts(query)
        onProgress("Search start…", emptyList())

        if (exts == null || exts.any { it in imageExt }) {
            queryUri(ctx, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, scope, exts, seen, cancelled)
            onProgress("Photos: ${seen.size}", snapshot(seen))
        }
        if (cancelled()) return snapshot(seen)
        if (exts == null || exts.any { it in videoExt }) {
            queryUri(ctx, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, scope, exts, seen, cancelled)
            onProgress("Photos+video: ${seen.size}", snapshot(seen))
        }
        if (cancelled()) return snapshot(seen)
        queryFilesTable(ctx, scope, exts, query, seen, cancelled)
        onProgress("Index: ${seen.size} — folders ghoom rahe hain…", snapshot(seen))

        walkKnownFolders(query, exts, scope, seen, cancelled) { n ->
            if (n % 200 == 0) onProgress("Folders… $n files", snapshot(seen))
        }
        val out = snapshot(seen)
        onProgress("Search done — ${out.size} files", out)
        return out
    }

    private fun snapshot(seen: Map<String, PhoneFile>) = seen.values.toList()

    private fun queryUri(
        ctx: Context,
        uri: android.net.Uri,
        scope: String,
        exts: Set<String>?,
        seen: LinkedHashMap<String, PhoneFile>,
        cancelled: () -> Boolean,
    ) {
        val projections = listOf(
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
            ),
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE),
            arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DISPLAY_NAME),
        )
        for (proj in projections) {
            try {
                ctx.contentResolver.query(uri, proj, null, null, null)?.use { c ->
                    val iId = c.getColumnIndex(MediaStore.MediaColumns._ID)
                    val iData = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                    val iName = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val iSize = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    while (c.moveToNext()) {
                        if (cancelled()) return
                        val name = if (iName >= 0) c.getString(iName) else null
                        val path = if (iData >= 0) c.getString(iData) else null
                        val id = if (iId >= 0) c.getLong(iId) else -1L
                        val content = if (id >= 0) ContentUris.withAppendedId(uri, id).toString() else null
                        val file = if (!path.isNullOrBlank()) {
                            File(path)
                        } else {
                            File(Environment.getExternalStorageDirectory(), name ?: "file")
                        }
                        val ext = extOf(name ?: file.name)
                        if (exts != null && ext !in exts) continue
                        val kind = Engine.scopeOf(path ?: "")
                        if (scope != "both" && kind != scope && path != null) continue
                        val size = if (iSize >= 0) c.getLong(iSize) else -1L
                        add(seen, file, kind, size, name ?: file.name, content)
                    }
                }
                return
            } catch (_: Exception) {
            }
        }
    }

    private fun queryFilesTable(
        ctx: Context,
        scope: String,
        exts: Set<String>?,
        query: String,
        seen: LinkedHashMap<String, PhoneFile>,
        cancelled: () -> Boolean,
    ) {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val uri = MediaStore.Files.getContentUri("external")
            ctx.contentResolver.query(
                uri,
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DATA,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                ),
                null,
                null,
                null,
            )?.use { c ->
                val iId = c.getColumnIndex(MediaStore.MediaColumns._ID)
                val iData = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                val iName = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val iSize = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val q = query.trim().lowercase(Locale.US)
                while (c.moveToNext()) {
                    if (cancelled()) return
                    val path = if (iData >= 0) c.getString(iData) else null
                    if (path.isNullOrBlank()) continue
                    val name = if (iName >= 0) c.getString(iName) else File(path).name
                    val ext = extOf(name)
                    if (exts != null && ext !in exts) continue
                    if (exts == null && q.isNotEmpty() && q !in name.lowercase(Locale.US) && q !in path.lowercase(Locale.US)) {
                        continue
                    }
                    val kind = Engine.scopeOf(path)
                    if (scope != "both" && kind != scope) continue
                    val size = if (iSize >= 0) c.getLong(iSize) else -1L
                    val id = if (iId >= 0) c.getLong(iId) else -1L
                    val content = if (id >= 0) ContentUris.withAppendedId(uri, id).toString() else null
                    add(seen, File(path), kind, size, name, content)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun walkKnownFolders(
        query: String,
        exts: Set<String>?,
        scope: String,
        seen: LinkedHashMap<String, PhoneFile>,
        cancelled: () -> Boolean,
        tick: (Int) -> Unit,
    ) {
        val q = query.trim().lowercase(Locale.US)
        for (dir in targetFolders(exts, query)) {
            if (cancelled()) return
            if (!dir.exists()) continue
            val kind = Engine.scopeOf(dir.absolutePath)
            if (scope != "both" && kind != scope) continue
            walkDir(dir, kind, exts, q, seen, cancelled, 0, tick)
        }
    }

    private fun walkDir(
        dir: File,
        scope: String,
        exts: Set<String>?,
        q: String,
        seen: LinkedHashMap<String, PhoneFile>,
        cancelled: () -> Boolean,
        depth: Int,
        tick: (Int) -> Unit,
    ) {
        if (depth > 8 || cancelled()) return
        val kids = try {
            dir.listFiles()
        } catch (_: Exception) {
            null
        } ?: return
        for (f in kids) {
            if (cancelled()) return
            try {
                if (f.isDirectory) {
                    val n = f.name
                    if (n.equals("data", true) && dir.name.equals("Android", true)) continue
                    if (n.equals("obb", true) && dir.name.equals("Android", true)) continue
                    walkDir(f, scope, exts, q, seen, cancelled, depth + 1, tick)
                } else if (f.isFile) {
                    val ext = extOf(f.name)
                    if (exts != null && ext !in exts) continue
                    if (exts == null && q.isNotEmpty() && "*" !in q &&
                        q !in f.name.lowercase(Locale.US) && q !in f.path.lowercase(Locale.US)
                    ) {
                        continue
                    }
                    add(seen, f, scope)
                    tick(seen.size)
                }
            } catch (_: Exception) {
            }
        }
    }

    fun lookupMediaUri(ctx: Context, file: PhoneFile): android.net.Uri? {
        file.mediaUri?.let { raw ->
            runCatching { android.net.Uri.parse(raw) }.getOrNull()?.let { return it }
        }
        val path = file.path.ifBlank { file.file.absolutePath }
        val name = file.name.ifBlank { file.file.name }
        val tables = buildList {
            if (Engine.isImage(file.ext) || extOf(name) in imageExt) add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            if (Engine.isVideo(file.ext) || extOf(name) in videoExt) add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            if (Build.VERSION.SDK_INT >= 29) add(MediaStore.Files.getContentUri("external"))
        }
        for (uri in tables) {
            if (path.contains("/")) {
                findId(ctx, uri, "${MediaStore.MediaColumns.DATA}=?", arrayOf(path))?.let { return it }
            }
            if (name.isNotBlank()) {
                findId(ctx, uri, "${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(name))?.let { return it }
            }
        }
        return null
    }

    private fun findId(
        ctx: Context,
        uri: android.net.Uri,
        sel: String,
        args: Array<String>,
    ): android.net.Uri? {
        return try {
            ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), sel, args, null)?.use { c ->
                if (c.moveToFirst()) ContentUris.withAppendedId(uri, c.getLong(0)) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extOf(name: String): String {
        val d = name.lastIndexOf('.')
        if (d < 0) return ""
        return name.substring(d).lowercase(Locale.US)
    }

    private fun add(
        seen: LinkedHashMap<String, PhoneFile>,
        f: File,
        scope: String,
        size: Long = -1L,
        name: String? = null,
        mediaUri: String? = null,
    ) {
        val path = f.absolutePath
        val key = when {
            path.isNotBlank() && path.contains("/") -> path
            !mediaUri.isNullOrBlank() -> mediaUri
            else -> name ?: path
        }
        val old = seen[key]
        if (old != null && old.mediaUri != null) return
        val nm = name ?: f.name
        seen[key] = PhoneFile(
            file = f,
            name = nm,
            path = path,
            size = if (size >= 0) size else 0L,
            ext = extOf(nm),
            scope = scope,
            mediaUri = mediaUri ?: old?.mediaUri,
        )
    }

    private fun targetFolders(exts: Set<String>?, query: String): List<File> {
        val roots = Engine.storageRoots().map { it.second }.toMutableList()
        Environment.getExternalStorageDirectory()?.let { roots.add(it) }
        File("/storage/emulated/0").takeIf { it.exists() }?.let { roots.add(it) }
        val q = query.lowercase(Locale.US)
        val rels = LinkedHashSet<String>()
        rels += listOf(
            "DCIM",
            "DCIM/Camera",
            "Pictures",
            "Download",
            "Downloads",
            "WhatsApp/Media",
            "WhatsApp/Media/WhatsApp Images",
            "WhatsApp/Media/WhatsApp Video",
            "Android/media/com.whatsapp/WhatsApp/Media",
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images",
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video",
        )
        if (exts == null && q.isNotEmpty() && "whatsapp" !in q && "jpg" !in q && "mp4" !in q) {
            rels += "Documents"
        }
        return roots.distinctBy { it.absolutePath }.flatMap { root ->
            rels.map { File(root, it) }
        }
    }
}
