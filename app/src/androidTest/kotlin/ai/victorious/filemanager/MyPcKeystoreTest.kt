package ai.victorious.filemanager

import ai.victorious.filemanager.core.mypc.HostKeys
import ai.victorious.filemanager.core.mypc.MyPcCrypto
import ai.victorious.filemanager.core.mypc.MyPcIds
import ai.victorious.filemanager.core.mypc.SshKeyFormat
import ai.victorious.filemanager.mypc.MyPcKeys
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** My PC's login key on a real Android Keystore: P-256, non-exportable, and signs the way sshlib needs. */
@RunWith(AndroidJUnit4::class)
class MyPcKeystoreTest {
    private val alias = MyPcIds.keyAlias(MyPcIds.newProfileId())

    @After fun tearDown() = MyPcKeys.delete(alias)

    @Test fun keystoreKeySignsForSshLoginAndCannotBeExported() {
        val pub = MyPcKeys.ensure(alias)
        assertEquals(256, pub.params.curve.field.fieldSize)
        assertArrayEquals(pub.encoded, MyPcKeys.ensure(alias).encoded) // second call reuses the same key
        val pair = MyPcKeys.keyPair(alias)
        assertNull("private key bytes must not be exportable", pair.private.encoded)
        assertTrue(MyPcCrypto.signVerifies(pair))
        val line = SshKeyFormat.publicKey(pub)
        assertTrue(line.startsWith("ecdsa-sha2-nistp256 AAAAE2VjZHNh"))
        assertTrue(HostKeys.isValidPin(HostKeys.fingerprintOfLine(line)))
        MyPcKeys.delete(alias)
        assertFalse(MyPcKeys.exists(alias))
    }
}
