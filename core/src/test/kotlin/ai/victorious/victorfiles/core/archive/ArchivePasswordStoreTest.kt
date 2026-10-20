package ai.victorious.victorfiles.core.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchivePasswordStoreTest {
    private class MemStorage : BlobStorage {
        var blob: ByteArray? = null
        override fun read() = blob
        override fun write(blob: ByteArray) { this.blob = blob }
    }

    /** XOR is NOT real encryption; it only proves the plaintext never reaches storage verbatim. */
    private class XorCodec(var broken: Boolean = false) : SecretCodec {
        override fun encrypt(plain: ByteArray) = ByteArray(plain.size) { (plain[it].toInt() xor 0x5A).toByte() }
        override fun decrypt(cipher: ByteArray): ByteArray {
            if (broken) throw IllegalStateException("key lost")
            return encrypt(cipher)
        }
    }

    private fun store(s: MemStorage = MemStorage(), c: SecretCodec = XorCodec(), max: Int = 3) =
        ArchivePasswordStore(s, c, max)

    @Test fun addOrdersMruAndCaps() {
        val st = store()
        listOf("a", "b", "c", "d").forEach(st::add)
        assertEquals(listOf("d", "c", "b"), st.list())
        st.add("b")
        assertEquals(listOf("b", "d", "c"), st.list())
    }

    @Test fun persistsEncryptedAndReloads() {
        val s = MemStorage()
        store(s).add("hunter2")
        assertFalse(String(s.blob!!).contains("hunter2"))
        assertEquals(listOf("hunter2"), store(s).list())
    }

    @Test fun rejectsBlankAndNewlines() {
        val st = store()
        assertTrue(runCatching { st.add("  ") }.isFailure)
        assertTrue(runCatching { st.add("a\nb") }.isFailure)
    }

    @Test fun undecryptableBlobFailsClosedToEmpty() {
        val s = MemStorage()
        store(s).add("x")
        assertEquals(emptyList<String>(), store(s, XorCodec(broken = true)).list())
    }

    @Test fun removeAndClear() {
        val st = store()
        st.add("a"); st.add("b")
        assertTrue(st.remove("a")); assertFalse(st.remove("zz"))
        assertEquals(listOf("b"), st.list())
        st.clear()
        assertEquals(emptyList<String>(), st.list())
    }

    @Test fun tryAllFindsPasswordPromotesItAndPropagatesOtherErrors() {
        val st = store(max = 5)
        listOf("bad1", "good", "bad2").forEach(st::add) // list: bad2, good, bad1
        val hit = st.tryAll { p -> if (p == "good") 42 else throw WrongPasswordException("no") }
        assertEquals("good" to 42, hit)
        assertEquals("good", st.list().first())
        assertNull(st.tryAll { throw Archives.RarPasswordException("no") })
        val e = runCatching { st.tryAll<Int> { throw java.io.IOException("disk") } }.exceptionOrNull()
        assertNotNull(e); assertTrue(e is java.io.IOException)
    }
}
