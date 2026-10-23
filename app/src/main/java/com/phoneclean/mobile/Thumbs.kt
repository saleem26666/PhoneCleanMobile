package com.phoneclean.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream

object Thumbs {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    fun mediaStoreUri(ctx: Context, file: PhoneFile): Uri? {
        file.mediaUri?.takeIf { it.startsWith("content://media", true) }?.let {
            return runCatching { Uri.parse(it) }.getOrNull()
        }
        return MediaScan.lookupMediaUri(ctx, file)
    }

    fun resolveUri(ctx: Context, file: PhoneFile): Uri? {
        mediaStoreUri(ctx, file)?.let { return it }
        if (file.file.exists()) {
            return runCatching {
                FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file.file)
            }.getOrNull()
        }
        return null
    }

    fun loadThumb(ctx: Context, file: PhoneFile): Bitmap? {
        val key = "${file.mediaUri}|${file.path}|${file.name}"
        cache.get(key)?.let { return it }
        val bmp = software(loadUncached(ctx, file, 256)) ?: return null
        cache.put(key, bmp)
        return bmp
    }

    fun loadPreview(ctx: Context, file: PhoneFile): Bitmap? {
        return software(loadUncached(ctx, file, 1280))
    }

    private fun loadUncached(ctx: Context, file: PhoneFile, size: Int): Bitmap? {
        val disk = readableFile(file)
        if (disk != null) {
            if (Engine.isVideo(file.ext)) {
                fileThumb(disk, video = true, size)?.let { return it }
            } else {
                fileThumb(disk, video = false, size)?.let { return it }
                decodeFileStream(disk, size)?.let { return it }
            }
        }
        val media = mediaStoreUri(ctx, file)
        if (media != null) {
            decodeUri(ctx, media, size)?.let { return it }
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    ctx.contentResolver.loadThumbnail(media, Size(size, size), null)?.let { return it }
                } catch (_: Exception) {
                }
            }
            if (Engine.isVideo(file.ext)) videoFrame(ctx, media, disk)?.let { return it }
        }
        val anyUri = resolveUri(ctx, file)
        if (anyUri != null && anyUri != media) {
            decodeUri(ctx, anyUri, size)?.let { return it }
        }
        if (Engine.isVideo(file.ext)) videoFrame(ctx, media ?: anyUri, disk)?.let { return it }
        return null
    }

    private fun readableFile(file: PhoneFile): File? {
        val candidates = listOf(file.file, File(file.path)).distinctBy { it.absolutePath }
        return candidates.firstOrNull { f ->
            try {
                f.isFile && f.exists() && f.length() > 0L
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun fileThumb(file: File, video: Boolean, size: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < 29) return null
        return try {
            if (video) {
                ThumbnailUtils.createVideoThumbnail(file, Size(size, size), null)
            } else {
                ThumbnailUtils.createImageThumbnail(file, Size(size, size), null)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeUri(ctx: Context, uri: Uri, size: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                val src = ImageDecoder.createSource(ctx.contentResolver, uri)
                return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val w = info.size.width.coerceAtLeast(1)
                    val h = info.size.height.coerceAtLeast(1)
                    val scale = maxOf(1, maxOf(w, h) / size)
                    decoder.setTargetSize((w / scale).coerceAtLeast(1), (h / scale).coerceAtLeast(1))
                }
            } catch (_: Exception) {
            }
        }
        return decodeStream(ctx, uri, size)
    }

    private fun decodeStream(ctx: Context, uri: Uri, size: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample(bounds, size)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeFileStream(file: File, size: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            FileInputStream(file).use { BitmapFactory.decodeStream(it, null, bounds) }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample(bounds, size)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            FileInputStream(file).use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: Exception) {
            null
        }
    }

    private fun sample(bounds: BitmapFactory.Options, size: Int): Int {
        var sample = 1
        val h = bounds.outHeight.coerceAtLeast(1)
        val w = bounds.outWidth.coerceAtLeast(1)
        while (h / sample > size * 2 || w / sample > size * 2) sample *= 2
        return sample
    }

    private fun videoFrame(ctx: Context, uri: Uri?, file: File?): Bitmap? {
        val mmr = MediaMetadataRetriever()
        return try {
            when {
                uri != null -> mmr.setDataSource(ctx, uri)
                file != null && file.exists() -> mmr.setDataSource(file.absolutePath)
                else -> return null
            }
            mmr.getFrameAtTime(800_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: mmr.frameAtTime
        } catch (_: Exception) {
            null
        } finally {
            try {
                mmr.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun software(src: Bitmap?): Bitmap? {
        if (src == null) return null
        if (Build.VERSION.SDK_INT >= 26 && src.config == Bitmap.Config.HARDWARE) {
            return src.copy(Bitmap.Config.ARGB_8888, false) ?: src
        }
        return src
    }
}
