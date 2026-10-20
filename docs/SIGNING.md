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

The CI workflow decodes the base64 into `$RUNNER_TEMP/fm-debug.jks`, exports the
env vars, then runs `assembleDebug`. If secrets are absent (e.g. fork PRs), it
skips decoding and logs that the default Android debug key will be used.

After a successful custom-key build, CI runs `apksigner verify --print-certs` on
the debug APK so the SHA-256 digest is visible in the job log (no passwords).

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
