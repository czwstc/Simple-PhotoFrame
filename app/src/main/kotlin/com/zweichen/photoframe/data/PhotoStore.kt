package com.zweichen.photoframe.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.bumptech.glide.Glide
import androidx.exifinterface.media.ExifInterface
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The frame's own photo folder. Lives in app-specific storage, so no storage permission is needed.
 */
class PhotoStore(private val context: Context) {
    data class Photo(val name: String, val file: File, val size: Long, val modified: Long)

    sealed class SaveResult {
        data class Saved(val photo: Photo) : SaveResult()
        data class Duplicate(val photo: Photo) : SaveResult()
        data class Invalid(val reason: String) : SaveResult()
    }

    val dir: File = (context.getExternalFilesDir("photos") ?: File(context.filesDir, "photos")).apply { mkdirs() }
    private val thumbDir = File(context.cacheDir, "thumbs").apply { mkdirs() }
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    /** "original name|size" of converted HEIC uploads -> stored JPEG name, to recognise re-uploads. */
    private val convertedUploads = context.getSharedPreferences("converted_uploads", Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    init {
        // leftovers of interrupted uploads
        dir.listFiles()?.filter { it.name.endsWith(PART_SUFFIX) }?.forEach { it.delete() }
    }

    fun list(): List<Photo> {
        val files = dir.listFiles() ?: return emptyList()
        return files.asSequence()
            .filter { it.isFile && !it.name.startsWith(".") && hasImageExtension(it.name) }
            .map { Photo(it.name, it, it.length(), it.lastModified()) }
            .sortedByDescending { it.modified }
            .toList()
    }

    fun count() = dir.listFiles()?.count { it.isFile && !it.name.startsWith(".") && hasImageExtension(it.name) } ?: 0

    fun freeSpace(): Long = dir.usableSpace

    /** Resolves a client supplied name to a photo inside [dir], rejecting anything that could escape it. */
    fun find(name: String): Photo? {
        if (name.isBlank() || name.contains('/') || name.contains('\\') || name.startsWith(".")) return null
        val file = File(dir, name)
        if (!file.isFile || file.parentFile?.canonicalPath != dir.canonicalPath) return null
        return Photo(file.name, file, file.length(), file.lastModified())
    }

    /** Streams an upload into the store. [length] is the expected byte count, or -1 if unknown. */
    fun save(originalName: String, input: InputStream, length: Long): SaveResult {
        var name = sanitize(originalName)
        if (length > MAX_FILE_SIZE) return SaveResult.Invalid("文件太大")
        if (length >= 0 && freeSpace() < length + MIN_FREE_SPACE) return SaveResult.Invalid("相框存储空间不足")

        val uploadKey = "$name|$length"
        val existing = find(name)?.takeIf { it.size == length }
            ?: convertedUploads.getString(uploadKey, null)?.let { find(it) }
        if (existing != null) {
            drain(input, length)
            return SaveResult.Duplicate(existing)
        }

        val tmp = File(dir, ".upload-${UUID.randomUUID()}$PART_SUFFIX")
        try {
            val written = FileOutputStream(tmp).use { out -> copy(input, out, length) }
            if (length >= 0 && written != length) {
                tmp.delete()
                return SaveResult.Invalid("上传中断")
            }

            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.absolutePath, options)
            val decodable = options.outWidth > 0 && options.outHeight > 0
            val heif = options.outMimeType in HEIF_MIME_TYPES || name.substringAfterLast('.', "").lowercase() in HEIF_EXTENSIONS
            if (!decodable && !heif) {
                tmp.delete()
                return SaveResult.Invalid("不是可识别的图片")
            }

            // HEIC (iPhone, many Android phones) can't be shown by phone browsers; store a JPEG instead
            var source = tmp
            var converted = false
            if (heif) {
                val jpeg = convertToJpeg(tmp)
                if (jpeg != null) {
                    tmp.delete()
                    source = jpeg
                    converted = true
                    name = name.substringBeforeLast('.') + ".jpg"
                } else if (!decodable) {
                    tmp.delete()
                    return SaveResult.Invalid("无法识别这张 HEIC 图片")
                }
            }
            if (!hasImageExtension(name)) {
                name += extensionForMime(options.outMimeType)
            }

            val photo = synchronized(lock) {
                val target = uniqueFile(name)
                if (!source.renameTo(target)) {
                    source.delete()
                    return SaveResult.Invalid("保存失败")
                }
                Photo(target.name, target, target.length(), target.lastModified())
            }
            if (converted) convertedUploads.edit().putString(uploadKey, photo.name).apply()
            notifyChanged()
            return SaveResult.Saved(photo)
        } catch (e: Exception) {
            Log.w(TAG, "save failed", e)
            tmp.delete()
            return SaveResult.Invalid("保存失败")
        }
    }

    fun delete(names: Collection<String>): Int {
        var deleted = 0
        names.mapNotNull { find(it) }.forEach { photo ->
            thumbFile(photo).delete()
            if (photo.file.delete()) deleted++
        }
        val gone = names.toSet()
        convertedUploads.all.filterValues { it in gone }.keys.let { keys ->
            if (keys.isNotEmpty()) convertedUploads.edit().apply { keys.forEach { remove(it) } }.apply()
        }
        if (deleted > 0) notifyChanged()
        return deleted
    }

    /** Returns a cached square JPEG thumbnail. Blocking. */
    fun thumbnail(photo: Photo): File? {
        val thumb = thumbFile(photo)
        if (thumb.exists()) return thumb
        val target = Glide.with(context)
            .asBitmap()
            .load(photo.file)
            .centerCrop()
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .skipMemoryCache(true)
            .submit(THUMB_SIZE, THUMB_SIZE)
        return try {
            val bitmap = target.get()
            val tmp = File(thumbDir, thumb.name + ".tmp")
            FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            tmp.renameTo(thumb)
            thumb
        } catch (e: Exception) {
            Log.w(TAG, "thumbnail failed for ${photo.name}", e)
            null
        } finally {
            Glide.with(context).clear(target)
        }
    }

    /** Decodes [src] (Glide applies the EXIF rotation) and re-encodes it as JPEG, keeping the capture date. Blocking. */
    private fun convertToJpeg(src: File): File? {
        val target = Glide.with(context)
            .asBitmap()
            .load(src)
            .downsample(DownsampleStrategy.CENTER_INSIDE)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .skipMemoryCache(true)
            .submit(MAX_CONVERTED_SIZE, MAX_CONVERTED_SIZE)
        val out = File(dir, ".convert-${UUID.randomUUID()}$PART_SUFFIX")
        return try {
            val bitmap = target.get()
            FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            copyCaptureDate(src, out)
            out
        } catch (e: Exception) {
            Log.w(TAG, "HEIC conversion failed", e)
            out.delete()
            null
        } finally {
            Glide.with(context).clear(target)
        }
    }

    private fun copyCaptureDate(from: File, to: File) {
        try {
            val source = ExifInterface(from)
            val dest = ExifInterface(to)
            listOf(ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL)
                .forEach { tag -> source.getAttribute(tag)?.let { dest.setAttribute(tag, it) } }
            dest.saveAttributes()
        } catch (e: Exception) {
            Log.w(TAG, "couldn't copy EXIF", e)
        }
    }

    fun addListener(listener: () -> Unit) = listeners.add(listener)

    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    private fun notifyChanged() {
        mainHandler.post { listeners.forEach { it() } }
    }

    private fun thumbFile(photo: Photo) =
        File(thumbDir, "${Integer.toHexString(photo.name.hashCode())}_${photo.size}_${photo.modified}.jpg")

    private fun uniqueFile(name: String): File {
        var file = File(dir, name)
        if (!file.exists()) return file
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (file.exists()) {
            file = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext")
            i++
        }
        return file
    }

    private fun copy(input: InputStream, out: FileOutputStream, length: Long): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (length < 0 || total < length) {
            val toRead = if (length < 0) buffer.size else minOf(buffer.size.toLong(), length - total).toInt()
            val read = input.read(buffer, 0, toRead)
            if (read == -1) break
            out.write(buffer, 0, read)
            total += read
            if (total > MAX_FILE_SIZE) break
        }
        return total
    }

    private fun drain(input: InputStream, length: Long) {
        if (length <= 0) return
        val buffer = ByteArray(64 * 1024)
        var remaining = length
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read == -1) break
            remaining -= read
        }
    }

    companion object {
        private const val TAG = "PhotoStore"
        private const val PART_SUFFIX = ".part"
        private const val THUMB_SIZE = 360
        const val MAX_FILE_SIZE = 100L * 1024 * 1024
        private const val MAX_CONVERTED_SIZE = 4096
        private val HEIF_MIME_TYPES = setOf("image/heif", "image/heic", "image/heif-sequence", "image/heic-sequence")
        private val HEIF_EXTENSIONS = setOf("heic", "heif")
        private const val MIN_FREE_SPACE = 50L * 1024 * 1024
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp", "gif")

        fun hasImageExtension(name: String) = name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

        fun mimeType(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "heic", "heif" -> "image/heif"
            "bmp" -> "image/bmp"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        }

        private fun extensionForMime(mime: String?) = when (mime) {
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            "image/heif", "image/heic" -> ".heic"
            "image/bmp", "image/x-ms-bmp" -> ".bmp"
            "image/gif" -> ".gif"
            else -> ".jpg"
        }

        fun sanitize(original: String): String {
            var name = original.substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "_")
                .trim()
                .trimStart('.')
            if (name.length > 120) {
                val ext = name.substringAfterLast('.', "")
                name = name.take(110) + if (ext.isNotEmpty() && ext.length <= 5) ".$ext" else ""
            }
            return name.ifBlank { "photo_${System.currentTimeMillis()}" }
        }
    }
}
