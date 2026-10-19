package ai.victorious.victorfiles.core.cloud

import ai.victorious.victorfiles.core.net.CredentialRedactor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * OAuth 2.0 authorization-code + PKCE token exchange and refresh over plain REST (OkHttp).
 * No client secret is used (public native client + PKCE). Tokens are never logged; errors are
 * redacted via [CredentialRedactor].
 */
class OAuthClient(
    private val http: OkHttpClient = OkHttpClient(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String? = null,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long? = null,
        @SerialName("token_type") val tokenType: String? = null,
        val scope: String? = null,
        val error: String? = null,
        @SerialName("error_description") val errorDescription: String? = null,
    )

    fun exchangeCode(tokenEndpoint: String, clientId: String, redirectUri: String, code: String, verifier: String): OAuthTokens {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", clientId)
            .add("redirect_uri", redirectUri)
            .add("code", code)
            .add("code_verifier", verifier)
            .build()
        return post(tokenEndpoint, body)
    }

    fun refresh(tokenEndpoint: String, clientId: String, refreshToken: String): OAuthTokens {
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", clientId)
            .add("refresh_token", refreshToken)
            .build()
        return post(tokenEndpoint, body)
    }

    private fun post(url: String, body: FormBody): OAuthTokens {
        val req = Request.Builder().url(url).post(body)
            .header("Accept", "application/json").build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val parsed = runCatching { json.decodeFromString(TokenResponse.serializer(), text) }.getOrNull()
            if (!resp.isSuccessful || parsed?.accessToken == null) {
                val reason = parsed?.error ?: "HTTP ${resp.code}"
                throw CloudException("Token request failed: ${CredentialRedactor.redact(reason)}")
            }
            return OAuthTokens(
                accessToken = parsed.accessToken,
                refreshToken = parsed.refreshToken,
                expiresAtEpochMs = parsed.expiresIn?.let { nowMs() + it * 1000 } ?: 0L,
                tokenType = parsed.tokenType ?: "Bearer",
                scope = parsed.scope.orEmpty(),
            )
        }
    }
}

class CloudException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
