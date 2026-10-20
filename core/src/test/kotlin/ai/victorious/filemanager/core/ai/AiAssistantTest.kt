package ai.victorious.filemanager.core.ai

import ai.victorious.filemanager.core.diff.DiffOp
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiAssistantTest {
    @Test
    fun redactorMasksCommonSecrets() {
        val code = """
            val apiKey = "sk-abcdefghijklmnopqrstuvwx"
            password: hunter22
            Authorization: Bearer abcdefghijklmnop123
            -----BEGIN RSA PRIVATE KEY-----
            MIIEow
            -----END RSA PRIVATE KEY-----
            val ok = "hello"
        """.trimIndent()
        val (out, n) = SecretRedactor.redact(code)
        assertFalse(out.contains("sk-abcdefghijklmnopqrstuvwx"))
        assertFalse(out.contains("hunter22"))
        assertFalse(out.contains("abcdefghijklmnop123"))
        assertFalse(out.contains("MIIEow"))
        assertTrue(out.contains("val ok = \"hello\""))
        assertTrue(n >= 4)
    }

    @Test
    fun scrubHidesConfiguredKey() {
        assertEquals("bad «redacted» here", SecretRedactor.scrub("bad my-secret-key here", "my-secret-key"))
    }

    @Test
    fun promptContainsRedactedFencedCodeAndTruncates() {
        val p = PromptBuilder.build(AiMode.SUGGEST_EDIT, "Main.kt", "Kotlin", "token = abcd1234\nfun a() = 1")
        assertEquals(2, p.messages.size)
        assertEquals("system", p.messages[0].role)
        val user = p.messages[1].content
        assertTrue(user.contains("```kotlin"))
        assertFalse(user.contains("abcd1234"))
        assertEquals(1, p.redactions)
        val big = PromptBuilder.build(AiMode.EXPLAIN, "x", "Plain", "a".repeat(PromptBuilder.MAX_CODE_CHARS + 10))
        assertTrue(big.truncated)
    }

    @Test
    fun endpointUrlNormalization() {
        assertEquals("https://h/v1/chat/completions", AiEndpoint("https://h/v1/", "m").chatCompletionsUrl)
        assertEquals("https://h/v1/chat/completions", AiEndpoint("https://h/v1/chat/completions", "m").chatCompletionsUrl)
        assertEquals("h", AiEndpoint("https://h/v1", "m").host)
    }

    @Test
    fun parsesContentErrorsAndEdits() {
        val ok = """{"choices":[{"message":{"role":"assistant","content":"Here:\n```kotlin\nfun a() = 2\n```\n- changed 1 to 2"}}]}"""
        val content = AiResponseParser.content(ok).getOrThrow()
        val r = AiResponseParser.toResult(AiMode.SUGGEST_EDIT, "fun a() = 1", content) as AiResult.Edit
        assertEquals("fun a() = 2", r.replacement)
        assertTrue(r.notes.contains("changed 1 to 2"))
        assertTrue(r.diff.any { it.op == DiffOp.DELETE } && r.diff.any { it.op == DiffOp.INSERT })

        val err = AiResponseParser.content("""{"error":{"message":"Invalid key"}}""")
        assertEquals("Invalid key", err.exceptionOrNull()?.message)
        val noBlock = AiResponseParser.toResult(AiMode.SUGGEST_EDIT, "x", "just words")
        assertTrue(noBlock is AiResult.Failure)
    }

    @Test
    fun applyEditReplacesSelection() {
        assertEquals("aXc", AiResponseParser.applyEdit("abc", 1..1, "X"))
        assertEquals("new", AiResponseParser.applyEdit("abc", null, "new"))
    }

    @Test
    fun clientAgainstMockServer() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"It adds."}}]}"""))
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key k-123456"}}"""))
            server.start()
            val ep = AiEndpoint(server.url("/v1").toString(), "test-model")
            val prompt = PromptBuilder.build(AiMode.EXPLAIN, "a.kt", "Kotlin", "fun add(a:Int,b:Int)=a+b")
            val r = AiClient().complete(ep, "k-123456", AiMode.EXPLAIN, "", prompt)
            assertEquals(AiResult.Explanation("It adds."), r)
            val req = server.takeRequest()
            assertEquals("/v1/chat/completions", req.path)
            assertEquals("Bearer k-123456", req.getHeader("Authorization"))
            assertTrue(req.body.readUtf8().contains("\"model\":\"test-model\""))

            val bad = AiClient().complete(ep, "k-123456", AiMode.EXPLAIN, "", prompt) as AiResult.Failure
            assertTrue(bad.message.startsWith("HTTP 401"))
            assertFalse(bad.message.contains("k-123456"))
        }
    }

    @Test
    fun clientOfflineAndMissingKey() {
        val ep = AiEndpoint("http://127.0.0.1:1/v1", "m")
        val prompt = PromptBuilder.build(AiMode.EXPLAIN, "a", "Plain", "x")
        assertTrue((AiClient().complete(ep, "", AiMode.EXPLAIN, "", prompt) as AiResult.Failure).message.contains("No API key"))
        assertTrue((AiClient().complete(ep, "key", AiMode.EXPLAIN, "", prompt) as AiResult.Failure).message.startsWith("Network error"))
    }
}
