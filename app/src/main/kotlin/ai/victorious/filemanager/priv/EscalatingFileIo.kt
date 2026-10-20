package ai.victorious.filemanager.priv

import ai.victorious.filemanager.core.fs.FileEntry
import ai.victorious.filemanager.core.fs.FileIo
import ai.victorious.filemanager.core.fs.FileIoException
import ai.victorious.filemanager.core.priv.PrivilegeBackend
import ai.victorious.filemanager.core.shell.ShellCommands
import ai.victorious.filemanager.core.shell.ShellListParser
import java.io.File

/**
 * Tries normal [delegate] first; on failure (or when an elevated backend is selected after a
 * failure), uses shell list/copy/move/delete/chmod.
 */
class EscalatingFileIo(
    private val delegate: FileIo,
    private val privilege: PrivilegeManager,
) : FileIo {
    private fun elevated(): Boolean = privilege.backend() != PrivilegeBackend.NORMAL

    private fun runOrThrow(cmd: String) {
        val r = privilege.execElevated(cmd)
        if (!r.ok) throw FileIoException(r.stderr.ifBlank { r.stdout }.ifBlank { "exit ${r.code}" })
    }

    override fun exists(path: String): Boolean {
        val ok = try {
            delegate.exists(path)
        } catch (_: Exception) {
            false
        }
        if (ok || !elevated()) return ok
        return privilege.execElevated(ShellCommands.exists(path)).stdout.trim() == "1"
    }

    override fun isDirectory(path: String): Boolean {
        try {
            if (delegate.exists(path)) return delegate.isDirectory(path)
        } catch (_: Exception) {
        }
        if (!elevated()) return false
        return privilege.execElevated(ShellCommands.isDir(path)).stdout.trim() == "1"
    }

    override fun isFile(path: String): Boolean = exists(path) && !isDirectory(path)

    override fun list(path: String): List<FileEntry> {
        try {
            return delegate.list(path)
        } catch (e: Exception) {
            if (!elevated()) {
                val why = privilege.missingReason()
                throw FileIoException(why ?: (e.message ?: "Cannot list $path"), e)
            }
        }
        val r = privilege.execElevated(ShellCommands.listParsable(path))
        if (!r.ok) throw FileIoException(r.stderr.ifBlank { r.stdout }.ifBlank { "list failed" })
        val base = path.trimEnd('/')
        return ShellListParser.parse(r.stdout).map { row ->
            FileEntry(
                path = "$base/${row.name}",
                name = row.name,
                isDirectory = row.isDirectory,
                sizeBytes = row.size,
                modifiedEpochMs = row.mtimeSec * 1000,
                isHidden = row.name.startsWith('.'),
            )
        }.sortedWith(compareBy<FileEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
    }

    override fun mkdir(path: String) {
        try {
            delegate.mkdir(path)
        } catch (e: Exception) {
            if (!elevated()) throw e else runOrThrow(ShellCommands.mkdir(path))
        }
    }

    override fun mkdirs(path: String) {
        try {
            delegate.mkdirs(path)
        } catch (e: Exception) {
            if (!elevated()) throw e else runOrThrow(ShellCommands.mkdir(path))
        }
    }

    override fun rename(from: String, to: String) = move(from, to)

    override fun copy(from: String, to: String) {
        try {
            delegate.copy(from, to)
        } catch (e: Exception) {
            if (!elevated()) throw e else runOrThrow(ShellCommands.copy(from, to))
        }
    }

    override fun move(from: String, to: String) {
        try {
            delegate.move(from, to)
        } catch (e: Exception) {
            if (!elevated()) throw e else runOrThrow(ShellCommands.move(from, to))
        }
    }

    override fun deleteRecursively(path: String) {
        try {
            delegate.deleteRecursively(path)
        } catch (e: Exception) {
            if (!elevated()) throw e else runOrThrow(ShellCommands.delete(path))
        }
    }

    override fun sizeOf(path: String): Long = try {
        delegate.sizeOf(path)
    } catch (_: Exception) {
        File(path).length()
    }

    override fun childNames(dir: String): Set<String> = list(dir).map { it.name }.toSet()

    override fun readText(path: String): String {
        try {
            return delegate.readText(path)
        } catch (e: Exception) {
            if (!elevated()) throw e
            val r = privilege.execElevated(ShellCommands.cat(path))
            if (!r.ok) throw FileIoException(r.stderr.ifBlank { "read failed" }, e)
            return r.stdout
        }
    }

    override fun writeText(path: String, text: String) {
        try {
            delegate.writeText(path, text)
        } catch (e: Exception) {
            if (!elevated()) throw e
            val q = ShellCommands.quote(path)
            val escaped = text.replace("'", "'\"'\"'")
            runOrThrow("printf '%s' '$escaped' > $q")
        }
    }

    fun chmod(path: String, mode: String) {
        if (!elevated()) throw FileIoException(privilege.missingReason() ?: "chmod needs Root or Shizuku")
        runOrThrow(ShellCommands.chmod(path, mode))
    }
}
