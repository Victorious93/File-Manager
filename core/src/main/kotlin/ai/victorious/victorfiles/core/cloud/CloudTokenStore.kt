package ai.victorious.victorfiles.core.cloud

/** Persistence for per-connection OAuth tokens. App impl is backed by EncryptedSharedPreferences. */
interface CloudTokenStore {
    fun loadTokens(connectionId: String): OAuthTokens?
    fun saveTokens(connectionId: String, tokens: OAuthTokens)
    fun clear(connectionId: String)
}

/** In-memory store for tests. */
class InMemoryTokenStore : CloudTokenStore {
    private val map = HashMap<String, OAuthTokens>()
    override fun loadTokens(connectionId: String) = map[connectionId]
    override fun saveTokens(connectionId: String, tokens: OAuthTokens) { map[connectionId] = tokens }
    override fun clear(connectionId: String) { map.remove(connectionId) }
}
