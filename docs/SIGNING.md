# Debug signing (VictorFiles only)

Debug APKs for `ai.victorious.filemanager.debug` should install over previous
debug builds. CI and this agent box share one **File-Manager-only** debug
keystore. It is **not** the fleet shared key under `/workspace/fleet-signing`.

## Rules

- Never commit a `.jks` / `.keystore`, `passwords.env`, or `keystore.properties`.
- Never use a shared fleet signing key for this app.
- Release signing is out of scope here (debug only).

## How Gradle picks a key

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
