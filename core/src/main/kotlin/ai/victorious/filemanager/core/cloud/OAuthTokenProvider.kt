package ai.victorious.filemanager.core.cloud

/**
 * Supplies a valid bearer access token for a connection, refreshing and persisting transparently
 * when the current one is near expiry. Thread-safe. Tokens never logged.
 */
class OAuthTokenProvider(
    private val connectionId: String,
    private val provider: CloudProvider,
    private val clientId: String,
    private val store: CloudTokenStore,
    private val client: OAuthClient = OAuthClient(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val tokenEndpoint: String = provider.tokenEndpoint,
) {
    @Synchronized
    fun validAccessToken(): String {
        val current = store.loadTokens(connectionId)
            ?: throw CloudException("Not signed in to ${provider.displayName}")
        if (current.needsRefresh(nowMs()) && current.refreshToken != null) {
            val refreshed = current.mergedWith(
                client.refresh(tokenEndpoint, clientId, current.refreshToken),
            )
            store.saveTokens(connectionId, refreshed)
            return refreshed.accessToken
        }
        return current.accessToken
    }

    /** Forces a refresh after a 401 (if a refresh token exists); returns the new access token. */
    @Synchronized
    fun forceRefresh(): String {
        val current = store.loadTokens(connectionId)
            ?: throw CloudException("Not signed in to ${provider.displayName}")
        val rt = current.refreshToken ?: throw CloudException("Session expired; sign in again")
        val refreshed = current.mergedWith(client.refresh(tokenEndpoint, clientId, rt))
        store.saveTokens(connectionId, refreshed)
        return refreshed.accessToken
    }
}
