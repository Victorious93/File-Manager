package ai.victorious.filemanager.media

import ai.victorious.filemanager.core.media.pro.StreamSupport
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import java.io.IOException
import java.util.EnumSet

/** Media3 DataSource for `smb://[domain;][user[:pass]@]host/share/path` using smbj positional reads. */
@UnstableApi
class SmbDataSource : BaseDataSource(true) {
    private var uri: Uri? = null
    private var client: SMBClient? = null
    private var conn: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null
    private var file: File? = null
    private var position = 0L
    private var remaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val t = StreamSupport.parseSmb(dataSpec.uri.toString()) ?: throw IOException("bad smb uri")
        uri = dataSpec.uri
        val c = SMBClient().also { client = it }
        val cn = c.connect(t.host).also { conn = it }
        val auth = if (t.user == null) AuthenticationContext.anonymous()
        else AuthenticationContext(t.user, (t.password ?: "").toCharArray(), t.domain)
        val s = cn.authenticate(auth).also { session = it }
        val sh = (s.connectShare(t.share) as? DiskShare ?: throw IOException("not a disk share")).also { share = it }
        val f = sh.openFile(
            t.path, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
        ).also { file = it }
        val size = f.fileInformation.standardInformation.endOfFile
        position = dataSpec.position
        if (position > size) throw IOException("position beyond end")
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else size - position
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val want = minOf(length.toLong(), remaining).toInt()
        val n = file!!.read(buffer, position, offset, want)
        if (n <= 0) return C.RESULT_END_OF_INPUT
        position += n; remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        runCatching { file?.close() }; runCatching { share?.close() }; runCatching { session?.close() }
        runCatching { conn?.close() }; runCatching { client?.close() }
        file = null; share = null; session = null; conn = null; client = null
        if (uri != null) { uri = null; transferEnded() }
    }
}

/** Routes smb:// to [SmbDataSource]; everything else to Media3's DefaultDataSource (file, content, http). */
@UnstableApi
class RoutingDataSourceFactory(private val context: android.content.Context) : DataSource.Factory {
    private val default = DefaultDataSource.Factory(context)
    override fun createDataSource(): DataSource = Router(default.createDataSource())

    private class Router(private val fallback: DataSource) : DataSource by fallback {
        private var active: DataSource = fallback
        override fun open(dataSpec: DataSpec): Long {
            active = if (dataSpec.uri.scheme.equals("smb", true)) SmbDataSource() else fallback
            return active.open(dataSpec)
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = active.read(buffer, offset, length)
        override fun getUri() = active.uri
        override fun close() { active.close(); active = fallback }
        override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) = fallback.addTransferListener(transferListener)
    }
}
