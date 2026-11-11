package ai.victorious.filemanager.core.shell

import ai.victorious.filemanager.core.fs.FileIoException
import java.util.Base64
import java.util.UUID

/**
 * Exact, size-safe reading and writing of a file through a privileged shell (Root or Shizuku).
 *
 * The old way put the whole file into one `sh -c` command and read it back from the command's
 * text output. That breaks in three ways: a command string is a single argv entry (about 128 KB
 * on Linux) and also crosses Binder (about 1 MB); text output is split into lines, which loses
 * the final newline and turns CRLF into LF; and non-UTF-8 bytes are replaced on the way.
 *
 * Here the bytes travel as base64, in chunks, and the file's length is checked at the end:
 *  - **read**: `dd` one chunk at a time, `base64` it, decode on this side.
 *  - **write**: send each chunk on stdin (never in the command line) to a staging file, check the
 *    staged length, then `cat` it over the target. Copying into the existing file keeps its inode,
 *    owner, permissions and SELinux label, which a rename over it would not.
 *
 * Needs `sh`, `base64`, `dd`, `wc`, `cat` and `rm` (all present in Android's toybox).
 */
object ElevatedFileTransfer {
    /** Runs one shell command, optionally feeding [stdin]. */
    fun interface Shell {
        fun run(command: String, stdin: String?): ShellResult
    }

    /** Raw bytes per round trip; about 128 KB as base64, well under the Binder and argv limits. */
    const val CHUNK_BYTES = 96 * 1024

    /** Largest file read into memory as text or bytes. */
    const val DEFAULT_MAX_BYTES = 8L * 1024 * 1024

    /** Shell-writable on Android without touching the app's own storage. */
    const val DEFAULT_STAGE_DIR = "/data/local/tmp"

    fun readText(path: String, shell: Shell, maxBytes: Long = DEFAULT_MAX_BYTES): String =
        readBytes(path, shell, maxBytes).toString(Charsets.UTF_8)

    fun writeText(path: String, text: String, shell: Shell, stageDir: String = DEFAULT_STAGE_DIR) =
        writeBytes(path, text.toByteArray(Charsets.UTF_8), shell, stageDir)

    fun readBytes(path: String, shell: Shell, maxBytes: Long = DEFAULT_MAX_BYTES): ByteArray {
        val q = ShellCommands.quote(path)
        val size = lengthOf(q, shell)
        if (size > maxBytes) throw FileIoException("File is too large to open here (${size / 1024} KB, limit ${maxBytes / 1024} KB)")
        val out = ByteArray(size.toInt())
        var filled = 0
        var chunk = 0L
        val decoder = Base64.getMimeDecoder() // tolerates the line breaks `base64` adds
        while (filled < out.size) {
            val r = shell.run("dd if=$q bs=$CHUNK_BYTES skip=$chunk count=1 2>/dev/null | base64", null)
            if (!r.ok) throw FileIoException(r.stderr.lineSequence().firstOrNull()?.ifBlank { null } ?: "read failed")
            val bytes = decoder.decode(r.stdout.trim())
            if (bytes.isEmpty()) throw FileIoException("Read stopped early at $filled of $size bytes")
            val n = minOf(bytes.size, out.size - filled)
            System.arraycopy(bytes, 0, out, filled, n)
            filled += n
            chunk++
        }
        return out
    }

    fun writeBytes(path: String, data: ByteArray, shell: Shell, stageDir: String = DEFAULT_STAGE_DIR) {
        val target = ShellCommands.quote(path)
        if (data.isEmpty()) {
            check(shell, ": > $target", null)
            return
        }
        val stage = ShellCommands.quote("${stageDir.trimEnd('/')}/.vf-write-${UUID.randomUUID()}")
        val encoder = Base64.getEncoder()
        try {
            check(shell, ": > $stage", null)
            var offset = 0
            while (offset < data.size) {
                val end = minOf(offset + CHUNK_BYTES, data.size)
                check(shell, "base64 -d >> $stage", encoder.encodeToString(data.copyOfRange(offset, end)))
                offset = end
            }
            val staged = lengthOf(stage, shell)
            if (staged != data.size.toLong()) {
                throw FileIoException("Write aborted: staged $staged of ${data.size} bytes, original left untouched")
            }
            check(shell, "cat $stage > $target", null)
        } finally {
            runCatching { shell.run("rm -f $stage", null) }
        }
    }

    private fun check(shell: Shell, command: String, stdin: String?) {
        val r = shell.run(command, stdin)
        if (!r.ok) throw FileIoException(r.stderr.lineSequence().firstOrNull()?.ifBlank { null } ?: "exit ${r.code}")
    }

    private fun lengthOf(quotedPath: String, shell: Shell): Long {
        val r = shell.run("wc -c < $quotedPath", null)
        if (!r.ok) throw FileIoException(r.stderr.lineSequence().firstOrNull()?.ifBlank { null } ?: "cannot read file size")
        return r.stdout.trim().toLongOrNull() ?: throw FileIoException("Unexpected file size output")
    }
}
