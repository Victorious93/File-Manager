# Phase 12: MT Manager–class gap fill (core logic)

Clean-room implementations written from public feature descriptions only. No code or assets
from any third-party APK were used.

## Status
| Feature | `:core` logic | Unit tests | UI wired | Verified on device |
|---|---|---|---|---|
| Hex search | `hex/HexSearch.kt` | yes | Find bar in the hex editor | **no** |
| Saved archive passwords | `archive/ArchivePasswordStore.kt`, `EncryptedZip.verifyPassword` | yes | Archive extract + Settings | **no** |
| File split / join | `ops/FileSplit.kt` | yes | Long-press menu + size dialog | **no** |

Verified: `./gradlew clean :core:test :app:testDebugUnitTest :app:assembleDebug` succeeds (core 257 tests,
app 4 tests, 0 failures); the debug APK builds. **Not verified:** any UI behavior and the Android Keystore
codec, which need a device or emulator (none was available). `KeystoreSecretCodec` has no unit test because
AndroidKeyStore does not exist on the JVM.

## Behavior
- **Hex search**: hex patterns with `??` wildcards or text; sees unsaved edits; forward/backward
  with optional wrap; `findAll` capped (default 10,000). A start past the end (forward) or
  before the start (backward) wraps instead of re-finding the edge match.
- **Password store**: list is encrypted through a `SecretCodec` before reaching `BlobStorage`.
  The app must supply a Keystore-backed codec; none exists yet. Undecryptable data loads as empty.
  `tryAll` retries saved passwords only on wrong-password exceptions.
- **Split/join**: `name.001…name.999` plain slices (joinable with `cat`). Never overwrites;
  join refuses gaps instead of producing a truncated file.

## UI behavior
- **Hex editor**: search icon opens a find bar (Hex or Text mode, previous/next, wraps). The current hit's row
  is highlighted and scrolled into view. Row editing is disabled while a search runs.
- **Archive extract**: for encrypted ZIPs, saved passwords are checked with `verifyPassword` (reads the smallest
  entry, writes nothing) and extraction starts only with a confirmed match; otherwise the password dialog
  appears with a "Remember" checkbox. A password is saved only after it successfully extracted. **RAR is never
  auto-tried** (no write-free verification exists), but a working RAR password can still be remembered.
- **Settings**: "Saved archive passwords" shows the count and Clear all.
- **Storage**: `noBackupFilesDir/archive-passwords.bin`, AES-256-GCM, non-exportable Keystore key; excluded from
  backup and device transfer by location.
- **Split/Join**: long-press a file: "Split file…" (size like `100`, `50MB`, `1.5GB`; bare numbers are MB) or, on a
  `.NNN` part, "Join parts". Operates with plain `java.io.File` like Zip does, so it does not use the root/Shizuku
  file layer.

## Next
Device/emulator smoke test of the three flows; consider routing split/join through the privileged `FileIo`.
