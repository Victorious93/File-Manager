package ai.victorious.filemanager.core.photo

/**
 * Pure helpers for batch resize / convert / compress planning (dimensions + quality).
 * Actual Bitmap encode lives in the app layer.
 */
object ImageBatch {
    data class ResizeSpec(val maxWidth: Int, val maxHeight: Int, val quality: Int = 85, val format: String = "jpeg")

    fun fitWithin(srcW: Int, srcH: Int, maxW: Int, maxH: Int): Pair<Int, Int> {
        if (srcW <= 0 || srcH <= 0) return 1 to 1
        val scale = minOf(maxW.toDouble() / srcW, maxH.toDouble() / srcH, 1.0)
        return (srcW * scale).toInt().coerceAtLeast(1) to (srcH * scale).toInt().coerceAtLeast(1)
    }

    fun outputName(input: String, format: String): String {
        val base = input.substringBeforeLast('.')
        val ext = when (format.lowercase()) {
            "png" -> "png"
            "webp" -> "webp"
            else -> "jpg"
        }
        return "$base.$ext"
    }
}
