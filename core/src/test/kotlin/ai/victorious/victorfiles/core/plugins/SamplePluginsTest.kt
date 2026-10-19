package ai.victorious.victorfiles.core.plugins

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The sample plugins shipped in the app's assets must pass the same validation as user plugins. */
class SamplePluginsTest {
    @Test
    fun bundledSamplesParse() {
        val dir = File("../app/src/main/assets/plugins")
        val files = dir.listFiles { f -> f.extension == "json" }.orEmpty()
        assertTrue("no samples found in ${dir.absolutePath}", files.isNotEmpty())
        for (f in files) {
            val result = runCatching { PluginManifestParser.parse(f.readText()) }
            assertTrue("${f.name}: ${(result.exceptionOrNull() as? PluginValidationException)?.problems ?: result.exceptionOrNull()}", result.isSuccess)
        }
    }
}
