# Phase 3 — APK tools (NP Manager–style)

Long-press an `.apk` in either pane of the dual-pane browser:

| Action | How it works |
| --- | --- |
| **APK info** | `PackageManager.getPackageArchiveInfo(GET_PERMISSIONS)`; falls back to the built-in dependency-free AXML parser (`core/apk/AxmlParser.kt`). Shows label, package, versionName/Code, min/target SDK, installed version, signature schemes (apksig verifier), permissions. |
| **Install APK** | `PackageInstaller` session API (`apk/ApkInstaller.kt`). Requires the user to allow "Install unknown apps" for this app (the app opens that settings page if needed). Every install is confirmed by the user in the system dialog (`STATUS_PENDING_USER_ACTION` → confirmation intent). |
| **Batch install** | Select several APKs, tap the Android icon in the bottom bar. They are installed sequentially; each needs user confirmation. |
| **Uninstall** | From the APK info dialog: system uninstall confirmation (`ACTION_UNINSTALL_PACKAGE`) for the package parsed from the APK. |
| **Extract APK** | Unzips into `<name>_extracted/` next to the APK (zip-slip guarded). |
| **Rebuild APK** | Long-press a folder containing `AndroidManifest.xml` → `<name>_rebuilt.apk`. Manifest first, `resources.arsc` + `lib/**.so` stored uncompressed and aligned (4 B / 16 KiB) via an alignment extra field, old `META-INF` v1 signature files dropped. Output is unsigned. |
| **Sign APK** | Google's **apksig** (Apache-2.0) — V1, V2 and V3 (choose any). Writes `*_signed.apk`. |
| **Share / export** | Any file (single or multi-select) through `FileProvider` + the system share sheet. |

Note: the app does not decompile resources (no apktool/smali); "extract" is a raw unzip, so
the binary `AndroidManifest.xml` and `resources.arsc` are kept as-is and rebuild round-trips them.

## Signing keys

No private keys are committed to this repository.

* **Debug key (default)** — generated once per install on the device: RSA-2048 with a self-signed
  certificate (`CN=Victorious File Manager Debug`, 30 years), stored as PKCS12 at
  `filesDir/signing/debug.p12` with a random password kept in private SharedPreferences.
  Uninstalling the app destroys it, and APKs signed with it cannot be updated by a key from
  another device. Use it for testing only.
* **Your keystore** — enter a path to a `.p12`/`.pfx` (PKCS12) keystore, store password, alias
  (blank = first key entry) and key password (blank = store password). JKS is tried where the
  platform provides it (Android generally does not; convert with
  `keytool -importkeystore -srcstoretype JKS -deststoretype PKCS12`). Passwords are only held in
  memory for the signing call.

## Permissions added
`REQUEST_INSTALL_PACKAGES`, `REQUEST_DELETE_PACKAGES`. No `BIND_*` permissions, no DependencyGuard.

## Licences
apksig — Apache-2.0. Everything else (AXML parser, aligner, X.509 builder) is in-repo code.
