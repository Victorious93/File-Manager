package ai.victorious.victorfiles.core.javadec

import ai.victorious.victorfiles.core.apk.SmaliAssembler
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class JavaDecompilerTest {
    private val sampleSmali = """
        .class public Lcom/example/Hello;
        .super Ljava/lang/Object;

        .method public constructor <init>()V
            .registers 1
            invoke-direct {p0}, Ljava/lang/Object;-><init>()V
            return-void
        .end method

        .method public greet()Ljava/lang/String;
            .registers 2
            const-string v0, "hello world"
            return-object v0
        .end method
    """.trimIndent()

    @Test fun decompilesDexToJava() {
        val dex = SmaliAssembler.assembleDex(listOf(sampleSmali))
        val tmp = File.createTempFile("sample", ".dex").apply { writeBytes(dex); deleteOnExit() }
        JavaDecompiler.open(tmp).use { s ->
            val names = s.classNames()
            assertTrue("class listed: $names", names.contains("com.example.Hello"))
            val java = s.codeByDescriptor("Lcom/example/Hello;") ?: error("no code")
            assertTrue("has class decl:\n$java", java.contains("class Hello"))
            assertTrue("has method:\n$java", java.contains("greet"))
            assertTrue("has string literal:\n$java", java.contains("hello world"))
        }
    }
}
