package ai.victorious.filemanager.core.media

/**
 * Pure page math for the PDF viewer. The Android `PdfRenderer` does the actual rasterising in :app;
 * this object only computes clamped page indexes and the render bitmap size for a "fit width" layout,
 * so the arithmetic (and its edge cases) can be unit-tested on the JVM.
 */
object PdfPaging {
    /** Clamps a requested page index into [0, pageCount). Returns 0 for an empty/invalid document. */
    fun clampPage(requested: Int, pageCount: Int): Int {
        if (pageCount <= 0) return 0
        if (requested < 0) return 0
        if (requested >= pageCount) return pageCount - 1
        return requested
    }

    /** Human label, e.g. "3 / 12". Empty documents read "0 / 0". */
    fun label(page: Int, pageCount: Int): String =
        if (pageCount <= 0) "0 / 0" else "${clampPage(page, pageCount) + 1} / $pageCount"

    data class RenderSize(val widthPx: Int, val heightPx: Int)

    /**
     * Fit-to-width render size for a page of native size [pageWidthPt] x [pageHeightPt] (PDF points),
     * scaled so the width equals [targetWidthPx], capped by [maxBitmapPx] on the longer edge to avoid
     * OOM on very tall pages. Aspect ratio is preserved; results are at least 1x1.
     */
    fun fitWidth(
        pageWidthPt: Int,
        pageHeightPt: Int,
        targetWidthPx: Int,
        maxBitmapPx: Int = 4096,
    ): RenderSize {
        if (pageWidthPt <= 0 || pageHeightPt <= 0 || targetWidthPx <= 0) return RenderSize(1, 1)
        val scale = targetWidthPx.toDouble() / pageWidthPt
        var w = Math.round(pageWidthPt * scale).toInt().coerceAtLeast(1)
        var h = Math.round(pageHeightPt * scale).toInt().coerceAtLeast(1)
        val longest = maxOf(w, h)
        if (longest > maxBitmapPx) {
            val shrink = maxBitmapPx.toDouble() / longest
            w = (w * shrink).toInt().coerceAtLeast(1)
            h = (h * shrink).toInt().coerceAtLeast(1)
        }
        return RenderSize(w, h)
    }
}
