package ai.victorious.filemanager.core.cloud

/** OAuth-backed cloud providers and their public endpoints/scopes (no secrets here). */
enum class CloudProvider(
    val displayName: String,
    val authEndpoint: String,
    val tokenEndpoint: String,
    val defaultScopes: String,
    /** Extra auth-request params needed to obtain a refresh token. */
    val extraAuthParams: Map<String, String>,
) {
    GOOGLE_DRIVE(
        "Google Drive",
        "https://accounts.google.com/o/oauth2/v2/auth",
        "https://oauth2.googleapis.com/token",
        "https://www.googleapis.com/auth/drive",
        mapOf("access_type" to "offline", "prompt" to "consent"),
    ),
    DROPBOX(
        "Dropbox",
        "https://www.dropbox.com/oauth2/authorize",
        "https://api.dropboxapi.com/oauth2/token",
        "files.metadata.read files.metadata.write files.content.read files.content.write",
        mapOf("token_access_type" to "offline"),
    ),
    ONEDRIVE(
        "OneDrive",
        "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
        "https://login.microsoftonline.com/common/oauth2/v2.0/token",
        "Files.ReadWrite offline_access User.Read",
        emptyMap(),
    ),
    ;

    /** Builds the browser authorization URL for authorization-code + PKCE. */
    fun authUrl(clientId: String, redirectUri: String, codeChallenge: String, state: String): String {
        val params = linkedMapOf(
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "response_type" to "code",
            "scope" to defaultScopes,
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256",
            "state" to state,
        )
        params.putAll(extraAuthParams)
        val query = params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        return "$authEndpoint?$query"
    }

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
