package ai.victorious.victorfiles.core.cloud

import ai.victorious.victorfiles.core.net.RemoteBackend
import ai.victorious.victorfiles.core.net.RemoteBackendFactory
import ai.victorious.victorfiles.core.net.RemoteProtocol
import ai.victorious.victorfiles.core.net.SavedConnection
import okhttp3.OkHttpClient

/** Maps cloud/object-store connections to their [RemoteBackend]. OAuth tokens come from [tokenStore]. */
class CloudBackendFactory(
    private val tokenStore: CloudTokenStore,
    /** Per-provider public OAuth client IDs from build config; blank means "not configured". */
    private val clientIds: Map<CloudProvider, String>,
    private val http: OkHttpClient = OkHttpClient(),
    private val oauthClient: OAuthClient = OAuthClient(http),
) {
    fun handles(protocol: RemoteProtocol): Boolean = protocol.isCloud || protocol.isObjectStore

    fun open(connection: SavedConnection, secret: String?): RemoteBackend = when (connection.protocol) {
        RemoteProtocol.S3 -> {
            val accessKeyId = connection.username
            val secretKey = secret ?: throw CloudException("Missing S3 secret key for ${connection.name}")
            S3RemoteBackend(connection, SigV4.Credentials(accessKeyId, secretKey), http)
        }
        RemoteProtocol.GDRIVE -> GoogleDriveBackend(connection.id, providerToken(connection.id, CloudProvider.GOOGLE_DRIVE), http)
        RemoteProtocol.DROPBOX -> DropboxBackend(connection.id, providerToken(connection.id, CloudProvider.DROPBOX), http)
        RemoteProtocol.ONEDRIVE -> OneDriveBackend(connection.id, providerToken(connection.id, CloudProvider.ONEDRIVE), http)
        else -> throw IllegalArgumentException("Not a cloud protocol: ${connection.protocol}")
    }

    private fun providerToken(connectionId: String, provider: CloudProvider): () -> String {
        val clientId = clientIds[provider].orEmpty()
        if (clientId.isBlank()) throw CloudException("${provider.displayName} is not configured (missing client ID)")
        val tp = OAuthTokenProvider(connectionId, provider, clientId, tokenStore, oauthClient)
        return tp::validAccessToken
    }

    fun providerFor(protocol: RemoteProtocol): CloudProvider? = when (protocol) {
        RemoteProtocol.GDRIVE -> CloudProvider.GOOGLE_DRIVE
        RemoteProtocol.DROPBOX -> CloudProvider.DROPBOX
        RemoteProtocol.ONEDRIVE -> CloudProvider.ONEDRIVE
        else -> null
    }
}

/** Routes cloud protocols to [cloud] and everything else to [base]. Drop-in [RemoteBackendFactory]. */
class CombinedBackendFactory(
    private val base: RemoteBackendFactory,
    private val cloud: CloudBackendFactory,
) : RemoteBackendFactory {
    override fun open(connection: SavedConnection, secret: String?): RemoteBackend =
        if (cloud.handles(connection.protocol)) cloud.open(connection, secret)
        else base.open(connection, secret)
}
