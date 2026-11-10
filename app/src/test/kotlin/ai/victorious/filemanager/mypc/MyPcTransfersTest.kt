package ai.victorious.filemanager.mypc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MyPcTransfersTest {
    private val pc = "/remote/mypc-7Q2M4KXATB5N"
    private val pc2 = "/remote/mypc-ABCDEFGHJKLM"
    private val phone = "/storage/emulated/0/Download"

    @Test fun phoneToPcAndBackAreBackgroundTransfers() {
        assertTrue(MyPcTransfers.isPhonePcTransfer(listOf("$phone/a.jpg", "$phone/b"), "$pc/home/don"))
        assertTrue(MyPcTransfers.isPhonePcTransfer(listOf("$pc/home/don/a.jpg"), phone))
    }

    @Test fun everythingElseStaysANormalOperation() {
        assertFalse(MyPcTransfers.isPhonePcTransfer(emptyList(), phone))
        assertFalse(MyPcTransfers.isPhonePcTransfer(listOf("$phone/a"), "$phone/b"))
        // Same PC to same PC: SFTP rename/copy, no phone involved.
        assertFalse(MyPcTransfers.isPhonePcTransfer(listOf("$pc/home/don/a"), "$pc/tmp"))
        // Two PCs, or a PC and another network place.
        assertFalse(MyPcTransfers.isPhonePcTransfer(listOf("$pc/a", "$pc2/b"), phone))
        assertFalse(MyPcTransfers.isPhonePcTransfer(listOf("$pc/a"), "/remote/abc123def456/x"))
        assertFalse(MyPcTransfers.isPhonePcTransfer(listOf("/remote/abc123def456/x"), "$pc/x"))
        assertFalse(MyPcTransfers.isPhonePcTransfer(listOf("$phone/a", "$pc/b"), phone))
    }
}
