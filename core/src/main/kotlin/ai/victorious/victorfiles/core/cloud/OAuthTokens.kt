package ai.victorious.victorfiles.core.cloud

import kotlinx.serialization.Serializable

/** OAuth token set persisted (encrypted) per connection. Never logged. */
@Serializable
data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    /** Absolute expiry; 0 means unknown (treated as non-expiring until a 401 forces refresh). */
    val expiresAtEpochMs: Long = 0L,
    val tokenType: String = "Bearer",
    val scope: String = "",
) {
    fun needsRefresh(nowMs: Long, skewMs: Long = 60_000L): Boolean =
        expiresAtEpochMs != 0L && nowMs >= expiresAtEpochMs - skewMs

    /** Merge a refresh response, keeping the old refresh token when the server omits a new one. */
    fun mergedWith(refreshed: OAuthTokens): OAuthTokens =
        refreshed.copy(refreshToken = refreshed.refreshToken ?: this.refreshToken)
}
