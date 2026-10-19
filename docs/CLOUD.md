# Cloud & remote providers

Phase 14 adds Google Drive, Dropbox, OneDrive (Microsoft Graph) and S3-compatible object stores
(AWS S3, Cloudflare R2, Backblaze B2 via the S3 API, MinIO) as remote locations.

## How it plugs in
Each provider is a `RemoteBackend` (in `:core`, package `core.cloud`) routed through the existing
`CompositeFileIo` under `/remote/<connectionId>/...`. That means browse, upload, download, rename,
delete and mkdir - and **progress + cancel** - all flow through the same `OperationQueue` batch
engine already used for FTP/SFTP/SMB/WebDAV. No separate code path.

## Security
- OAuth uses **authorization code + PKCE** (no client secret) in the system browser; the redirect
  is captured by a custom-scheme activity.
- **Tokens and S3 keys are stored in `EncryptedSharedPreferences`** (Android Keystore-backed) and are
  **never logged**. Errors are passed through `CredentialRedactor`.
- Access tokens are refreshed automatically near expiry (60s skew); the refresh token is preserved
  when a refresh response omits a new one.
- S3 requests are signed with **AWS Signature V4** implemented from scratch (no AWS SDK), verified
  against the AWS published SigV4 test vectors.

## Where the client IDs / keys go (we ship NONE)
Client IDs are read at build time with **empty defaults** - a provider with no ID shows as
**"Not configured"** in the UI. Supply them by any of:
- Gradle properties: `-PgdriveClientId=...  -PdropboxAppKey=...  -PonedriveClientId=...`
- `~/.gradle/gradle.properties` or the repo's `local.properties` (gitignored), same keys.
- Environment variables of the same names (CI).
- **GitHub Actions**: add repo **Secrets** `GDRIVE_CLIENT_ID`, `DROPBOX_APP_KEY`, `ONEDRIVE_CLIENT_ID`
  and pass them, e.g. `./gradlew assembleDebug -PgdriveClientId=${{ secrets.GDRIVE_CLIENT_ID }}`.

**Never commit real client IDs or secrets.** S3 access key/secret are entered in-app and encrypted;
they are never part of the build.

### Redirect URI
The app registers the custom scheme **oauthRedirectScheme** (default `ai.victorious.victorfiles`),
so the redirect URI is:

    ai.victorious.victorfiles://oauth2redirect

Override the scheme at build time with `-PoauthRedirectScheme=<scheme>` (updates both the manifest
intent-filter and the runtime redirect URI). Dropbox and OneDrive accept this custom scheme directly.
**Google** (below) may require the reversed-client-ID scheme instead.

## Our debug signing certificate (for Google)
Register the **File-Manager debug certificate** fingerprint when creating the Google OAuth
Android client:

    SHA-1:   DD:FF:C0:E4:58:DE:80:8C:77:9F:8C:52:CF:E4:E6:07:DA:92:48:98
    SHA-256: 8C:1F:E3:...:85:F2   (read the full value from the keystore, see below)

This is the dedicated File-Manager debug key (used when the build runs with
`DEBUG_KEYSTORE_FILE`/`DEBUG_KEY_ALIAS` set), NOT the generic `~/.android/debug.keystore` default key.
Use the SHA-1 above in the Google Cloud console. To print the full fingerprints yourself:

    keytool -list -v -keystore <path> -alias <alias>

(keytool reads the password from the prompt, so it never appears on the command line.)
applicationId: `ai.victorious.victorfiles` (release) / `ai.victorious.victorfiles.debug` (debug).

---

## Step-by-step: create each free app / client ID

### Google Drive  (https://console.cloud.google.com)
1. Create (or pick) a project.
2. **APIs & Services -> Library -> Google Drive API -> Enable.**
3. **OAuth consent screen**: User type *External*; app name + support email; add your Google account
   under *Test users* (keeps it in testing, no verification needed).
4. **Credentials -> Create credentials -> OAuth client ID**:
   - Type **Android**: package `ai.victorious.victorfiles.debug` (debug) and/or
     `ai.victorious.victorfiles` (release); SHA-1 = the value above (use the dedicated key's SHA-1
     once configured). Scope used: `https://www.googleapis.com/auth/drive`.
   - Google's Android client derives its redirect from the **reversed client ID**
     (`com.googleusercontent.apps.<id>`). Build with
     `-PoauthRedirectScheme=com.googleusercontent.apps.<id>` so the redirect matches, OR create a
     *Web application* client and add `ai.victorious.victorfiles://oauth2redirect` as an authorized
     redirect. Put the client ID in `gdriveClientId`.
5. First sign-in prompts for consent (we request offline access for a refresh token).

### Dropbox  (https://www.dropbox.com/developers/apps)
1. **Create app** -> *Scoped access* -> *Full Dropbox* (or App folder) -> name it.
2. **Permissions** tab: enable `files.metadata.read/write`, `files.content.read/write`. Submit.
3. **Settings** tab: *OAuth 2 -> Redirect URIs* add `ai.victorious.victorfiles://oauth2redirect`.
4. Copy the **App key** -> `dropboxAppKey`. (PKCE; no app secret needed.)

### OneDrive / Microsoft  (https://portal.azure.com -> Azure AD -> App registrations)
1. **New registration**: name it; choose the account types you want.
2. **Redirect URI**: platform **Mobile and desktop applications** -> add custom URI
   `ai.victorious.victorfiles://oauth2redirect`; under *Authentication -> Advanced*, enable
   "Allow public client flows".
3. **API permissions -> Microsoft Graph -> Delegated**: add `Files.ReadWrite`, `offline_access`,
   `User.Read`; grant.
4. Copy the **Application (client) ID** -> `onedriveClientId`.

### S3 / R2 / B2 / MinIO  (no OAuth)
No app registration. In the app: **Network -> + S3 / R2 / MinIO**, then enter bucket, region,
endpoint (blank for AWS; `https://<acct>.r2.cloudflarestorage.com` for R2; your MinIO URL with
path-style on) and the access key ID + secret (stored encrypted). Create keys in:
- AWS: IAM -> Users -> Security credentials -> Access keys (scope a policy to the bucket).
- Cloudflare R2: R2 -> Manage API Tokens -> S3 credentials (region `auto`).
- Backblaze B2: App Keys -> Add a New Application Key (S3 endpoint shown on the bucket).
- MinIO: your server access/secret key; enable *path-style addressing*.

## Limits / deferred
- OneDrive & Dropbox simple upload buffers the file; **>4 MB OneDrive uploads need an upload session**
  (chunked) - deferred. Dropbox single-request upload is API-capped at 150 MB; chunked session deferred.
- Google Drive upload uses simple multipart; resumable upload deferred.
- No AppAuth / provider SDKs are bundled - the flow is plain OkHttp + our own PKCE.

## Licenses
No new third-party libraries were added. The cloud code reuses **OkHttp** (Apache-2.0),
**kotlinx.serialization** (Apache-2.0) and **androidx.security:security-crypto** (Apache-2.0).
SigV4 and the OAuth/PKCE flow are implemented from scratch. See
`app/src/main/assets/licenses/cloud-NOTICE.txt`.
