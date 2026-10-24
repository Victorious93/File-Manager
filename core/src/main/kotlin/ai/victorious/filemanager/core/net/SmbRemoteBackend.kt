package ai.victorious.filemanager.core.net

import ai.victorious.filemanager.core.fs.FileEntry
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet

class SmbRemoteBackend(
    private val connection: SavedConnection,
    password: String?,
) : RemoteBackend {
    override val connectionId: String = connection.id
    private val client = SMBClient()
    private val smbConnection: Connection = client.connect(connection.host)
    private val session: Session = smbConnection.authenticate(
        AuthenticationContext(connection.username, (password ?: "").toCharArray(), null),
    )
    private val shareName = connection.share.ifBlank { throw IllegalArgumentException("SMB share required") }
    private val share: DiskShare = session.connectShare(shareName) as DiskShare

    private fun norm(path: String): String = path.trim('/').replace('/', '\\')

    override fun list(remotePath: String): List<FileEntry> {
        val p = norm(remotePath)
        return share.list(p).mapNotNull { info ->
            val name = info.fileName
            if (name == "." || name == "..") return@mapNotNull null
            val child = when {
                remotePath.isEmpty() || remotePath == "/" -> "/$name"
                else -> "${remotePath.trimEnd('/')}/$name"
            }
            val isDir = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
            FileEntry(
                path = RemotePaths.toVirtual(connectionId, child),
                name = name,
                isDirectory = isDir,
                sizeBytes = info.endOfFile,
                modifiedEpochMs = info.lastWriteTime.toEpochMillis(),
                isHidden = name.startsWith('.'),
            )
        }
    }

    override fun exists(remotePath: String): Boolean =
        remotePath == "/" || share.fileExists(norm(remotePath)) || share.folderExists(norm(remotePath))

    override fun isDirectory(remotePath: String): Boolean =
        remotePath == "/" || share.folderExists(norm(remotePath))

    override fun mkdir(remotePath: String) {
        share.mkdir(norm(remotePath))
    }

    override fun delete(remotePath: String) {
        if (isDirectory(remotePath)) share.rmdir(norm(remotePath), true)
        else share.rm(norm(remotePath))
    }

    override fun rename(from: String, to: String) {
        share.openFile(
            norm(from),
            EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_WRITE),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE, SMB2ShareAccess.FILE_SHARE_DELETE),
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.noneOf(com.hierynomus.mssmb2.SMB2CreateOptions::class.java),
        ).use { file -> file.rename(norm(to), true) }
    }

    override fun openInput(remotePath: String): InputStream =
        share.openFile(
            norm(remotePath),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        ).inputStream

    override fun openOutput(remotePath: String): OutputStream =
        share.openFile(
            norm(remotePath),
            EnumSet.of(AccessMask.GENERIC_WRITE),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            null,
        ).outputStream

    override fun sizeOf(remotePath: String): Long =
        runCatching {
            share.openFile(
                norm(remotePath),
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            ).use { it.fileInformation.standardInformation.endOfFile }
        }.getOrDefault(0L)

    override fun close() {
        runCatching { share.close() }
        runCatching { session.close() }
        runCatching { smbConnection.close() }
        runCatching { client.close() }
    }
}
