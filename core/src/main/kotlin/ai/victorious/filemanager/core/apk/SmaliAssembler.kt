package ai.victorious.filemanager.core.apk

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.writer.builder.DexBuilder
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import com.android.tools.smali.smali.InvalidToken
import com.android.tools.smali.smali.smaliFlexLexer
import com.android.tools.smali.smali.smaliParser
import com.android.tools.smali.smali.smaliTreeWalker
import java.io.File
import java.io.StringReader
import org.antlr.runtime.CommonTokenStream
import org.antlr.runtime.RecognitionException
import org.antlr.runtime.TokenStream
import org.antlr.runtime.tree.CommonTreeNodeStream
import org.antlr.runtime.tree.TreeNodeStream

/** One assembler diagnostic. [line] is 1-based; 0 when unknown. */
data class SmaliError(val line: Int, val column: Int, val message: String) {
    override fun toString() = if (line > 0) "line $line:$column $message" else message
}

class SmaliAssemblyException(val errors: List<SmaliError>) :
    IllegalArgumentException(errors.joinToString("\n").ifEmpty { "Smali assembly failed" })

/**
 * Assembles smali text into DEX using Google's smali assembler (BSD-3-Clause), collecting
 * lexer/parser/semantic errors with line numbers instead of printing them to stderr.
 */
object SmaliAssembler {
    const val DEFAULT_API = 21

    private class CollectingParser(input: TokenStream, val sink: MutableList<SmaliError>) : smaliParser(input) {
        override fun displayRecognitionError(tokenNames: Array<out String>?, e: RecognitionException) {
            sink += SmaliError(e.line, e.charPositionInLine + 1, getErrorMessage(e, tokenNames))
        }
    }

    private class CollectingWalker(input: TreeNodeStream, val sink: MutableList<SmaliError>) : smaliTreeWalker(input) {
        override fun displayRecognitionError(tokenNames: Array<out String>?, e: RecognitionException) {
            sink += SmaliError(e.line, e.charPositionInLine + 1, getErrorMessage(e, tokenNames))
        }
    }

    /** Assembles one class (one .smali file's text) into a standalone DEX; throws [SmaliAssemblyException]. */
    fun assembleClass(smali: String, apiLevel: Int = DEFAULT_API): ClassDef {
        val dex = assembleDex(listOf(smali), apiLevel)
        return DexBackedDexFile(Opcodes.forApi(apiLevel), dex).classes.singleOrNull()
            ?: throw SmaliAssemblyException(listOf(SmaliError(0, 0, "Expected exactly one class")))
    }

    /** Assembles several smali sources into one DEX image. */
    fun assembleDex(sources: List<String>, apiLevel: Int = DEFAULT_API): ByteArray {
        val builder = DexBuilder(Opcodes.forApi(apiLevel))
        val errors = mutableListOf<SmaliError>()
        for (src in sources) addSource(builder, src, apiLevel, errors)
        if (errors.isNotEmpty()) throw SmaliAssemblyException(errors)
        val store = MemoryDataStore()
        builder.writeTo(store)
        return store.data.let { d -> if (d.size == store.size) d else d.copyOf(store.size) }
    }

    private fun addSource(builder: DexBuilder, src: String, api: Int, errors: MutableList<SmaliError>) {
        val local = mutableListOf<SmaliError>()
        val lexer = smaliFlexLexer(StringReader(src), api).apply { setSuppressErrors(true) }
        val tokens = CommonTokenStream(lexer)
        tokens.fill()
        for (t in tokens.tokens) if (t is InvalidToken) local += SmaliError(t.line, t.charPositionInLine + 1, t.message ?: "Invalid token '${t.text}'")
        tokens.seek(0)
        val parser = CollectingParser(tokens, local).apply { setVerboseErrors(false); setApiLevel(api) }
        val result = try { parser.smali_file() } catch (e: RecognitionException) {
            local += SmaliError(e.line, e.charPositionInLine + 1, e.toString()); null
        }
        if (local.isEmpty() && (parser.numberOfSyntaxErrors > 0 || lexer.numberOfSyntaxErrors > 0)) local += SmaliError(0, 0, "Syntax error")
        if (local.isNotEmpty() || result == null) { errors += local.distinct(); return }
        val nodes = CommonTreeNodeStream(result.tree).apply { setTokenStream(tokens) }
        val walker = CollectingWalker(nodes, local).apply { setApiLevel(api); setVerboseErrors(false); setDexBuilder(builder) }
        try {
            walker.smali_file()
        } catch (e: RecognitionException) {
            local += SmaliError(e.line, e.charPositionInLine + 1, e.message ?: e.toString())
        } catch (e: Exception) {
            local += SmaliError(0, 0, e.message ?: e.toString())
        }
        if (local.isEmpty() && walker.numberOfSyntaxErrors > 0) local += SmaliError(0, 0, "Semantic error")
        errors += local
    }

    /** Returns [dexBytes] with [newClass] replacing (or added alongside) the class of the same type. */
    fun replaceClass(dexBytes: ByteArray, newClass: ClassDef, apiLevel: Int? = null): ByteArray {
        val original = DexBackedDexFile(Opcodes.getDefault(), dexBytes)
        val api = apiLevel ?: original.opcodes.api
        val pool = DexPool(Opcodes.forApi(api))
        for (c in original.classes) if (c.type != newClass.type) pool.internClass(c)
        pool.internClass(newClass)
        val store = MemoryDataStore()
        pool.writeTo(store)
        return store.data.let { d -> if (d.size == store.size) d else d.copyOf(store.size) }
    }

    /** Assembles [smali] and writes it into [dexFile] (e.g. `extracted/classes.dex`) in place. Returns class type. */
    fun replaceInDexFile(dexFile: File, smali: String, apiLevel: Int = DEFAULT_API): String {
        val cls = assembleClass(smali, apiLevel)
        val out = replaceClass(dexFile.readBytes(), cls, apiLevel)
        val tmp = File(dexFile.path + ".tmp")
        tmp.writeBytes(out)
        if (!tmp.renameTo(dexFile)) { dexFile.writeBytes(out); tmp.delete() }
        return cls.type
    }

    /** Finds which classesN.dex in an extracted folder defines [descriptor]. */
    fun dexContaining(dir: File, descriptor: String): File? =
        DexTools.listDex(dir).firstOrNull { s -> DexTools.load(s).classes.any { it.type == descriptor } }?.container
}
