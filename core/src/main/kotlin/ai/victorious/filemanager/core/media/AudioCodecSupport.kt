package ai.victorious.filemanager.core.media

/**
 * Which surround / legacy audio formats VictorFiles can play and through what path.
 * Decoding comes from the bundled Media3 FFmpeg extension (LGPL 2.1+, decoders ac3, eac3,
 * dca, truehd, mlp, vorbis, opus, flac); the platform decoder is tried first.
 */
object AudioCodecSupport {
    enum class Status { PLAYABLE, CONTAINER_DEPENDENT, UNSUPPORTED }
    data class Info(val codec: String, val status: Status, val note: String)

    private val table = mapOf(
        "ac3" to Info("Dolby Digital (AC-3)", Status.PLAYABLE, "Raw AC-3 stream"),
        "eac3" to Info("Dolby Digital Plus (E-AC-3)", Status.PLAYABLE, "Raw E-AC-3 stream"),
        "ec3" to Info("Dolby Digital Plus (E-AC-3)", Status.PLAYABLE, "Raw E-AC-3 stream"),
        "mka" to Info("Matroska audio", Status.PLAYABLE, "AC-3, E-AC-3, DTS, TrueHD, FLAC, Opus, Vorbis inside MKA/MKV"),
        "dts" to Info("DTS", Status.CONTAINER_DEPENDENT, "DTS plays inside MKV/MKA, MP4 or TS; bare .dts files have no Media3 extractor"),
        "thd" to Info("Dolby TrueHD", Status.CONTAINER_DEPENDENT, "TrueHD plays inside MKV/MKA; bare .thd files have no Media3 extractor"),
        "wma" to Info("Windows Media Audio", Status.UNSUPPORTED, "WMA uses the ASF container, which Media3 cannot read; convert to another format"),
    )

    /** Extensions the file browser should treat as audio. */
    val audioExtensions: Set<String> = table.keys

    fun info(fileName: String): Info? = table[fileName.substringAfterLast('.', "").lowercase()]

    /** Friendly explanation for a playback failure, or null when we have nothing specific to add. */
    fun failureHint(fileName: String, ffmpegLoaded: Boolean): String? {
        val i = info(fileName) ?: return null
        return when {
            i.status == Status.UNSUPPORTED -> "${i.codec}: ${i.note}."
            !ffmpegLoaded -> "${i.codec} needs the FFmpeg decoder, which is not available on this device build."
            i.status == Status.CONTAINER_DEPENDENT -> "${i.codec}: ${i.note}."
            else -> null
        }
    }
}
