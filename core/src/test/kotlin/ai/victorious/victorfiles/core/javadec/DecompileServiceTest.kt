package ai.victorious.victorfiles.core.javadec

import ai.victorious.victorfiles.core.apk.SmaliAssembler
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DecompileServiceTest {
    private val smali = """
        .class public Lcom/example/Svc;
        .super Ljava/lang/Object;
        .method public constructor <init>()V
            .registers 1
            invoke-direct {p0}, Ljava/lang/Object;-><init>()V
            return-void
        .end method
    """.trimIndent()

    private fun sampleDex(): File {
        val dex = SmaliAssembler.assembleDex(listOf(smali))
        return File.createTempFile("svc", ".dex").apply { writeBytes(dex); deleteOnExit() }
    }

    @Test fun decompilesThenServesFromCache() {
        val dir = Files.createTempDirectory("svc").toFile()
        val svc = DecompileService(dir)
        val dex = sampleDex()
        val h = svc.hashInput(dex)
        val first = svc.runDecompile(dex, h, "com.example.Svc") {}
        assertEquals(DecompileOrigin.DECOMPILED, first.origin)
        assertTrue(first.code.contains("class Svc"))
        val second = svc.runDecompile(dex, h, "com.example.Svc") {}
        assertEquals(DecompileOrigin.MEMORY, second.origin)
        // A fresh service reads it from disk.
        val fromDisk = DecompileService(dir).runDecompile(dex, h, "com.example.Svc") {}
        assertEquals(DecompileOrigin.DISK, fromDisk.origin)
        svc.close()
    }

    @Test fun cancellationThrowsAndDoesNotCache() {
        val dir = Files.createTempDirectory("svc").toFile()
        val svc = DecompileService(dir)
        val dex = sampleDex()
        val h = svc.hashInput(dex)
        try {
            svc.runDecompile(dex, h, "com.example.Svc") { throw CancellationException("stop") }
            fail("expected cancellation")
        } catch (e: CancellationException) { /* expected */ }
        // Nothing cached.
        assertNull(DecompileCache.read(dir, DecompileCache.key(h, "com.example.Svc")))
        svc.close()
    }

    @Test fun rejectsMissingClass() {
        val dir = Files.createTempDirectory("svc").toFile()
        val svc = DecompileService(dir)
        val dex = sampleDex()
        val h = svc.hashInput(dex)
        try {
            svc.runDecompile(dex, h, "com.example.Nope") {}
            fail("expected NoSuchElement")
        } catch (e: NoSuchElementException) { /* expected */ }
        svc.close()
    }
}
