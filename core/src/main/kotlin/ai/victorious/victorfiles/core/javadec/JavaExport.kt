package ai.victorious.victorfiles.core.javadec

import java.io.File

/** Thrown when a requested export path would escape the chosen output directory. */
class UnsafeExportPathException(message: String) : IllegalArgumentException(message)

/**
 * Maps dotted class names to safe `.java` paths under a chosen output folder. Inner classes
 * (`com.x.Foo$Bar`) export to their top-level file. Every resulting path is verified to stay
 * inside the output directory, so a hostile class name can never traverse out of it.
 */
object JavaExport {
    private val VALID = Regex("""[A-Za-z0-9_$.]+""")

    /** `com.x.Foo` / `com.x.Foo$Bar` -> `com/x/Foo.java`. */
    fun relativePath(fullClassName: String): String {
        val name = fullClassName.trim()
        if (name.isEmpty() || !VALID.matches(name) || name.startsWith(".") || name.endsWith(".") || name.contains("..")) {
            throw UnsafeExportPathException("Illegal class name: $fullClassName")
        }
        val topLevel = run {
            val lastDot = name.lastIndexOf('.')
            val pkg = if (lastDot >= 0) name.substring(0, lastDot) else ""
            val simple = (if (lastDot >= 0) name.substring(lastDot + 1) else name).substringBefore('$')
            if (pkg.isEmpty()) simple else "$pkg.$simple"
        }
        return topLevel.replace('.', '/') + ".java"
    }

    /** Resolves [fullClassName] to a [File] under [outDir], refusing any path that escapes it. */
    fun fileIn(outDir: File, fullClassName: String): File {
        val rel = relativePath(fullClassName)
        val out = File(outDir, rel)
        val base = outDir.canonicalFile
        val resolved = out.canonicalFile
        if (resolved != base && !resolved.path.startsWith(base.path + File.separator)) {
            throw UnsafeExportPathException("Path escapes output dir: $fullClassName")
        }
        return resolved
    }
}
