package ai.victorious.filemanager.core.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PluginsTest {
    private val good = """
        {"id":"checksum","name":"Checksums","apiVersion":1,
         "capabilities":["shell","read_files"],
         "actions":[{"id":"sha256","title":"SHA-256","command":"sha256sum {file}","target":"each_file"},
                    {"id":"all","title":"All","command":"ls -l {files}","target":"all_files","extensions":["apk"]}]}
    """.trimIndent()

    @Test
    fun parsesValidManifest() {
        val m = PluginManifestParser.parse(good)
        assertEquals("checksum", m.id)
        assertEquals(2, m.actions.size)
        assertEquals(ActionTarget.ALL_FILES, m.actions[1].target)
    }

    private fun problems(json: String): List<String> = try {
        PluginManifestParser.parse(json); fail("expected invalid"); emptyList()
    } catch (e: PluginValidationException) { e.problems }

    @Test
    fun rejectsUndeclaredCapabilities() {
        val p = problems("""{"id":"net","name":"N","capabilities":["shell","read_files"],"actions":[{"id":"up","title":"Up","command":"curl -T {file} http://x"}]}""")
        assertTrue(p.any { "network" in it })
        val w = problems("""{"id":"wr","name":"W","capabilities":["shell","read_files"],"actions":[{"id":"rm","title":"Rm","command":"rm {file}"}]}""")
        assertTrue(w.any { "write_files" in it })
        val s = problems("""{"id":"ns","name":"S","capabilities":["read_files"],"actions":[{"id":"a","title":"A","command":"cat {file}"}]}""")
        assertTrue(s.any { "'shell'" in it })
    }

    @Test
    fun rejectsBadIdsPlaceholdersAndUnknownFields() {
        val p = problems("""{"id":"Bad Id","name":"","capabilities":["shell","read_files"],"actions":[{"id":"a","title":"A","command":"cat {file} {secret}"}]}""")
        assertTrue(p.any { "id must" in it })
        assertTrue(p.any { "name is required" in it })
        assertTrue(p.any { "{secret}" in it })
        val unknown = problems("""{"id":"ok","name":"x","dex":"evil.dex","actions":[]}""")
        assertTrue(unknown.first().startsWith("Not a valid manifest"))
    }

    @Test
    fun buildsQuotedCommands() {
        val m = PluginManifestParser.parse(good)
        val cmds = PluginCommandBuilder.build(m.actions[0], listOf("/sd/a b.txt", "/sd/it's.txt"), "/sd")
        assertEquals(listOf("sha256sum '/sd/a b.txt'", "sha256sum '/sd/it'\"'\"'s.txt'"), cmds)
        val all = PluginCommandBuilder.build(m.actions[1], listOf("/sd/x.apk", "/sd/y.txt"), "/sd")
        assertEquals(listOf("ls -l '/sd/x.apk'"), all)
    }

    @Test
    fun scriptRunnerWrapsVariables() {
        val s = SavedScript("1", "wc", "wc -c \"\$FILE\"", perFile = true)
        val cmds = ScriptRunnerSupport.commandsFor(s, listOf("/a/b c"), "/a")
        assertEquals(1, cmds.size)
        assertTrue(cmds[0].startsWith("DIR='/a'; cd \"\$DIR\" || exit 1; FILE='/a/b c'; NAME='b c'; "))
        val once = ScriptRunnerSupport.commandsFor(s.copy(perFile = false, body = "ls"), listOf("/a/x"), "/a")
        assertTrue(once[0].contains("set -- '/a/x'; ls"))
    }

    @Test
    fun bundledSamplePluginsAreValid() {
        val dir = java.io.File("../app/src/main/assets/plugins")
        val files = dir.listFiles { f -> f.extension == "json" }.orEmpty()
        assertTrue("expected sample plugins in ${dir.absolutePath}", files.size >= 2)
        for (f in files) {
            val m = PluginManifestParser.parse(f.readText())
            assertTrue(m.capabilities.none { it == Capability.NETWORK || it == Capability.WRITE_FILES || it == Capability.ELEVATED })
        }
    }
}
