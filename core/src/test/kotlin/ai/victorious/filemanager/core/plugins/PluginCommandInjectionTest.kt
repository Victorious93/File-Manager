package ai.victorious.filemanager.core.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The builder promises "paths are always single-quoted". These run the generated command through a
 * real shell: a value that merely looks like another placeholder must come out verbatim and must
 * never be re-scanned (placeholders were once substituted one after another, so a value inserted by
 * an earlier pass was rewritten by a later one).
 */
class PluginCommandInjectionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun shell(command: String): List<String> {
        assumeTrue("needs /bin/sh", File("/bin/sh").exists())
        val p = ProcessBuilder("sh", "-c", command).directory(tmp.root).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out.lines().filter { it.isNotEmpty() }
    }

    private fun action(command: String, target: ActionTarget) = PluginAction("x", "x", command, target)

    @Test
    fun aFolderNamedLikeAPlaceholderCannotBreakOutOfTheQuotes() {
        val dir = "/work/{file}"
        val file = "/work/;touch PWNED;"
        val cmd = PluginCommandBuilder.build(action("printf '%s\\n' {dir} {file}", ActionTarget.EACH_FILE), listOf(file), dir).single()

        assertEquals(listOf(dir, file), shell(cmd))
        assertFalse("file name was executed as a command", File(tmp.root, "PWNED").exists())
    }

    @Test
    fun theSameHoldsForAllFilesTargets() {
        val dir = "/work/{files}"
        val files = listOf("/work/a b", "/work/;touch PWNED2;")
        val cmd = PluginCommandBuilder.build(action("printf '%s\\n' {dir} {files}", ActionTarget.ALL_FILES), files, dir).single()

        assertEquals(listOf(dir) + files, shell(cmd))
        assertFalse(File(tmp.root, "PWNED2").exists())
    }

    @Test
    fun placeholderTextInsideAValueIsInsertedVerbatim() {
        val cmd = PluginCommandBuilder.build(action("printf '%s\\n' {file} {dir}", ActionTarget.EACH_FILE), listOf("/f"), "/d/{name}").single()
        assertEquals(listOf("/f", "/d/{name}"), shell(cmd))
    }
}
