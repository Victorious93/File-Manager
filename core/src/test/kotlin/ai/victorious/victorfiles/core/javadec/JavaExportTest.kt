package ai.victorious.victorfiles.core.javadec

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class JavaExportTest {
    @Test fun mapsPackageToPath() {
        assertEquals("com/x/Foo.java", JavaExport.relativePath("com.x.Foo"))
    }
    @Test fun innerClassMapsToTopLevelFile() {
        assertEquals("com/x/Foo.java", JavaExport.relativePath("com.x.Foo\$Bar"))
    }
    @Test fun defaultPackage() {
        assertEquals("Foo.java", JavaExport.relativePath("Foo"))
    }
    @Test fun fileStaysInsideOutDir() {
        val dir = Files.createTempDirectory("ex").toFile()
        val f = JavaExport.fileIn(dir, "com.x.Foo")
        assertTrue(f.canonicalPath.startsWith(dir.canonicalPath + File.separator))
    }
    @Test(expected = UnsafeExportPathException::class)
    fun rejectsTraversal() {
        val dir = Files.createTempDirectory("ex").toFile()
        JavaExport.fileIn(dir, "..a.b")
    }
    @Test(expected = UnsafeExportPathException::class)
    fun rejectsSlashInjection() {
        JavaExport.relativePath("../../etc/passwd")
    }
}
