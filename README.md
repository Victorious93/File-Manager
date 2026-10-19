<p align="center">
  <img src="docs/assets/logo.png" alt="VictorFiles logo" width="240">
</p>

# VictorFiles

A fast, standalone Android file manager (Phases 1–7) with an **optional** DroidCommand AI fleet stub.

This app is an MT Manager–class product: **it works fully on its own**. It is not a dependent companion APK. DroidCommand AI is never required, and removing DCA does not disable any file-manager feature.

## Features (Phases 1–7)

See also `docs/BATCH_RENAME.md`, `docs/BROWSING.md`, `docs/APK_TOOLS.md`, `docs/POWER_TOOLS.md`, `docs/NETWORK.md`, `docs/AI_ASSISTANT.md`, `docs/PLUGINS.md`, and `docs/SECURE_FOLDER.md`.

### Phase 1 includes


- Kotlin + Jetpack Compose + Material 3
- Dual panes (side-by-side on wide screens, stacked on phones) with per-pane tabs
- File operations: **copy, move, delete, rename**
- Sequential **operation queue** with **undo**
- Search (local name match)
- Bookmarks
- Trash (soft-delete into the app trash store; restore / empty / permanent delete)
- Optional RootForge-style **Messenger** fleet stub (not LIVE control)

`applicationId`: `ai.victorious.victorfiles`  
Version: `0.1.0`  
`minSdk`: **26** · `targetSdk` / `compileSdk`: **35**

Debug builds use `ai.victorious.victorfiles.debug`.

### Later phases (merged)

- **Phase 2+** — text/hex editors, archives, image/media viewers  
- **Phase 3–4** — APK install/sign/rebuild, DEX/smali browse, resources  
- **Phase 5** — syntax highlighting, find/replace, diff, duplicates, storage analyzer, encrypted ZIP  
- **Phase 6** — smali assemble, ARSC strings, APKS/XAPK, v4/verify, extract installed apps  
- **Phase 7** — Root (libsu), Shizuku, built-in terminal, app manager backup/restore (`docs/POWER_TOOLS.md`)
- **Phase 8** — Network connections (FTP/FTPS/SFTP/SMB/WebDAV), Wi-Fi transfer (`docs/NETWORK.md`)
- **Phase 9** — AI assistant in the code editor with your own endpoint (`docs/AI_ASSISTANT.md`), declarative plugins + script runner (`docs/PLUGINS.md`), biometric-locked encrypted secure folder (`docs/SECURE_FOLDER.md`)

## Build

Requirements: JDK 17+, Android SDK with platform 35.

```bash
./gradlew :core:test :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`

On device, grant **all-files access** (Android 11+) for snappy local storage browsing. Without it, the app still runs against its own directories. `requestLegacyExternalStorage` is set for Android 10.

## Architecture

| Module | Role |
| --- | --- |
| `:core` | Pure JVM logic — path helpers, `FileIo`, operation queue + undo, trash, bookmarks, search, fleet JSON envelope |
| `:app` | Android UI (Compose) + optional Messenger bound service |

Unit tests live in `:core` so queue/undo/trash/fleet JSON are verified without an emulator.

## Fleet notes (optional, pending owner choice)

VictorFiles is **not** registered as a hub `KnownCompanions` / `BOUND_COMPANION` member in this repo, and this PR does **not** modify [droidcommand-AI](https://github.com/Victorious93/droidcommand-AI).

Until Don confirms the fleet class, Phase 1 ships a **soft Messenger stub** modeled on RootForge OS (`EXTERNAL_NODE` shape), not an AIDL companion:

| | This app | Dependent companions (HackerAI / Pentest / EDL) |
| --- | --- | --- |
| Works without DCA | **Yes** | No (`DependencyGuard`) |
| Shared signing key required | **No** | Yes |
| Transport | Bound **Messenger** + JSON envelope | AIDL `ICompanionService` / domain AIDL |
| Action | `ai.victorious.victorfiles.action.BRIDGE` | `ai.droidcommand.companion.ICompanionService` |
| LIVE control | **No** — stubs only | Companion-specific |

### Messenger stub

- Exported bound service, **no** `BIND_FILEMANAGER` signature permission (that permission is **not** claimed to exist on the hub today).
- Envelope `protocol_major` 1, same *shape* as RootForge (`request_id`, `operation`, `ok` / `error.category`).
- `Message.what = 1` + bundle key `request`; reply `what = 2` + `response`.
- Operations:
  - `filemanager.capabilities.get` — lists stub ops, `live: false`, `dca_required: false`, `fleet_class: pending`
  - `filemanager.status` — id `filemanager`, version `0.1.0`
  - `filemanager.list` — honest empty stub; does **not** expose device files
- Unknown ops return `unknown_operation` and will **not** pretend LIVE control.
- There is **no DependencyGuard**. AIDL/JSON methods are not fail-closed when `ai.droidcommand.app` is missing.

**Hub recognition requires a later PR on droidcommand-AI.** Possible future classes (owner decides):

1. **EXTERNAL_NODE** (RootForge-like) — keep this Messenger bridge, pairing in this app, never DependencyGuard.
2. **BOUND_COMPANION** — would need a hub-side signature permission, `KnownCompanions` entry, and a companion that *stops* without DCA. That would be a product change, not the default.

Do not add VictorFiles to `TERMUX_IPC` or `HOST_PROCESS_TOOL`.

## CI

GitHub Actions on every pull request:

1. Run unit tests (`:core:test`, `:app:testDebugUnitTest`)
2. Build a debug APK
3. Upload `app-debug` as a workflow artifact

## Not in Phase 1

- Network/cloud providers, archives as a first-class FS, root/Magisk browsing
- LIVE fleet file control, hub catalog wiring, shared debug keystore with DCA
- Inventing `ai.droidcommand.permission.BIND_FILEMANAGER` as if the hub already declares it

## Phase 2: viewers, editors, archives

- **Text editor** (UTF-8, ≤4 MB): edit, save (atomic temp-file write), undo/redo, discard-changes prompt.
- **Hex editor** (≤16 MB): 16-byte rows with offset/hex/ASCII, tap a row to overwrite bytes, undo, save writes only changed bytes.
- **Archives**: ZIP/JAR/APK list + extract (java.util.zip); **7z** and **TAR/TAR.GZ** list + extract via Apache Commons Compress (Apache-2.0) + XZ for Java (public domain), pure Java. Extraction goes to the other pane's folder and blocks zip-slip paths.
- **RAR** (added in Phase 10): list + extract RAR4/RAR5/solid/password archives via junrar, extraction only, under the UnRAR licence. See [docs/ARCHIVES.md](docs/ARCHIVES.md) for the compliance notes.
- **Create ZIP** from selected files/folders (bottom-bar Zip button) into the current folder (`name.zip`, `name (2).zip`, …).
- **Image preview** (PNG/JPEG/GIF/WebP/BMP/HEIF, downsampled) and **media info** for audio/video via `MediaMetadataRetriever` (no player).
- **Integration**: tap a file to open it in the default viewer for its type (sniffed when the extension is unknown); long-press a file for *Select* / *Open in text editor / hex editor / Preview image / Media info / Open as archive*.

## Phase 3: APK tools

Long-press an `.apk`: APK info, Install (PackageInstaller session, user-confirmed), Extract APK,
Sign APK (V1/V2/V3 via apksig, Apache-2.0), Uninstall (from APK info), Share / export.
Long-press an extracted folder: Rebuild APK (aligned, unsigned). Multi-select APKs + the Android
bottom-bar icon installs them in sequence. Signing uses your keystore or a per-device debug key
generated in-app — no keys in the repo. Details: [docs/APK_TOOLS.md](docs/APK_TOOLS.md).
Debug signing setup: [docs/SIGNING.md](docs/SIGNING.md).
- Testing: unit tests, emulator Compose smoke tests + monkey/logcat scan, and lint in CI — see [docs/TESTING.md](docs/TESTING.md)

## Phase 10: gap fill

Indexed search, trash on the same volume, themes (light/dark/AMOLED/dynamic/accent),
tablet/foldable layouts (NavigationRail plus side-by-side panes), Compare two APKs, Smali
tools (search, decryption hints, mapping.txt), RAR extraction, and big-folder listing speed.
See [docs/PHASE10.md](docs/PHASE10.md).

## Phase 11: APK deep edit + PTY terminal

Binary AndroidManifest editor, ARSC string edits inside a sealed APK, one-tap APK mod
(rebuild → sign → verify → install), and a real PTY terminal (Termux/jackpal Apache-2.0
modules, with pipe fallback). See [docs/APK_DEEP_EDIT.md](docs/APK_DEEP_EDIT.md).
