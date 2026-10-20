package ai.victorious.filemanager.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailCacheTest {
    @Test fun tokenIsStableForSameIdentity() {
        val a = ThumbnailKey("/sd/a.jpg", 1000, 42L, 256)
        val b = ThumbnailKey("/sd/a.jpg", 1000, 42L, 256)
        assertEquals(a.token(), b.token())
    }

    @Test fun tokenChangesWhenFileChangesInPlace() {
        val original = ThumbnailKey("/sd/a.jpg", 1000, 42L, 256).token()
        assertNotEquals(original, ThumbnailKey("/sd/a.jpg", 2000, 42L, 256).token()) // size changed
        assertNotEquals(original, ThumbnailKey("/sd/a.jpg", 1000, 99L, 256).token()) // mtime changed
    }

    @Test fun tokenDiffersByTargetDensity() {
        assertNotEquals(
            ThumbnailKey("/sd/a.jpg", 1, 1, 128).token(),
            ThumbnailKey("/sd/a.jpg", 1, 1, 256).token(),
        )
    }

    @Test fun tokenIsFilesystemSafe() {
        val t = ThumbnailKey("/sd/My Photos/a b.jpg", 1, 1, 256).token()
        assertTrue(t.all { it.isLetterOrDigit() || it == '_' })
    }

    @Test fun budgetEvictsLeastRecentlyUsed() {
        val b = LruBudget(maxBytes = 300)
        assertTrue(b.put("a", 100).isEmpty())
        assertTrue(b.put("b", 100).isEmpty())
        assertTrue(b.put("c", 100).isEmpty())
        assertEquals(300, b.sizeBytes)
        // Touch a so b becomes LRU, then insert d -> b evicted.
        b.touch("a")
        val evicted = b.put("d", 100)
        assertEquals(listOf("b"), evicted)
        assertEquals(300, b.sizeBytes)
        assertFalse(b.contains("b"))
        assertTrue(b.contains("a"))
        assertTrue(b.contains("d"))
    }

    @Test fun budgetEvictsMultipleToFit() {
        val b = LruBudget(maxBytes = 250)
        b.put("a", 100); b.put("b", 100)
        val evicted = b.put("c", 200)
        assertEquals(listOf("a", "b"), evicted)
        assertEquals(200, b.sizeBytes)
    }

    @Test fun updatingExistingKeyAdjustsTotal() {
        val b = LruBudget(maxBytes = 1000)
        b.put("a", 100)
        b.put("a", 250)
        assertEquals(250, b.sizeBytes)
        assertEquals(1, b.count)
    }

    @Test fun removeAndClear() {
        val b = LruBudget(maxBytes = 1000)
        b.put("a", 100); b.put("b", 100)
        assertTrue(b.remove("a"))
        assertFalse(b.remove("a"))
        assertEquals(100, b.sizeBytes)
        b.clear()
        assertEquals(0, b.sizeBytes)
        assertEquals(0, b.count)
    }
}
