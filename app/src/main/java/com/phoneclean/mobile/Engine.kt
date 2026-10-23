package com.phoneclean.mobile

import android.os.Environment
import java.io.File
import java.util.Locale

data class PhoneFile(
    val file: File,
    val name: String,
    val path: String,
    val size: Long,
    val ext: String,
    val scope: String,
    val keep: Boolean = false,
    val mediaUri: String? = null,
)

data class FolderRow(
    val path: String,
    val name: String,
    val count: Int,
    val scope: String,
    val kind: String,
)

object Engine {
    @Volatile
    var cancelled: Boolean = false

    fun begin() {
        cancelled = false
    }

    fun cancel() {
        cancelled = true
    }

    private val shortcuts = listOf(
        "WA Images" to listOf("/whatsapp images", "/whatsapp/media/whatsapp images"),
        "WA Video" to listOf("/whatsapp video", "/whatsapp/media/whatsapp video"),
        "WA Docs" to listOf("/whatsapp documents", "/whatsapp/media/whatsapp documents"),
        "Camera" to listOf("/dcim/camera"),
        "DCIM" to listOf("/dcim"),
        "Download" to listOf("/download"),
        "Pictures" to listOf("/pictures"),
        "Movies" to listOf("/movies"),
    )

    fun storageRoots(): List<Pair<String, File>> {
        val out = LinkedHashMap<String, File>()
        val primary = Environment.getExternalStorageDirectory()
        if (primary != null && primary.exists()) out["device"] = primary
        val storage = File("/storage")
        storage.listFiles()?.forEach { f ->
            val n = f.name
            val isSd = n.matches(Regex("[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}")) ||
                n.startsWith("sdcard", ignoreCase = true)
            if (isSd && f.isDirectory && f.canRead() && !n.equals("emulated", true)) {
                out.putIfAbsent("sd:$n", f)
            }
        }
        return out.map { it.key.substringBefore(":") to it.value }
            .distinctBy { it.second.canonicalPath }
    }

    fun scopeOf(path: String): String {
        val p = path.replace('\\', '/')
        return if ("/storage/" in p && "/emulated" !in p) "sd" else "device"
    }

    fun scan(scope: String, onProgress: (String) -> Unit): List<PhoneFile> {
        val roots = storageRoots().filter { (kind, _) ->
            scope == "both" || kind == scope
        }
        val files = ArrayList<PhoneFile>(8000)
        val seen = HashSet<String>()
        for ((kind, root) in roots) {
            onProgress("Scan: ${root.path}")
            walk(root, kind, files, seen, onProgress)
        }
        onProgress("Scan done — ${files.size} files")
        return files
    }

    private fun walk(
        dir: File,
        scope: String,
        out: ArrayList<PhoneFile>,
        seen: HashSet<String>,
        onProgress: (String) -> Unit,
    ) {
        val kids = dir.listFiles() ?: return
        for (f in kids) {
            if (cancelled) return
            try {
                if (f.isDirectory) {
                    val n = f.name
                    if (n.equals("data", true) && dir.name.equals("Android", true)) continue
                    if (n.equals("obb", true) && dir.name.equals("Android", true)) continue
                    if (n.startsWith(".") && n !in setOf(".whatsapp", ".thumbnails")) continue
                    if (n in setOf("cache", "code_cache", "no_backup")) continue
                    walk(f, scope, out, seen, onProgress)
                } else if (f.isFile) {
                    val path = f.absolutePath
                    if (!seen.add(path)) continue
                    out.add(
                        PhoneFile(
                            file = f,
                            name = f.name,
                            path = path,
                            size = 0L,
                            ext = f.extension.lowercase(Locale.US).let { if (it.isEmpty()) "" else ".$it" },
                            scope = scope,
                        ),
                    )
                    if (out.size % 500 == 0) onProgress("Scan… ${out.size} files")
                }
            } catch (_: SecurityException) {
            }
        }
    }

    fun filterQuery(files: List<PhoneFile>, query: String): List<PhoneFile> {
        val q = query.trim()
        if (q.isEmpty()) return files
        MediaScan.queryExts(q)?.let { aliases ->
            return files.filter { it.ext in aliases }
        }
        val ext = Regex("""^\*?(\.[A-Za-z0-9]{1,10})$""").matchEntire(q)
        if (ext != null) {
            val e = ext.groupValues[1].lowercase(Locale.US)
            val aliases = when (e) {
                ".jpg" -> setOf(".jpg", ".jpeg")
                ".jpeg" -> setOf(".jpg", ".jpeg")
                else -> setOf(e)
            }
            return files.filter { it.ext in aliases }
        }
        if ("*" in q) {
            val regex = Regex(q.lowercase(Locale.US).replace(".", "\\.").replace("*", ".*"))
            return files.filter { regex.matches(it.name.lowercase(Locale.US)) }
        }
        val parts = q.lowercase(Locale.US).split(Regex("\\s+")).filter { it.isNotEmpty() }
        return files.filter { f ->
            val hay = "${f.name} ${f.path} ${f.ext}".lowercase(Locale.US)
            parts.all { it in hay }
        }
    }

    fun filesInFolders(files: List<PhoneFile>, folders: List<String>): List<PhoneFile> {
        if (folders.isEmpty()) return files
        val prefs = folders.map { it.trimEnd('/', '\\') + File.separator }
        val exact = folders.map { it.trimEnd('/', '\\') }.toSet()
        return files.filter { f ->
            val parent = f.file.parent ?: ""
            parent in exact || prefs.any { f.path.startsWith(it, ignoreCase = true) }
        }
    }

    fun collectFolders(files: List<PhoneFile>, scope: String): List<FolderRow> {
        val counts = LinkedHashMap<String, Int>()
        val scopes = HashMap<String, String>()
        for (f in files) {
            if (scope != "both" && f.scope != scope) continue
            val folder = f.file.parent ?: continue
            counts[folder] = (counts[folder] ?: 0) + 1
            scopes[folder] = f.scope
        }
        val rows = counts.map { (folder, n) ->
            val kind = folderKind(folder)
            val last = folder.substringAfterLast(File.separatorChar)
            FolderRow(
                path = folder,
                name = if (kind.isNotEmpty()) "$kind / $last" else last,
                count = n,
                scope = scopes[folder] ?: "device",
                kind = kind,
            )
        }
        val shortcuts = rows.filter { it.kind.isNotEmpty() }.sortedByDescending { it.count }
        val others = rows.filter { it.kind.isEmpty() && it.count >= 3 }.sortedByDescending { it.count }.take(180)
        return shortcuts + others
    }

    fun folderKind(folder: String): String {
        val low = folder.replace('\\', '/').lowercase(Locale.US)
        return shortcuts.firstOrNull { (_, hints) -> hints.any { it in low } }?.first ?: ""
    }

    fun duplicates(files: List<PhoneFile>): List<PhoneFile> {
        val byKey = HashMap<String, MutableList<PhoneFile>>()
        val byName = HashMap<String, MutableList<PhoneFile>>()
        val photo = setOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".mp4")
        for (f in files) {
            if (f.name.isBlank()) continue
            val keyName = f.name.lowercase(Locale.US)
            if (f.size >= 16 * 1024) {
                byKey.getOrPut("${f.size}|$keyName") { mutableListOf() }.add(f)
            }
            if (f.ext in photo && uniqueName(f.name)) {
                byName.getOrPut(keyName) { mutableListOf() }.add(f)
            }
        }
        val groups = ArrayList<List<PhoneFile>>()
        val used = HashSet<String>()
        for (g in byKey.values) {
            if (g.size >= 2) {
                groups.add(g)
                g.forEach { used.add(it.path) }
            }
        }
        for (g in byName.values) {
            val extra = g.filter { it.path !in used }
            if (extra.size >= 2) {
                groups.add(extra)
                extra.forEach { used.add(it.path) }
            }
        }
        val out = ArrayList<PhoneFile>()
        for (g in groups.sortedByDescending { (it.firstOrNull()?.size ?: 0L) * (it.size - 1) }) {
            val keep = pickKeep(g)
            for (f in g) out.add(f.copy(keep = f.path == keep.path))
        }
        return out
    }

    fun extras(rows: List<PhoneFile>): List<PhoneFile> = rows.filter { !it.keep }

    private fun uniqueName(name: String): Boolean {
        val stem = name.substringBeforeLast('.')
        return stem.length >= 10 || Regex("""\d{6,}""").containsMatchIn(stem)
    }

    private fun pickKeep(group: List<PhoneFile>): PhoneFile {
        return group.maxBy { f ->
            val p = f.path.replace('\\', '/').lowercase(Locale.US)
            var s = 0
            when {
                "/dcim/camera/" in p -> s += 220
                "/dcim/" in p -> s += 160
                "/pictures/" in p -> s += 120
            }
            if ("/whatsapp/" in p) s -= 50
            if ("/download" in p) s -= 40
            if ("/cache" in p || "/thumbnail" in p) s -= 90
            if (f.scope == "device") s += 8
            s
        }
    }

    fun fmtSize(n: Long): String {
        var v = n.toDouble()
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var i = 0
        while (v >= 1024 && i < units.lastIndex) {
            v /= 1024
            i++
        }
        return if (i == 0) "${n} B" else String.format(Locale.US, "%.1f %s", v, units[i])
    }

    fun isImage(ext: String) = ext in setOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".heic")
    fun isVideo(ext: String) = ext in setOf(".mp4", ".mkv", ".avi", ".mov", ".webm", ".3gp", ".m4v")

    fun deviceRoot(): File? = storageRoots().firstOrNull { it.first == "device" }?.second

    fun sdRoot(): File? = storageRoots().firstOrNull { it.first == "sd" }?.second

    data class TransferResult(
        val ok: Int,
        val dests: List<PhoneFile>,
        val removed: List<String>,
        val errors: List<String>,
    )

    fun transferDeviceSd(
        paths: List<String>,
        toSd: Boolean,
        cut: Boolean,
        onProgress: (String) -> Unit,
    ): TransferResult {
        val device = deviceRoot() ?: return TransferResult(0, emptyList(), emptyList(), listOf("Device storage nahi mili"))
        val sd = sdRoot() ?: return TransferResult(0, emptyList(), emptyList(), listOf("SD card nahi mili / mount nahi"))
        val srcRoot = if (toSd) device else sd
        val destRoot = if (toSd) sd else device
        val destScope = if (toSd) "sd" else "device"
        var ok = 0
        val dests = ArrayList<PhoneFile>()
        val removed = ArrayList<String>()
        val errors = ArrayList<String>()
        paths.forEachIndexed { i, path ->
            val src = File(path)
            if (!src.exists() || !src.isFile) {
                errors.add("nahi mili: ${src.name}")
                return@forEachIndexed
            }
            val alreadySd = scopeOf(path) == "sd"
            if (toSd && alreadySd) {
                errors.add("pehle se SD: ${src.name}")
                return@forEachIndexed
            }
            if (!toSd && !alreadySd) {
                errors.add("pehle se Device: ${src.name}")
                return@forEachIndexed
            }
            onProgress("${if (cut) "Cut" else "Copy"} (${i + 1}/${paths.size}): ${src.name}")
            val rel = relativeUnder(srcRoot, src) ?: src.name
            val destDir = File(destRoot, File(rel).parent ?: "")
            destDir.mkdirs()
            val dest = uniqueLocal(destDir, File(rel).name)
            try {
                src.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
                if (!dest.exists() || dest.length() <= 0L) {
                    dest.delete()
                    errors.add("copy fail: ${src.name}")
                    return@forEachIndexed
                }
                if (cut) {
                    if (src.delete()) removed.add(src.absolutePath) else errors.add("copied, delete fail: ${src.name}")
                }
                dests.add(
                    PhoneFile(
                        file = dest,
                        name = dest.name,
                        path = dest.absolutePath,
                        size = dest.length(),
                        ext = dest.extension.lowercase(Locale.US).let { if (it.isEmpty()) "" else ".$it" },
                        scope = destScope,
                    ),
                )
                ok++
            } catch (e: Exception) {
                errors.add("${src.name}: ${e.message ?: "fail"}")
                if (dest.exists() && dest.length() == 0L) dest.delete()
            }
        }
        return TransferResult(ok, dests, removed, errors)
    }

    private fun relativeUnder(root: File, file: File): String? {
        val r = try {
            root.canonicalPath
        } catch (_: Exception) {
            root.absolutePath
        }.trimEnd('/', '\\')
        val p = try {
            file.canonicalPath
        } catch (_: Exception) {
            file.absolutePath
        }
        if (!p.startsWith(r, ignoreCase = true)) return file.name
        return p.substring(r.length).trimStart('/', '\\')
    }

    private fun uniqueLocal(dir: File, name: String): File {
        var dest = File(dir, name)
        if (!dest.exists()) return dest
        val stem = dest.nameWithoutExtension
        val ext = dest.extension.let { if (it.isEmpty()) "" else ".$it" }
        var i = 2
        while (true) {
            dest = File(dir, "$stem ($i)$ext")
            if (!dest.exists()) return dest
            i++
        }
    }
}
