package ai.victorious.filemanager.core.cloud

import ai.victorious.filemanager.core.net.CredentialRedactor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OAuthFlowTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun url() = server.url("/token").toString()

    @Test fun exchangeCodeParsesTokens() {
        server.enqueue(
            MockResponse().setBody(
                """{"access_token":"AT","refresh_token":"RT","expires_in":3600,"token_type":"Bearer","scope":"s"}""",
            ),
        )
        val client = OAuthClient(nowMs = { 1_000_000 })
        val t = client.exchangeCode(url(), "cid", "fm://cb", "code", "verifier")
        assertEquals("AT", t.accessToken)
        assertEquals("RT", t.refreshToken)
        assertEquals(1_000_000 + 3_600_000, t.expiresAtEpochMs)
        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent.contains("grant_type=authorization_code"))
        assertTrue(sent.contains("code_verifier=verifier"))
    }

    @Test fun errorResponseIsRedactedAndThrows() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
        val client = OAuthClient()
        val ex = assertThrows(CloudException::class.java) {
            client.exchangeCode(url(), "cid", "fm://cb", "bad", "v")
        }
        assertTrue(ex.message!!.contains("invalid_grant"))
    }

    @Test fun tokenProviderRefreshesWhenExpiredAndPersists() {
        server.enqueue(MockResponse().setBody("""{"access_token":"NEW","expires_in":3600}"""))
        val store = InMemoryTokenStore()
        store.saveTokens("c1", OAuthTokens("OLD", "RT", expiresAtEpochMs = 500))
        // Point the provider's token endpoint at the mock by using a custom-provider shim:
        val client = OAuthClient(nowMs = { 10_000 })
        val tp = OAuthTokenProvider("c1", CloudProvider.DROPBOX, "cid", store, client, nowMs = { 10_000 }, tokenEndpoint = url())
        val token = tp.validAccessToken()
        assertEquals("NEW", token)
        // Refresh token preserved across refresh that omitted it.
        assertEquals("RT", store.loadTokens("c1")!!.refreshToken)
    }

    @Test fun tokenProviderReturnsCachedWhenFresh() {
        val store = InMemoryTokenStore()
        store.saveTokens("c1", OAuthTokens("FRESH", "RT", expiresAtEpochMs = Long.MAX_VALUE))
        val tp = OAuthTokenProvider("c1", CloudProvider.DROPBOX, "cid", store, OAuthClient(), nowMs = { 0 })
        assertEquals("FRESH", tp.validAccessToken())
    }

    @Test fun redactionHidesTokens() {
        val line = """{"access_token":"super-secret-value","token":"abc123"}"""
        val red = CredentialRedactor.redact(line)
        assertFalse(red.contains("super-secret-value"))
        assertFalse(red.contains("abc123"))
    }
}
