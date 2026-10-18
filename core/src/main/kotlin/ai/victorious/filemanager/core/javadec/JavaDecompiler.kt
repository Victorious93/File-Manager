package ai.victorious.filemanager.core.javadec

import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import java.io.Closeable
import java.io.File

/**
 * Thin wrapper over jadx (Apache-2.0) that turns a dex/apk into readable Java.
 *
 * jadx itself is pure JVM, so this type lives in :core and is unit-testable without Android.
 * We never call [JadxDecompiler.save] (which would write every class to disk); instead we
 * decompile classes on demand via [Session.code], which keeps memory bounded to the classes
 * actually viewed. Resource decoding is disabled (we only read code here).
 */
object JavaDecompiler {

    /** Opens a jadx session over [input] (a .dex, .apk, .jar, .zip, …). Caller must [Session.close]. */
    fun open(input: File, threads: Int = 1): Session {
        val args = JadxArgs()
        args.setInputFile(input)
        args.setThreadsCount(threads.coerceAtLeast(1))
        // We only want code, and stable output even when a method can't be fully decompiled.
        args.setSkipResources(true)
        args.setShowInconsistentCode(true)
        args.setRenameValid(true)
        val jadx = JadxDecompiler(args)
        jadx.load()
        return Session(jadx)
    }

    class Session internal constructor(private val jadx: JadxDecompiler) : Closeable {
        /** Dotted full names of all top-level classes, sorted. */
        fun classNames(): List<String> = jadx.classes.map { it.fullName }.sorted()

        /** Readable Java for the top-level class that owns [fullName] (dotted), or null if absent. */
        fun code(fullName: String): String? {
            val top = topLevel(fullName)
            val cls = jadx.classes.firstOrNull { it.fullName == top || it.fullName == fullName } ?: return null
            return cls.code
        }

        /** Same as [code] but accepts a smali descriptor like `Lcom/x/Foo;`. */
        fun codeByDescriptor(descriptor: String): String? = code(descriptorToDotted(descriptor))

        override fun close() = jadx.close()
    }

    /** `Lcom/x/Foo;` -> `com.x.Foo`. */
    fun descriptorToDotted(descriptor: String): String =
        descriptor.trim().removePrefix("L").removeSuffix(";").replace('/', '.')

    private fun topLevel(dotted: String): String {
        val dollar = dotted.indexOf('$')
        return if (dollar >= 0) dotted.substring(0, dollar) else dotted
    }
}
