package ai.victorious.filemanager.core.cloud

import org.junit.Assert.*
import org.junit.Test

class PkceTest {
    @Test fun rfc7636AppendixBVector() {
        // RFC 7636 Appendix B worked example.
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challengeOf(verifier))
    }
    @Test fun generatedPairIsConsistentAndUrlSafe() {
        val p = Pkce.generate()
        assertEquals(p.challenge, Pkce.challengeOf(p.verifier))
        assertTrue(p.verifier.length in 43..128)
        assertFalse(p.verifier.any { it == '+' || it == '/' || it == '=' })
        assertFalse(p.challenge.any { it == '+' || it == '/' || it == '=' })
    }
}

class OAuthTokensTest {
    @Test fun expiryWithSkew() {
        val t = OAuthTokens("a", "r", expiresAtEpochMs = 100_000)
        assertFalse(t.needsRefresh(0, skewMs = 1000))
        assertTrue(t.needsRefresh(99_500, skewMs = 1000))
        assertTrue(t.needsRefresh(100_001, skewMs = 1000))
    }
    @Test fun zeroExpiryNeverRefreshesProactively() {
        assertFalse(OAuthTokens("a", "r", 0).needsRefresh(Long.MAX_VALUE))
    }
    @Test fun mergeKeepsOldRefreshTokenWhenOmitted() {
        val old = OAuthTokens("a", "oldR", 1)
        val refreshed = OAuthTokens("b", null, 2)
        assertEquals("oldR", old.mergedWith(refreshed).refreshToken)
        assertEquals("b", old.mergedWith(refreshed).accessToken)
    }
}

class CloudProviderTest {
    @Test fun authUrlHasPkceAndScopes() {
        val url = CloudProvider.GOOGLE_DRIVE.authUrl("cid", "fm://cb", "chal", "state123")
        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"))
        assertTrue(url.contains("code_challenge=chal"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("access_type=offline"))
        assertTrue(url.contains("client_id=cid"))
        assertTrue(url.contains("redirect_uri=fm%3A%2F%2Fcb"))
    }
    @Test fun dropboxRequestsOfflineToken() {
        assertTrue(CloudProvider.DROPBOX.authUrl("c", "r", "x", "s").contains("token_access_type=offline"))
    }
}
