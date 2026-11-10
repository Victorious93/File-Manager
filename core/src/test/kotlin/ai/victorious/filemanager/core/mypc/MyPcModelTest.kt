package ai.victorious.filemanager.core.mypc

import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MyPcModelTest {
    private val specBlob = Base64.getDecoder().decode(
        "AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBHzRT5f/I7BO5T2aUM4EzzwPf1aiGZ7eCGEt4LMC1ffZH3lILMfzw26hCLv0IzCWc/694fdY2EKTG1ONGBrk7Hg=",
    )

    @Test fun p256BlobRoundTripsWithSpecVector() {
        val key = SshKeyFormat.parseP256(specBlob)
        assertArrayEquals(specBlob, SshKeyFormat.blob(key))
        assertEquals(
            "ecdsa-sha2-nistp256:SHA256:d6a1zp2bJmEDKLL7Rmm7p/5ZLuzzfyCvzWYJRMalj0U",
            HostKeys.fingerprint(SshKeyFormat.blob(key)),
        )
    }

    @Test fun freshKeysEncodeTo104ByteBlobs() {
        val gen = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        repeat(20) {
            val pub = gen.generateKeyPair().public as ECPublicKey
            val blob = SshKeyFormat.blob(pub)
            assertEquals(4 + 19 + 4 + 8 + 4 + 65, blob.size)
            assertEquals(pub.w, SshKeyFormat.parseP256(blob).w)
        }
    }

    @Test fun authorizedKeysLineUsesSpecComment() {
        val pub = SshKeyFormat.parseP256(specBlob)
        val line = SshKeyFormat.authorizedKeysLine(pub, "K3VQ7M2XH9PDT4WA")
        assertTrue(line.startsWith("restrict ecdsa-sha2-nistp256 AAAAE2VjZHNh"))
        assertTrue(line.endsWith(" victorpc:K3VQ7M2XH9PDT4WA:filemanager"))
        // The spec "public key" form has no comment.
        assertEquals(2, SshKeyFormat.publicKey(pub).split(' ').size)
    }

    @Test fun idsAliasesAndComments() {
        val id = MyPcIds.newProfileId()
        assertTrue(MyPcIds.isProfileId(id))
        assertTrue(MyPcIds.isDeviceId(MyPcIds.newDeviceId()))
        assertEquals("victorpc.7Q2M4KXATB5N.filemanager", MyPcIds.keyAlias("7Q2M4KXATB5N"))
        // The spec's own example id (with a '9') is accepted; generated ids stay strict base32.
        assertEquals("victorpc.7Q2M4KX9TB5N.filemanager", MyPcIds.keyAlias("7Q2M4KX9TB5N"))
        repeat(50) { assertTrue(MyPcIds.newProfileId().all { it in 'A'..'Z' || it in '2'..'7' }) }
        assertTrue(runCatching { MyPcIds.keyAlias("7q2m4kx9tb5n") }.isFailure)
        assertTrue(runCatching { MyPcIds.keyAlias("../x") }.isFailure)
        assertEquals("mypc-7Q2M4KXATB5N", MyPcIds.connectionId("7Q2M4KXATB5N"))
        assertEquals("7Q2M4KXATB5N", MyPcIds.profileIdOf("mypc-7Q2M4KXATB5N"))
        assertNull(MyPcIds.profileIdOf("abc123"))
        assertNull(MyPcIds.profileIdOf("mypc-short"))
    }

    @Test fun profileToStringHasNoPins() {
        val p = PcProfile("7Q2M4KXATB5N", "Desk", "don", listOf("192.168.1.42"), hostKeys = listOf("ssh-ed25519:SHA256:x"))
        assertFalse(p.toString().contains("SHA256"))
    }

    @Test fun lastSeenHostGoesFirst() {
        val p = PcProfile("7Q2M4KXATB5N", "Desk", "don", listOf("192.168.1.42", "desk.tail1.ts.net"), lastSeenHost = "desk.tail1.ts.net")
        assertEquals(listOf("desk.tail1.ts.net", "192.168.1.42"), p.connectOrder())
        assertEquals(listOf("192.168.1.42"), p.copy(hosts = listOf("192.168.1.42")).connectOrder())
    }

    @Test fun connectPlanGivesLanAShortTimeout() {
        val p = PcProfile("7Q2M4KXATB5N", "Desk", "don", listOf("192.168.1.42", "desk.local", "100.101.7.12", "desk.tail1.ts.net", "example.com", "[fd7a:115c:a1e0::1]", "desk"))
        val plan = ConnectPlan.plan(p)
        assertEquals(
            listOf(ConnectPlan.Kind.LAN, ConnectPlan.Kind.LAN, ConnectPlan.Kind.TAILNET, ConnectPlan.Kind.TAILNET, ConnectPlan.Kind.OTHER, ConnectPlan.Kind.TAILNET, ConnectPlan.Kind.LAN),
            plan.map { it.kind },
        )
        assertEquals(1_500, plan[0].connectTimeoutMs)
        assertTrue(plan[2].connectTimeoutMs > 1_500)
        assertEquals(p.hosts, plan.map { it.host })
        assertEquals("fd7a:115c:a1e0::1", ConnectPlan.socketHost("[fd7a:115c:a1e0::1]"))
        assertEquals(ConnectPlan.Kind.LAN, ConnectPlan.classify("10.0.0.5"))
        assertEquals(ConnectPlan.Kind.LAN, ConnectPlan.classify("172.20.1.1"))
        assertEquals(ConnectPlan.Kind.OTHER, ConnectPlan.classify("172.40.1.1"))
    }

    @Test fun formRules() {
        assertTrue(PcProfileRules.problems("Desk", "don", listOf("192.168.1.42"), 22, "").isEmpty())
        assertEquals(listOf("192.168.1.42", "desk.local"), PcProfileRules.parseHosts("192.168.1.42, desk.local 192.168.1.42"))
        assertTrue(PcProfileRules.problems("Desk", "Don", listOf("h"), 22, "").any { it.startsWith("User") })
        assertTrue(PcProfileRules.problems("Desk", "don", emptyList(), 22, "").isNotEmpty())
        assertTrue(PcProfileRules.problems("Desk", "don", listOf("999.1.1.1"), 22, "").isNotEmpty())
        assertTrue(PcProfileRules.problems("Desk", "don", listOf("h"), 70000, "").isNotEmpty())
        assertTrue(PcProfileRules.problems("Desk", "don", listOf("h"), 22, "bad-pin").isNotEmpty())
        assertTrue(PcProfileRules.validHost("[fe80::1]"))
        assertTrue(PcProfileRules.validHost("desk.tail1.ts.net"))
        assertFalse(PcProfileRules.validHost("bad host"))
        assertFalse(PcProfileRules.validHost("-x"))
    }

    @Test fun shellQuoteKeepsPathsAsData() {
        assertEquals("'/home/don/it'\\''s \$(rm -rf).txt'", MyPcSftpBackend.shellQuote("/home/don/it's \$(rm -rf).txt"))
    }
}
