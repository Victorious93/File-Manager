# Releasing VictorFiles

Published releases are still **debug-signed pre-releases**: APKs are sideload-only and use the
File-Manager-only debug key when the `DEBUG_KEYSTORE_*` secrets are set.

There **is** now a File-Manager-only release keystore (alias `fmrelease`, see
[`SIGNING.md`](SIGNING.md#release-signing)). `:app:assembleRelease` signs with it whenever the
`RELEASE_*` values are set, and stays unsigned otherwise. Release-signed APKs are **not**
published as GitHub releases yet.

1. Make sure `main` is green and `versionName` in `app/build.gradle.kts` is what you want to ship.
2. Actions → **CI** → *Run workflow* on `main`, set `release_tag` (e.g. `v0.1.0`).
3. The `publish-release` job runs only after unit tests and the debug APK build succeed. It tags the
   exact commit, attaches `VictorFiles-<tag>-debug.apk` and `SHA256SUMS`, and marks it a pre-release.

Leave `release_tag` empty to run plain CI.

To get a release-signed APK without publishing anything, run the same workflow with
**release_signed_apk** ticked. The manual `release-apk` job builds `assembleRelease` with the
`RELEASE_*` secrets, checks the certificate SHA-256
(`7C:6E:37:61:21:9D:81:9A:66:B3:17:93:C7:AB:E6:25:A5:B7:DC:10:67:16:FB:0D:6C:48:56:BF:72:26:CB:3C`)
and keeps the APK as a 1-day `app-release` artifact. A release-signed APK has package
`ai.victorious.filemanager` and a different key from the debug build, so it installs next to the
debug app rather than over it. The tag must look like `v1.2.3` or `v1.2.3-rc.1`.

What a release does **not** claim: it is not device-tested, and published pre-releases are not
release-signed builds.
The package is `ai.victorious.filemanager` (debug builds: `ai.victorious.filemanager.debug`).
