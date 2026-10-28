package ai.victorious.filemanager.core.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPluginsTest {
    @Test fun parsesValid() {
        val d = AppPluginParser.parse("""{"id":"com.ex.apkinfo","name":"APK info","apiVersion":2,"extensions":["apk"],"maxFiles":1,"extra":true}""")
        assertEquals("APK info", d.name); assertEquals(listOf("apk"), d.extensions); assertEquals(1, d.maxFiles)
        assertEquals(false, d.wantsWrite)
    }

    @Test fun rejectsInvalid() {
        val e = runCatching { AppPluginParser.parse("""{"id":"Bad Id","name":"","apiVersion":1,"minFiles":3,"maxFiles":2,"extensions":[".apk"]}""") }.exceptionOrNull()
        assertTrue(e is PluginValidationException)
        assertEquals(5, (e as PluginValidationException).problems.size)
        assertTrue(runCatching { AppPluginParser.parse("not json") }.exceptionOrNull() is PluginValidationException)
    }

    @Test fun selectionRules() {
        val d = AppPluginDescriptor("x.y", "X", extensions = listOf("apk"), minFiles = 1, maxFiles = 2)
        assertNull(AppPluginParser.whyNot(d, listOf("/s/a.APK")))
        assertEquals("Select at least 1 file(s)", AppPluginParser.whyNot(d, emptyList()))
        assertEquals("Select at most 2 file(s)", AppPluginParser.whyNot(d, listOf("a.apk", "b.apk", "c.apk")))
        assertEquals("Only .apk files", AppPluginParser.whyNot(d, listOf("/s/a.zip")))
        assertNull(AppPluginParser.whyNot(d.copy(extensions = emptyList()), listOf("/s/noext")))
    }
}
