package ai.victorious.filemanager.cloud

import ai.victorious.filemanager.core.cloud.CloudProvider
import ai.victorious.filemanager.core.cloud.OAuthClient
import ai.victorious.filemanager.net.ConnectionRepository
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import ai.victorious.filemanager.core.cloud.Pkce

/**
 * Lightweight OAuth 2.0 authorization-code + PKCE flow using the system browser (no extra deps) and
 * a custom-scheme redirect captured by [CloudAuthRedirectActivity]. Token exchange and storage run
 * through the core OAuth client; tokens are never logged.
 */
object CloudOAuth {
    const val REDIRECT_SCHEME = "ai.victorious.filemanager"
    const val REDIRECT_URI = "$REDIRECT_SCHEME:/oauth2redirect"

    private data class Pending(val connectionId: String, val provider: CloudProvider, val verifier: String)

    private val pending = ConcurrentHashMap<String, Pending>()

    /** Builds the auth URL and opens it in the browser. Returns false if the provider is unconfigured. */
    fun start(context: Context, repo: ConnectionRepository, provider: CloudProvider, connectionId: String): Boolean {
        val clientId = repo.clientId(provider)
        if (clientId.isBlank()) return false
        val pkce = Pkce.generate(SecureRandom())
        val state = randomState()
        pending[state] = Pending(connectionId, provider, pkce.verifier)
        val url = provider.authUrl(clientId, REDIRECT_URI, pkce.challenge, state)
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }

    /**
     * Completes the flow from the redirect [uri]. Must run OFF the main thread (does network I/O).
     * Returns a user-facing result message. Never includes token values.
     */
    fun complete(uri: Uri, repo: ConnectionRepository): String {
        val error = uri.getQueryParameter("error")
        val state = uri.getQueryParameter("state")
        if (error != null) return "Sign-in failed: $error"
        val code = uri.getQueryParameter("code") ?: return "Sign-in failed: no code returned"
        val p = state?.let { pending.remove(it) } ?: return "Sign-in failed: unknown or expired request"
        return try {
            val tokens = OAuthClient().exchangeCode(
                tokenEndpoint = p.provider.tokenEndpoint,
                clientId = repo.clientId(p.provider),
                redirectUri = REDIRECT_URI,
                code = code,
                verifier = p.verifier,
            )
            repo.tokenStore.saveTokens(p.connectionId, tokens)
            "Signed in to ${p.provider.displayName}"
        } catch (t: Throwable) {
            "Sign-in failed: ${t.message}"
        }
    }

    private fun randomState(): String {
        val b = ByteArray(16); SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }
}
