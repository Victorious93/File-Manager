# Signing (VictorFiles only)

Debug APKs for `ai.victorious.filemanager.debug` should install over previous
debug builds. CI and this agent box share one **File-Manager-only** debug
keystore. Release APKs have their own **File-Manager-only** release keystore
(see [Release signing](#release-signing) below). Neither is the fleet shared key
under `/workspace/fleet-signing`.

## Rules

- Never commit a `.jks` / `.keystore`, `passwords.env`, or `keystore.properties`
  (debug or release). `.gitignore` blocks these names; do not force-add them.
- Never use a shared fleet signing key for this app.
- Never print keystore passwords, the keystore base64, or full `keytool -list`
  output in logs, chats, PRs or CI.
- Release signing is in scope: see [Release signing](#release-signing).

## How Gradle picks a debug key

`app/build.gradle.kts` overrides the `debug` `signingConfig` when all of these
are set (env vars or Gradle `-P` properties):

| Name | Meaning |
|------|---------|
| `DEBUG_KEYSTORE_FILE` | Absolute path to the JKS on disk |
| `DEBUG_KEYSTORE_PASSWORD` | Keystore password |
| `DEBUG_KEY_ALIAS` | Key alias (e.g. `fmdebug`) |
| `DEBUG_KEY_PASSWORD` | Key password |

If any are missing, Gradle keeps the **Android default debug key** so forks and
local machines without secrets still build.

## GitHub Actions

Repo secrets (set with `gh secret set`, never committed):

- `DEBUG_KEYSTORE_BASE64` — base64 of the JKS bytes
- `DEBUG_KEYSTORE_PASSWORD`
- `DEBUG_KEY_ALIAS`
- `DEBUG_KEY_PASSWORD`

CI **fails closed** (job `test-and-apk`):

1. Before decoding/assembling, a step fails with a clear `::error::` naming any
   of the four secrets above that is empty. CI never falls back to the Android
   default debug key (so fork PRs without secrets will fail this job).
2. The base64 is decoded into `$RUNNER_TEMP/fm-debug.jks` and the env vars are
   exported, then `assembleDebug` runs.
3. After the build, `apksigner verify --print-certs` runs on the debug APK and
   the job fails unless the Signer #1 SHA-256 digest equals
   `8C:1F:E3:C9:E5:34:F2:71:05:C0:68:8F:45:28:D7:7E:47:11:AE:0A:79:DD:08:D1:E8:59:30:B9:E9:48:85:F2`
   (compared case-insensitively, colons ignored).

The **local-build fallback in Gradle is unchanged**: if the `DEBUG_*` values are
missing, `app/build.gradle.kts` still uses the Android default debug key, so
local machines and forks can build. Only CI enforces the VictorFiles key.
(The `emulator-smoke` job intentionally builds with the default key and does
not need the secrets.)

## Local box (agent store)

The persistent keystore lives **outside the git repo** in this bot’s store:

`/cursor/stores/store-9f9373ab-5514-4ce3-a9b8-bb9723c3d9da/debug-signing/`

That directory holds `fm-debug.jks`, `passwords.env` (mode ideally 600), and a
README with the alias and SHA-256 fingerprint. Source `passwords.env`, set
`DEBUG_KEYSTORE_FILE`, then `./gradlew :app:assembleDebug`.

## Verify fingerprint

```bash
apksigner verify --print-certs app/build/outputs/apk/debug/app-debug.apk
# or
keytool -list -v -keystore /path/to/fm-debug.jks -alias fmdebug
```

Compare the SHA-256 digest to the value recorded in the store README / CI log.

## Release signing

The release key is **separate** from the debug key and is used only for the
`release` build type (`ai.victorious.filemanager`, no `.debug` suffix).

| Item | Value |
|------|-------|
| Keystore (outside git) | `/cursor/stores/store-9f9373ab-5514-4ce3-a9b8-bb9723c3d9da/release-signing/fm-release.jks` |
| Alias | `fmrelease` |
| Certificate SHA-256 | `7C:6E:37:61:21:9D:81:9A:66:B3:17:93:C7:AB:E6:25:A5:B7:DC:10:67:16:FB:0D:6C:48:56:BF:72:26:CB:3C` |

The passwords live next to the keystore in the bot store, never in the repo.
Losing this keystore means future release builds cannot update installs signed
with it, so keep the store copy safe.

### How Gradle picks the release key

`app/build.gradle.kts` creates a `release` `signingConfig` and attaches it to the
`release` build type only when **all** of these are set (env vars or Gradle `-P`
properties) **and** the keystore file exists:

| Name | Meaning |
|------|---------|
| `RELEASE_KEYSTORE_FILE` | Absolute path to the release JKS on disk |
| `RELEASE_KEYSTORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | Key alias (`fmrelease`) |
| `RELEASE_KEY_PASSWORD` | Key password |

If any are missing, Gradle prints
`signing: RELEASE_KEYSTORE_* not fully set; release build stays unsigned` and
leaves the release build type as before (`app-release-unsigned.apk`). The Gradle
configuration never fails because of missing release values, so forks and local
machines without the key still build. When the key is used Gradle prints only
the keystore **path**, never a password.

Local example (on the agent box):

```bash
export RELEASE_KEYSTORE_FILE=/cursor/stores/store-9f9373ab-5514-4ce3-a9b8-bb9723c3d9da/release-signing/fm-release.jks
# set RELEASE_KEYSTORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD from the store, without echoing them
./gradlew :app:assembleRelease
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

### GitHub Actions (release)

Repo secrets (set with `gh secret set`, never committed):

- `RELEASE_KEYSTORE_BASE64` — base64 of the release JKS bytes
- `RELEASE_KEYSTORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

Regular pull request and push CI **does not** use or require these secrets.
The optional job **Release-signed APK (manual)** (`release-apk` in
`.github/workflows/ci.yml`) runs only from Actions → **CI** → *Run workflow*
with **release_signed_apk** ticked. It:

1. Fails with a clear `::error::` naming any empty `RELEASE_*` secret.
2. Decodes the keystore to `$RUNNER_TEMP/fm-release.jks` (deleted at the end of
   the job) and runs `:app:assembleRelease`.
3. Runs `apksigner verify --print-certs` and fails unless the Signer #1 SHA-256
   equals the fingerprint above (case-insensitive, colons ignored).
4. Uploads the APK as a 1-day `app-release` artifact (best effort). It does
   **not** publish a GitHub release.
