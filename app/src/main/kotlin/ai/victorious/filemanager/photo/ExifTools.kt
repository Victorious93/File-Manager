package ai.victorious.filemanager.photo

import ai.victorious.filemanager.core.photo.ImageBatch
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

object ExifTools {
    fun read(file: File): Map<String, String> {
        val exif = ExifInterface(file)
        val keys = listOf(
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_IMAGE_WIDTH,
            ExifInterface.TAG_IMAGE_LENGTH,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_ISO_SPEED_RATINGS,
        )
        return keys.mapNotNull { k -> exif.getAttribute(k)?.let { k to it } }.toMap()
    }

    fun set(file: File, edits: Map<String, String>) {
        val exif = ExifInterface(file)
        for ((k, v) in edits) exif.setAttribute(k, v)
        exif.saveAttributes()
    }

    /** Strip EXIF by rewriting pixels without metadata. */
    fun strip(file: File, out: File = file): Boolean {
        val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return false
        val format = when (file.extension.lowercase()) {
            "png" -> Bitmap.CompressFormat.PNG
            "webp" -> Bitmap.CompressFormat.WEBP
            else -> Bitmap.CompressFormat.JPEG
        }
        FileOutputStream(out).use { bmp.compress(format, 92, it) }
        bmp.recycle()
        return true
    }

    fun resizeCompress(src: File, dest: File, maxW: Int, maxH: Int, quality: Int, format: String): Boolean {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.absolutePath, opts)
        val (tw, th) = ImageBatch.fitWithin(opts.outWidth, opts.outHeight, maxW, maxH)
        val sample = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(opts.outWidth, opts.outHeight, tw, th)
        }
        val bmp = BitmapFactory.decodeFile(src.absolutePath, sample) ?: return false
        val scaled = Bitmap.createScaledBitmap(bmp, tw, th, true)
        if (scaled !== bmp) bmp.recycle()
        val fmt = when (format.lowercase()) {
            "png" -> Bitmap.CompressFormat.PNG
            "webp" -> Bitmap.CompressFormat.WEBP
            else -> Bitmap.CompressFormat.JPEG
        }
        FileOutputStream(dest).use { scaled.compress(fmt, quality.coerceIn(1, 100), it) }
        scaled.recycle()
        return true
    }

    private fun calculateInSampleSize(w: Int, h: Int, reqW: Int, reqH: Int): Int {
        var inSample = 1
        if (h > reqH || w > reqW) {
            val halfH = h / 2
            val halfW = w / 2
            while (halfH / inSample >= reqH && halfW / inSample >= reqW) inSample *= 2
        }
        return inSample.coerceAtLeast(1)
    }
}
