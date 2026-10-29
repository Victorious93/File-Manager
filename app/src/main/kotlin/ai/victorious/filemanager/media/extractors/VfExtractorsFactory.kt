package ai.victorious.filemanager.media.extractors

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory

/**
 * Media3's default extractors plus VictorFiles' ASF (WMA), raw DTS and raw TrueHD ones.
 * Ours are tried first when the file name says .wma/.asf/.dts/.thd, otherwise last, so
 * normal formats keep their stock extractor.
 */
@UnstableApi
class VfExtractorsFactory : ExtractorsFactory {
    private val defaults = DefaultExtractorsFactory()

    private fun ours(): Array<Extractor> = arrayOf(
        AsfExtractor(),
        ElementaryAudioExtractor(ElementaryAudioExtractor.Kind.DTS),
        ElementaryAudioExtractor(ElementaryAudioExtractor.Kind.TRUEHD),
    )

    override fun createExtractors(): Array<Extractor> = defaults.createExtractors() + ours()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> {
        val d = defaults.createExtractors(uri, responseHeaders)
        val ext = (uri.lastPathSegment ?: "").substringAfterLast('.', "").lowercase()
        return if (ext in PREFERRED) ours() + d else d + ours()
    }

    private companion object { val PREFERRED = setOf("wma", "asf", "dts", "thd") }
}
