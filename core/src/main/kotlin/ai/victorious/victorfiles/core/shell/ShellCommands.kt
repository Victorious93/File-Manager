package ai.victorious.victorfiles.core.shell

/**
 * Builds POSIX-ish shell snippets for privileged file ops. Paths are single-quoted
 * with embedded single quotes escaped as: '"'"'
 */
object ShellCommands {
    fun quote(path: String): String = "'" + path.replace("'", "'\"'\"'") + "'"

    fun list(path: String): String = "ls -la " + quote(path)

    /** Machine-friendly listing: type|size|mtime|name (name may contain spaces after 3rd |). */
    fun listParsable(path: String): String {
        val q = quote(path)
        return "ls -A1 $q | while IFS= read -r n; do " +
            "p=$q; p=\"\$p/\$n\"; " +
            "if [ -d \"\$p\" ]; then t=d; elif [ -L \"\$p\" ]; then t=l; else t=f; fi; " +
            "s=\$(stat -c %s \"\$p\" 2>/dev/null || echo 0); " +
            "m=\$(stat -c %Y \"\$p\" 2>/dev/null || echo 0); " +
            "printf '%s|%s|%s|%s\\n' \"\$t\" \"\$s\" \"\$m\" \"\$n\"; done"
    }

    fun copy(from: String, to: String): String = "cp -a " + quote(from) + " " + quote(to)

    fun move(from: String, to: String): String = "mv " + quote(from) + " " + quote(to)

    fun delete(path: String): String = "rm -rf " + quote(path)

    fun mkdir(path: String): String = "mkdir -p " + quote(path)

    fun chmod(path: String, mode: String): String {
        require(mode.matches(Regex("[0-7]{3,4}"))) { "Invalid mode: $mode" }
        return "chmod $mode " + quote(path)
    }

    fun exists(path: String): String =
        "if [ -e " + quote(path) + " ]; then echo 1; else echo 0; fi"

    fun isDir(path: String): String =
        "if [ -d " + quote(path) + " ]; then echo 1; else echo 0; fi"

    fun cat(path: String): String = "cat " + quote(path)

    fun writeViaStdin(path: String): String = "cat > " + quote(path)

    fun packageDataBackup(packageName: String, destTar: String): String {
        require(packageName.matches(Regex("[A-Za-z0-9_.]+"))) { "Bad package: $packageName" }
        return "tar -cf " + quote(destTar) + " -C /data/data " + quote(packageName)
    }

    fun packageDataRestore(packageName: String, srcTar: String): String {
        require(packageName.matches(Regex("[A-Za-z0-9_.]+"))) { "Bad package: $packageName" }
        return "tar -xf " + quote(srcTar) + " -C /data/data"
    }
}

data class ShellResult(val code: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = code == 0
}

/** Parses [ShellCommands.listParsable] lines into name/type/size/mtime. */
object ShellListParser {
    data class Row(val isDirectory: Boolean, val size: Long, val mtimeSec: Long, val name: String)

    fun parse(stdout: String): List<Row> = stdout.lineSequence()
        .map { it.trimEnd() }
        .filter { it.isNotEmpty() && it.contains('|') }
        .mapNotNull { line ->
            val parts = line.split('|', limit = 4)
            if (parts.size < 4) return@mapNotNull null
            Row(
                isDirectory = parts[0] == "d",
                size = parts[1].toLongOrNull() ?: 0L,
                mtimeSec = parts[2].toLongOrNull() ?: 0L,
                name = parts[3],
            )
        }.toList()
}
