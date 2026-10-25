# Releasing VictorFiles

Releases are **debug-signed pre-releases** published by CI. There is no release keystore
(see `SIGNING.md`: release signing is out of scope), so APKs are sideload-only and use the
File-Manager-only debug key when the `DEBUG_KEYSTORE_*` secrets are set.

1. Make sure `main` is green and `versionName` in `app/build.gradle.kts` is what you want to ship.
2. Actions → **CI** → *Run workflow* on `main`, set `release_tag` (e.g. `v0.1.0`).
3. The `publish-release` job runs only after unit tests and the debug APK build succeed. It tags the
   exact commit, attaches `VictorFiles-<tag>-debug.apk` and `SHA256SUMS`, and marks it a pre-release.

Leave `release_tag` empty to run plain CI. The tag must look like `v1.2.3` or `v1.2.3-rc.1`.

What a release does **not** claim: it is not device-tested and is not a production-signed build.
The package is `ai.victorious.victorfiles`; old `ai.victorious.filemanager` installs are a different app.
