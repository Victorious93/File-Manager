package ai.victorious.filemanager.core.search

/** Library-style file groups (Images, Videos, Audio, Documents, Archives, APKs) chosen by extension. */
enum class FileCategory(val token: String, val label: String, val extensions: Set<String>) {
    IMAGE("image", "Images", setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "svg", "ico")),
    VIDEO("video", "Videos", setOf("mp4", "m4v", "mkv", "webm", "3gp", "mov", "avi", "wmv", "flv")),
    AUDIO("audio", "Audio", setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav", "amr", "mid", "midi", "ac3", "eac3", "ec3", "dts", "thd", "mka", "wma")),
    DOCUMENT("doc", "Documents", setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "txt", "md", "rtf", "csv", "epub")),
    ARCHIVE("archive", "Archives", setOf("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "tbz2", "tbz", "xz", "txz", "jar")),
    APK("apk", "APKs", setOf("apk", "apks", "xapk", "apkm", "aab"));

    fun matches(lowerName: String): Boolean = lowerName.substringAfterLast('.', "") in extensions

    companion object {
        /** Accepts the token and a few plural/alias spellings (`images`, `docs`, `document`, `archives`). */
        fun fromToken(text: String): FileCategory? = when (text.lowercase().trimEnd('s')) {
            "image", "photo", "picture", "pic" -> IMAGE
            "video", "movie" -> VIDEO
            "audio", "music", "song" -> AUDIO
            "doc", "document" -> DOCUMENT
            "archive", "zip" -> ARCHIVE
            "apk" -> APK
            else -> null
        }
    }
}
