package ai.victorious.filemanager.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.sharp.InsertDriveFile
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material.icons.sharp.Android
import androidx.compose.material.icons.sharp.AudioFile
import androidx.compose.material.icons.sharp.Code
import androidx.compose.material.icons.sharp.Description
import androidx.compose.material.icons.sharp.Folder
import androidx.compose.material.icons.sharp.FolderZip
import androidx.compose.material.icons.sharp.Image
import androidx.compose.material.icons.sharp.PictureAsPdf
import androidx.compose.material.icons.sharp.VideoFile
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Icon family for file types. The enum names are persisted: add entries, never rename.
 * [colourful] tints each file type with its own hue instead of the theme colours.
 */
enum class IconStyle(val label: String, val colourful: Boolean = false) {
    OUTLINED("Outlined"),
    ROUNDED("Rounded"),
    SHARP("Sharp"),
    FILLED("Filled"),
    /** Filled icons, each file type in its own colour. */
    COLOURFUL("Colourful", colourful = true),
}

val LocalIconStyle = staticCompositionLocalOf { IconStyle.OUTLINED }

/** What a browser row shows for an entry. Pure, so it is unit-tested. */
enum class FileCategory(val hue: Long) {
    FOLDER(0xFFF5B83D),
    IMAGE(0xFF34A853),
    VIDEO(0xFFE8453C),
    AUDIO(0xFFAB47BC),
    ARCHIVE(0xFF8D6E63),
    APK(0xFF3DDC84),
    PDF(0xFFD93025),
    CODE(0xFF4285F4),
    DOCUMENT(0xFF1E88E5),
    OTHER(0xFF78909C),
    ;

    val colour: Color get() = Color(hue)
}

private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg", "avif")
private val VIDEO_EXT = setOf("mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v", "ts", "flv")
private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma", "amr", "mid")
private val ARCHIVE_EXT = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst", "jar")
private val APK_EXT = setOf("apk", "apks", "xapk", "apkm", "aab", "dex")
private val CODE_EXT = setOf(
    "kt", "kts", "java", "py", "js", "ts", "json", "xml", "html", "css", "sh", "c", "cpp", "h", "rs", "go",
    "smali", "gradle", "yml", "yaml", "toml", "sql", "md",
)
private val DOC_EXT = setOf("txt", "doc", "docx", "odt", "rtf", "xls", "xlsx", "ods", "csv", "ppt", "pptx", "odp", "epub", "log")

fun fileCategory(name: String, isDirectory: Boolean): FileCategory {
    if (isDirectory) return FileCategory.FOLDER
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        in IMAGE_EXT -> FileCategory.IMAGE
        in VIDEO_EXT -> FileCategory.VIDEO
        in AUDIO_EXT -> FileCategory.AUDIO
        in ARCHIVE_EXT -> FileCategory.ARCHIVE
        in APK_EXT -> FileCategory.APK
        "pdf" -> FileCategory.PDF
        in CODE_EXT -> FileCategory.CODE
        in DOC_EXT -> FileCategory.DOCUMENT
        else -> FileCategory.OTHER
    }
}

fun FileCategory.icon(style: IconStyle): ImageVector = when (style) {
    IconStyle.OUTLINED -> when (this) {
        FileCategory.FOLDER -> Icons.Outlined.Folder
        FileCategory.IMAGE -> Icons.Outlined.Image
        FileCategory.VIDEO -> Icons.Outlined.VideoFile
        FileCategory.AUDIO -> Icons.Outlined.AudioFile
        FileCategory.ARCHIVE -> Icons.Outlined.FolderZip
        FileCategory.APK -> Icons.Outlined.Android
        FileCategory.PDF -> Icons.Outlined.PictureAsPdf
        FileCategory.CODE -> Icons.Outlined.Code
        FileCategory.DOCUMENT -> Icons.Outlined.Description
        FileCategory.OTHER -> Icons.AutoMirrored.Outlined.InsertDriveFile
    }
    IconStyle.ROUNDED -> when (this) {
        FileCategory.FOLDER -> Icons.Rounded.Folder
        FileCategory.IMAGE -> Icons.Rounded.Image
        FileCategory.VIDEO -> Icons.Rounded.VideoFile
        FileCategory.AUDIO -> Icons.Rounded.AudioFile
        FileCategory.ARCHIVE -> Icons.Rounded.FolderZip
        FileCategory.APK -> Icons.Rounded.Android
        FileCategory.PDF -> Icons.Rounded.PictureAsPdf
        FileCategory.CODE -> Icons.Rounded.Code
        FileCategory.DOCUMENT -> Icons.Rounded.Description
        FileCategory.OTHER -> Icons.AutoMirrored.Rounded.InsertDriveFile
    }
    IconStyle.SHARP -> when (this) {
        FileCategory.FOLDER -> Icons.Sharp.Folder
        FileCategory.IMAGE -> Icons.Sharp.Image
        FileCategory.VIDEO -> Icons.Sharp.VideoFile
        FileCategory.AUDIO -> Icons.Sharp.AudioFile
        FileCategory.ARCHIVE -> Icons.Sharp.FolderZip
        FileCategory.APK -> Icons.Sharp.Android
        FileCategory.PDF -> Icons.Sharp.PictureAsPdf
        FileCategory.CODE -> Icons.Sharp.Code
        FileCategory.DOCUMENT -> Icons.Sharp.Description
        FileCategory.OTHER -> Icons.AutoMirrored.Sharp.InsertDriveFile
    }
    IconStyle.FILLED, IconStyle.COLOURFUL -> when (this) {
        FileCategory.FOLDER -> Icons.Filled.Folder
        FileCategory.IMAGE -> Icons.Filled.Image
        FileCategory.VIDEO -> Icons.Filled.VideoFile
        FileCategory.AUDIO -> Icons.Filled.AudioFile
        FileCategory.ARCHIVE -> Icons.Filled.FolderZip
        FileCategory.APK -> Icons.Filled.Android
        FileCategory.PDF -> Icons.Filled.PictureAsPdf
        FileCategory.CODE -> Icons.Filled.Code
        FileCategory.DOCUMENT -> Icons.Filled.Description
        FileCategory.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}
