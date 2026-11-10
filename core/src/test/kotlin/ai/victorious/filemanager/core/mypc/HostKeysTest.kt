package ai.victorious.filemanager.core.mypc

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vectors are the throwaway example keys from VictorSuite docs/specs/VICTORPC_PAIRING.md §3.1. */
class HostKeysTest {
    private val ed25519Line = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIDcj2oxBwBVtaYh8Qvem3oTMav7DwBcRd0oGerklX3ov host"
    private val ed25519Pin = "ssh-ed25519:SHA256:gHC80Ray5GnftiotQW/JWkdaUZrT2zhJoAgvVkpna9w"
    private val ecdsaPub = "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBHzRT5f/I7BO5T2aUM4EzzwPf1aiGZ7eCGEt4LMC1ffZH3lILMfzw26hCLv0IzCWc/694fdY2EKTG1ONGBrk7Hg="
    private val ecdsaPin = "ecdsa-sha2-nistp256:SHA256:d6a1zp2bJmEDKLL7Rmm7p/5ZLuzzfyCvzWYJRMalj0U"

    private fun blobOf(line: String) = Base64.getDecoder().decode(line.split(' ')[1])

    @Test fun formatsSpecVectors() {
        assertEquals(ed25519Pin, HostKeys.fingerprintOfLine(ed25519Line))
        assertEquals(ecdsaPin, HostKeys.fingerprintOfLine(ecdsaPub))
        assertEquals(ed25519Pin, HostKeys.fingerprint("ssh-ed25519", blobOf(ed25519Line)))
        assertEquals("ssh-ed25519", HostKeys.keyTypeOf(blobOf(ed25519Line)))
    }

    @Test fun pinHasNoPaddingAndStandardAlphabet() {
        val pin = HostKeys.fingerprintOfLine(ed25519Line)
        assertFalse(pin.endsWith("="))
        assertTrue(pin.substringAfter("SHA256:").length == 43)
        assertTrue(HostKeys.isValidPin(pin))
        assertFalse(HostKeys.isValidPin("SHA256:gHC80Ray5GnftiotQW/JWkdaUZrT2zhJoAgvVkpna9w"))
        assertFalse(HostKeys.isValidPin("$ed25519Pin="))
        assertFalse(HostKeys.isValidPin("ssh-ed25519:MD5:aa"))
        // base64url is not the pin alphabet: '/' must stay '/'.
        assertFalse(HostKeys.isValidPin(ed25519Pin.replace('/', '_')))
    }

    @Test fun rejectsLineWhoseTypeDisagreesWithBlob() {
        val forged = "ssh-rsa " + ed25519Line.split(' ')[1]
        assertTrue(runCatching { HostKeys.fingerprintOfLine(forged) }.isFailure)
    }

    @Test fun parsesAndFormatsLists() {
        val list = HostKeys.parseList(" $ed25519Pin ;\n$ecdsaPin;$ed25519Pin ")
        assertEquals(listOf(ed25519Pin, ecdsaPin), list)
        assertEquals("$ed25519Pin;$ecdsaPin", HostKeys.formatList(list))
        assertEquals(emptyList<String>(), HostKeys.parseList("  "))
        assertTrue(runCatching { HostKeys.parseList("$ed25519Pin;nonsense") }.isFailure)
    }

    @Test fun matchingKeyIsAccepted() {
        val v = HostKeys.check(listOf(ecdsaPin, ed25519Pin), "ssh-ed25519", blobOf(ed25519Line))
        assertEquals(HostKeyVerdict.Match(ed25519Pin), v)
    }

    @Test fun changedKeyIsAHardMismatch() {
        val v = HostKeys.check(listOf(ecdsaPin), "ssh-ed25519", blobOf(ed25519Line))
        assertTrue(v is HostKeyVerdict.Mismatch)
        v as HostKeyVerdict.Mismatch
        assertEquals(ed25519Pin, v.presented)
        assertEquals(listOf(ecdsaPin), v.expected)
    }

    @Test fun pinComparisonIsExactString() {
        // A pin differing only in case is a different key.
        val lower = "ssh-ed25519:SHA256:" + ed25519Pin.substringAfter("SHA256:").lowercase()
        assertTrue(HostKeys.check(listOf(lower), "ssh-ed25519", blobOf(ed25519Line)) is HostKeyVerdict.Mismatch)
    }

    @Test fun unpinnedOnlyWhenNothingIsPinned() {
        assertEquals(HostKeyVerdict.Unpinned(ed25519Pin), HostKeys.check(emptyList(), "ssh-ed25519", blobOf(ed25519Line)))
    }

    @Test fun algorithmMustBelongToTheKey() {
        // A server claiming ECDSA while sending an Ed25519 blob is refused even if the blob is pinned.
        assertTrue(HostKeys.check(listOf(ed25519Pin), "ecdsa-sha2-nistp256", blobOf(ed25519Line)) is HostKeyVerdict.Mismatch)
        assertTrue(HostKeys.algorithmMatchesKey("rsa-sha2-512", "ssh-rsa"))
        assertTrue(HostKeys.algorithmMatchesKey("rsa-sha2-256", "ssh-rsa"))
        assertFalse(HostKeys.algorithmMatchesKey("rsa-sha2-512", "ssh-ed25519"))
    }
}
