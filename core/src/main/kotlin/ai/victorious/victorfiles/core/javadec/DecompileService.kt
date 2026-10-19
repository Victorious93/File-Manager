package ai.victorious.victorfiles.core.javadec

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

enum class DecompileOrigin { MEMORY, DISK, DECOMPILED }

data class DecompileResult(val code: String, val origin: DecompileOrigin)

/**
 * Orchestrates on-demand decompilation: content-hash cache (memory + disk), a single reused jadx
 * session per input, a hard input-size cap, and cancellation checkpoints so a cancelled request
 * never pollutes the cache.
 *
 * Memory bounding: jadx runs in-process, so we can't impose a hard JVM heap cap. Instead we (a)
 * refuse inputs larger than [maxInputBytes], (b) decompile one class at a time rather than the
 * whole app, (c) keep only one open session, and (d) cap the in-memory result map. Callers should
 * invoke [decompile] from a background dispatcher (it already hops to [Dispatchers.Default]).
 */
class DecompileService(
    private val cacheDir: File,
    private val diskBudgetBytes: Long = 64L * 1024 * 1024,
    private val maxInputBytes: Long = 96L * 1024 * 1024,
    private val memoryEntries: Int = 24,
) {
    private val memory = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > memoryEntries
    }
    private var openInputHash: String? = null
    private var openSession: JavaDecompiler.Session? = null

    /** Content hash of an input file (used as the dex/apk identity in cache keys). */
    fun hashInput(input: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        input.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Decompiles [className] from [input]; honours coroutine cancellation. */
    suspend fun decompile(input: File, className: String, inputHash: String = hashInput(input)): DecompileResult =
        withContext(Dispatchers.Default) {
            runDecompile(input, inputHash, className) { coroutineContext.ensureActive() }
        }

    /**
     * Synchronous core used by [decompile] and by tests. [checkpoint] is invoked at each
     * cancellation boundary and should throw (e.g. [CancellationException]) to abort.
     */
    fun runDecompile(input: File, inputHash: String, className: String, checkpoint: () -> Unit): DecompileResult {
        checkpoint()
        val key = DecompileCache.key(inputHash, className)
        synchronized(memory) { memory[key] }?.let { return DecompileResult(it, DecompileOrigin.MEMORY) }
        DecompileCache.read(cacheDir, key)?.let {
            synchronized(memory) { memory[key] = it }
            return DecompileResult(it, DecompileOrigin.DISK)
        }
        require(input.length() <= maxInputBytes) {
            "Input ${input.name} is ${input.length()} bytes, over the ${maxInputBytes} byte limit for in-app decompile"
        }
        checkpoint()
        val session = sessionFor(input, inputHash)
        val code = session.code(className) ?: throw NoSuchElementException("Class not found: $className")
        checkpoint() // drop the result (don't cache) if cancelled during decompile
        synchronized(memory) { memory[key] = code }
        DecompileCache.write(cacheDir, key, code, diskBudgetBytes)
        return DecompileResult(code, DecompileOrigin.DECOMPILED)
    }

    @Synchronized
    private fun sessionFor(input: File, inputHash: String): JavaDecompiler.Session {
        if (openInputHash == inputHash) return openSession!!
        openSession?.close()
        openSession = JavaDecompiler.open(input)
        openInputHash = inputHash
        return openSession!!
    }

    @Synchronized
    fun close() {
        openSession?.close()
        openSession = null
        openInputHash = null
        synchronized(memory) { memory.clear() }
    }
}
