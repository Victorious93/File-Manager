package ai.victorious.victorfiles.core.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellAndAnsiTest {
    @Test fun quoteEscapesSingleQuotes() {
        assertEquals("'foo'", ShellCommands.quote("foo"))
        // POSIX: close ', quote "', reopen '
        assertEquals("'a'\"'\"'b'", ShellCommands.quote("a'b"))
    }

    @Test fun buildersContainQuotedPathsAndSafeModes() {
        assertTrue(ShellCommands.copy("/a/b", "/c/d").contains("cp -a"))
        assertTrue(ShellCommands.delete("/tmp/x").startsWith("rm -rf"))
        assertEquals("chmod 755 '/system/bin/x'", ShellCommands.chmod("/system/bin/x", "755"))
        try {
            ShellCommands.chmod("/x", "999")
            throw AssertionError("expected")
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(ShellCommands.packageDataBackup("com.ex.app", "/sdcard/a.tar").contains("/data/data"))
        try {
            ShellCommands.packageDataBackup("com;rm", "/t")
            throw AssertionError()
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun listParser() {
        val rows = ShellListParser.parse("d|0|100|Download\nf|12|200|a b.txt\n")
        assertEquals(2, rows.size)
        assertTrue(rows[0].isDirectory)
        assertEquals("a b.txt", rows[1].name)
        assertEquals(12L, rows[1].size)
    }

    @Test fun ansiColoursAndStrip() {
        val real = "\u001B[1;32mOK\u001B[0m plain \u001B[31mERR\u001B[0m"
        val spans = AnsiParser.parse(real)
        assertEquals("OK", spans[0].text)
        assertTrue(spans[0].bold)
        assertEquals(2, spans[0].fg)
        assertEquals(" plain ", spans[1].text)
        assertEquals("ERR", spans[2].text)
        assertEquals(1, spans[2].fg)
        assertEquals("OK plain ERR", AnsiParser.strip(real))
        assertFalse(AnsiParser.strip(real).contains("\u001B"))
    }

    @Test fun cursorAndEraseSequencesAreStripped() {
        val esc = "\u001B"
        val raw = "hello" + esc + "[2J" + esc + "[Hworld" + esc + "[1;1H" + esc + "[0K!"
        assertEquals("helloworld!", AnsiParser.strip(raw))
        val spans = AnsiParser.parse(esc + "[1;32mOK" + esc + "[0m done")
        assertEquals("OK", spans[0].text)
        assertTrue(spans[0].bold)
        assertEquals(2, spans[0].fg)
        assertEquals(" done", spans[1].text)
    }
}
