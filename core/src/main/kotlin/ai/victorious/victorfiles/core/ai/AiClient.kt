package ai.victorious.victorfiles.core.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Minimal OpenAI-compatible chat client. Never logs the key, prompt, or response body. */
class AiClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build(),
) {
    fun complete(endpoint: AiEndpoint, apiKey: String, mode: AiMode, original: String, prompt: AiPrompt): AiResult {
        if (apiKey.isBlank()) return AiResult.Failure("No API key configured (Settings → AI assistant).")
        val url = try { endpoint.chatCompletionsUrl } catch (e: IllegalArgumentException) {
            return AiResult.Failure(e.message ?: "Invalid base URL")
        }
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .post(PromptBuilder.requestJson(endpoint, prompt).toRequestBody(JSON))
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val content = AiResponseParser.content(body)
                when {
                    content.isSuccess && resp.isSuccessful -> AiResponseParser.toResult(mode, original, content.getOrThrow())
                    content.isFailure && !resp.isSuccessful ->
                        AiResult.Failure("HTTP ${resp.code}: " + SecretRedactor.scrub(content.exceptionOrNull()?.message ?: "", apiKey))
                    !resp.isSuccessful -> AiResult.Failure("HTTP ${resp.code}")
                    else -> AiResult.Failure(SecretRedactor.scrub(content.exceptionOrNull()?.message ?: "Bad response", apiKey))
                }
            }
        } catch (e: IOException) {
            AiResult.Failure("Network error (offline?): " + SecretRedactor.scrub(e.message ?: e::class.simpleName.orEmpty(), apiKey))
        } catch (e: IllegalArgumentException) {
            AiResult.Failure(SecretRedactor.scrub(e.message ?: "Invalid request", apiKey))
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
