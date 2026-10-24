package ai.victorious.filemanager.core.cloud

import org.junit.Assert.*
import org.junit.Test

class CloudPathsTest {
    @Test fun segmentsAndParentName() {
        assertEquals(listOf("a", "b", "c"), CloudPaths.segments("/a/b/c"))
        assertEquals(emptyList<String>(), CloudPaths.segments("/"))
        assertEquals("/a/b", CloudPaths.parent("/a/b/c"))
        assertEquals("/", CloudPaths.parent("/a"))
        assertEquals("c", CloudPaths.name("/a/b/c"))
    }
    @Test fun join() {
        assertEquals("/a/b", CloudPaths.join("/a", "b"))
        assertEquals("/b", CloudPaths.join("/", "b"))
    }
    @Test fun s3KeyDirVsFile() {
        assertEquals("a/b/", CloudPaths.s3Key("/a/b", directory = true))
        assertEquals("a/b", CloudPaths.s3Key("/a/b", directory = false))
        assertEquals("", CloudPaths.s3Key("/", directory = true))
    }
    @Test fun dropboxRootIsEmpty() {
        assertEquals("", CloudPaths.dropboxPath("/"))
        assertEquals("/a/b", CloudPaths.dropboxPath("/a/b"))
    }
}
