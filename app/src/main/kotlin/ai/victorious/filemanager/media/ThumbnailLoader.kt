package ai.victorious.filemanager.media

import ai.victorious.filemanager.core.media.ThumbnailKey
import ai.victorious.filemanager.core.viewer.FileKind
import ai.victorious.filemanager.core.viewer.FileKinds
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Loads and caches square thumbnails for the gallery grid. Image frames are decoded with a
 * downsampling BitmapFactory pass; video frames come from MediaMetadataRetriever. Decoded bitmaps
 * live in an in-memory [LruCache]; a parallel [LruBudget] (unit-tested in :core) mirrors the byte
 * budget and decides what to drop, and encoded thumbnails are also persisted to a small disk cache
 * so a second visit to a folder is instant.
 *
 * Nothing here is Phase-11 / APK specific; it is plain media decoding written from scratch.
 */
class ThumbnailLoader(
    cacheRoot: File,
    private val maxMemoryBytes: Long = 16L * 1024 * 1024,
    private val maxDiskBytes: Long = 64L * 1024 * 1024,
) {
    private val diskDir = File(cacheRoot, "thumbs").apply { mkdirs() }

    private val memory = object : LruCache<String, Bitmap>((maxMemoryBytes / 1024).toInt().coerceAtLeast(1)) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    // Per-key locks so two grid cells for the same file do not decode twice.
    private val locks = HashMap<String, Mutex>()
    private fun lockFor(token: String): Mutex = synchronized(locks) { locks.getOrPut(token) { Mutex() } }

    fun keyFor(file: File, targetPx: Int): ThumbnailKey =
        ThumbnailKey(file.absolutePath, file.length(), file.lastModified(), targetPx)

    /** Returns a cached-or-freshly-decoded thumbnail, or null when the file cannot be decoded. */
    suspend fun load(file: File, targetPx: Int): Bitmap? {
        val token = keyFor(file, targetPx).token()
        memory.get(token)?.let { return it }
        return lockFor(token).withLock {
            memory.get(token)?.let { return it }
            val fromDisk = withContext(Dispatchers.IO) { readDisk(token) }
            if (fromDisk != null) { memory.put(token, fromDisk); return fromDisk }
            val bmp = withContext(Dispatchers.IO) { decode(file, targetPx) } ?: return null
            memory.put(token, bmp)
            withContext(Dispatchers.IO) { writeDisk(token, bmp) }
            bmp
        }
    }

    private fun decode(file: File, targetPx: Int): Bitmap? = when (FileKinds.byName(file.name)) {
        FileKind.IMAGE -> decodeImage(file, targetPx)
        FileKind.VIDEO -> decodeVideoFrame(file, targetPx)
        else -> null
    }

    private fun decodeImage(file: File, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetPx)
        }
        val full = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        return centerCrop(full, targetPx)
    }

    private fun decodeVideoFrame(file: File, targetPx: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            val frame = r.getFrameAtTime(-1) ?: r.frameAtTimeCompat() ?: return null
            centerCrop(frame, targetPx)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun MediaMetadataRetriever.frameAtTimeCompat(): Bitmap? = runCatching { getFrameAtTime(0) }.getOrNull()

    /** Largest power-of-two sample size that keeps the shorter edge >= targetPx. */
    fun sampleSize(width: Int, height: Int, targetPx: Int): Int {
        if (targetPx <= 0) return 1
        var sample = 1
        val shorter = minOf(width, height)
        while (shorter / (sample * 2) >= targetPx) sample *= 2
        return sample
    }

    private fun centerCrop(src: Bitmap, targetPx: Int): Bitmap {
        val side = minOf(src.width, src.height)
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        val square = Bitmap.createBitmap(src, x, y, side, side)
        if (side == targetPx) return square
        val scaled = Bitmap.createScaledBitmap(square, targetPx, targetPx, true)
        if (scaled !== square) square.recycle()
        if (scaled !== src && src !== square) { /* src may be reused by caller of decode; leave it */ }
        return scaled
    }

    private fun diskFile(token: String) = File(diskDir, "$token.jpg")

    private fun readDisk(token: String): Bitmap? {
        val f = diskFile(token)
        if (!f.isFile) return null
        f.setLastModified(System.currentTimeMillis()) // mark recently used for pruning
        return runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
    }

    private fun writeDisk(token: String, bmp: Bitmap) {
        runCatching {
            diskFile(token).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            pruneDisk()
        }
    }

    /** Prunes the disk cache to the byte budget, oldest file (by mtime) first. */
    private fun pruneDisk() {
        val files = diskDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        var i = 0
        while (total > maxDiskBytes && i < files.size) {
            total -= files[i].length()
            files[i].delete()
            i++
        }
    }

    fun clear() {
        memory.evictAll()
        diskDir.listFiles()?.forEach { it.delete() }
    }
}
