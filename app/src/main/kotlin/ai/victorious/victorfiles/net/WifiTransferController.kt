package ai.victorious.victorfiles.net

import ai.victorious.victorfiles.core.fs.FileIo
import ai.victorious.victorfiles.core.net.WifiTransferConfig
import ai.victorious.victorfiles.core.net.WifiTransferServer
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.util.concurrent.atomic.AtomicReference

data class WifiTransferSession(val url: String, val pin: String?, val bindHost: String)

class WifiTransferController(private val io: FileIo) {
    private val serverRef = AtomicReference<WifiTransferServer?>(null)
    private var session: WifiTransferSession? = null

    val isRunning: Boolean get() = serverRef.get()?.isRunning() == true
    val url: String? get() = session?.url
    val pin: String? get() = session?.pin

    fun start(rootPath: String, port: Int = 8765, requirePin: Boolean = true): WifiTransferSession {
        stop()
        val pin = if (requirePin) WifiTransferServer.randomPin() else null
        val host = WifiTransferServer.lanIpv4() ?: "127.0.0.1"
        val server = WifiTransferServer(io, WifiTransferConfig(rootPath, port, pin, host))
        server.startServer()
        serverRef.set(server)
        val s = WifiTransferSession("http://$host:$port/", pin, host)
        session = s
        return s
    }

    fun stop() {
        serverRef.getAndSet(null)?.stopServer()
        session = null
    }

    fun qrBitmap(content: String, size: Int = 512): Bitmap {
        val bits = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(EncodeHintType.MARGIN to 1),
        )
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (bits[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }
}
