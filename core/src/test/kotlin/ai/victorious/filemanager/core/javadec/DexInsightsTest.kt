package ai.victorious.filemanager.core.javadec

import ai.victorious.filemanager.core.apk.DexTools
import ai.victorious.filemanager.core.apk.SmaliAssembler
import org.junit.Assert.*
import org.junit.Test

class DexInsightsTest {
    private fun dexOf(vararg smali: String) =
        DexTools.load(SmaliAssembler.assembleDex(smali.toList()))

    private val foo = """
        .class public Lcom/example/Foo;
        .super Ljava/lang/Object;
        .method public hi()Ljava/lang/String;
            .registers 2
            const-string v0, "secret-token"
            return-object v0
        .end method
    """.trimIndent()

    private val bar = """
        .class public Lcom/example/Bar;
        .super Ljava/lang/Object;
        .method public bye()Ljava/lang/String;
            .registers 2
            const-string v0, "other"
            return-object v0
        .end method
    """.trimIndent()

    @Test fun findsStringConstants() {
        val c = DexInsights.stringConstants(dexOf(foo))
        assertTrue(c.contains("secret-token"))
    }

    @Test fun comparesClassesAndMethods() {
        val a = dexOf(foo)
        val b = dexOf(foo, bar)
        val cd = DexInsights.compareClasses(a, b)
        assertTrue(cd.onlyInB.contains("Lcom/example/Bar;"))
        assertTrue(cd.onlyInA.isEmpty())
        assertFalse(cd.identical)
        val md = DexInsights.compareMethods(a, a)
        assertTrue(md.identical)
    }
}
