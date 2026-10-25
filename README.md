<p align="center">
  <img src="docs/assets/logo.png" alt="VictorFiles logo" width="168">
</p>

<h1 align="center">VictorFiles</h1>

<p align="center">
  <b>A fast, standalone Android file manager with power tools.</b><br>
  Editors, archives, APK tools, network and cloud drives, a terminal, root and Shizuku,<br>
  in one app that works fully on its own.
</p>

<p align="center">
  <a href="https://github.com/Victorious93/File-Manager/actions/workflows/ci.yml"><img alt="CI" src="https://github.com/Victorious93/File-Manager/actions/workflows/ci.yml/badge.svg"></a>
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Kotlin 2.1" src="https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin&logoColor=white">
  <img alt="Jetpack Compose and Material 3" src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white">
</p>

<p align="center">
  <a href="#features">Features</a> ·
  <a href="#install">Install</a> ·
  <a href="#build-from-source">Build</a> ·
  <a href="#architecture">Architecture</a> ·
  <a href="#documentation">Docs</a> ·
  <a href="#victorious-fleet">Fleet</a>
</p>

---

## Why VictorFiles

- **Works on its own.** No companion app is needed. DroidCommand AI is optional, and removing it disables nothing.
- **One pane by default.** Two panes and tabs are a setting away (Settings → Appearance → *Two file panes*).
- **No AI of its own.** There is no built-in assistant and nowhere to enter an AI endpoint or key. The only AI that can connect is [DroidCommand AI](https://github.com/Victorious93/droidcommand-AI), and only if you switch external control on. It is off by default.
- **Built from scratch** on open-source libraries with compatible licences. The docs say where each one is used.

## Features

| Area | What you get |
| --- | --- |
| **Browse and organise** | One pane or two, with tabs · copy, move, delete and rename through an operation queue with **undo** · batch rename (find/replace or regex, case, counters) · bookmarks · trash on the same volume, with restore · indexed search · sort, filter and hidden files · properties with MD5, SHA-1 and SHA-256 · storage analyzer and duplicate finder<br>[Manual](docs/MANUAL.md) · [Browsing](docs/BROWSING.md) · [Batch rename](docs/BATCH_RENAME.md) |
| **View and edit** | Text and code editor with syntax highlighting, find/replace and undo · hex editor · line-by-line diff · image, PDF and font previews and a thumbnail gallery · **Media Pro** video and music player (AndroidX Media3, background audio) · photo tools (EXIF view, edit and strip; batch resize, convert and compress) · Obsidian-style notes vault<br>[Media](docs/MEDIA.md) · [Media Pro](docs/MEDIA_PRO.md) · [Photo tools](docs/PHOTO_TOOLS.md) · [Notes](docs/NOTES.md) |
| **Archives** | ZIP, JAR and APK (create and extract, including AES-256 passwords) · 7z · TAR, `.tar.gz`, `.tar.bz2`, `.tar.xz` · single `.gz`, `.bz2` and `.xz` files · RAR (extract only). Paths that try to escape the extract folder are blocked.<br>[Archives](docs/ARCHIVES.md) |
| **APK and developer tools** | APK info, install, sign (v1/v2/v3), rebuild and extract · DEX and smali browser · readable **Java view** · binary manifest and ARSC editing · compare two APKs · static patch presets behind an explicit authorization gate · App Manager Pro<br>[APK tools](docs/APK_TOOLS.md) · [Java view](docs/JAVA_VIEW.md) · [Deep edit](docs/APK_DEEP_EDIT.md) · [DEX and ARSC](docs/MT_NP_FINISH.md) · [Patch presets](docs/PATCH_PRESETS.md) · [App Manager](docs/APP_MANAGER.md) |
| **Power tools** | Root (libsu) and Shizuku backends, both **off by default** · built-in PTY terminal · declarative plugins and a script runner (no dex or class loading) · Quick Settings tiles, a bookmarks widget and a share target<br>[Power tools](docs/POWER_TOOLS.md) · [Plugins](docs/PLUGINS.md) |
| **Network and cloud** | FTP, FTPS, SFTP, SMB and WebDAV connections that open as tabs · Google Drive, Dropbox, OneDrive and S3-compatible stores (AWS S3, Cloudflare R2, Backblaze B2, MinIO) · Wi-Fi transfer with an optional PIN, rate-limited against guessing · built-in FTP, SFTP and web servers, off by default · one-way and two-way **folder sync** with a dry-run preview. A cloud provider shows *(Not configured)* when the build has no client ID for it.<br>[Network](docs/NETWORK.md) · [Cloud](docs/CLOUD.md) · [Server mode](docs/SERVER_MODE.md) · [Folder sync](docs/FOLDER_SYNC.md) |
| **Privacy and security** | Secure folder (AES-256-GCM, unlocked with biometrics or your device PIN) · saved archive passwords kept in the Android Keystore · external control from other apps is off by default and limited to an allowlist<br>[Secure folder](docs/SECURE_FOLDER.md) · [Fleet bridge](docs/FLEET_BRIDGE.md) |
| **Look and feel** | System, Light, Dark and AMOLED modes · seven colour themes (Classic, Cyber, Terminal, Ocean, Ember, Rose, Graphite) · Material You dynamic colour and accents · navigation rail and side-by-side panes on tablets and foldables · a launch screen that is never white |

## Install

**Requirements:** Android 8.0 or newer (API 26).

| Where | What you get |
| --- | --- |
| **CI artifact** | Open the latest run under *Actions → CI* and download `app-debug`. It is kept for one day, and the upload is best-effort: it is skipped if the account's Actions storage quota is full. |
| **Pre-release** | Debug-signed and sideload-only, published by hand with the *CI* workflow. Not device-tested and not production-signed. See [Releasing](docs/RELEASING.md). |
| **Build it yourself** | See [Build from source](#build-from-source). |

**First run.** Grant **all-files access** (Android 11 and newer) for fast browsing of shared storage. Without it the app still runs against its own folders. Every other permission is optional and explained in the [manual](docs/MANUAL.md#4-permissions-and-why-each-is-needed).

| Detail | Value |
| --- | --- |
| Package | `ai.victorious.filemanager` (debug builds: `ai.victorious.filemanager.debug`) |
| Version | `0.1.1`, from `versionName` in `app/build.gradle.kts` |
| SDK | `minSdk` 26 · `targetSdk` and `compileSdk` 35 |

## Build from source

You need **JDK 17** and the **Android SDK with platform 35**.

```bash
./gradlew :core:test :app:testDebugUnitTest   # unit tests, no device needed
./gradlew :app:lintDebug                      # Android lint
./gradlew :app:assembleDebug                  # debug APK
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. For the UI smoke tests, start an emulator or connect a device and run `./gradlew :app:connectedDebugAndroidTest`. Debug signing is described in [Signing](docs/SIGNING.md).

## Architecture

| Module | Role |
| --- | --- |
| `:core` | Pure JVM logic: paths, `FileIo`, the operation queue and undo, trash, bookmarks, search, archives, networking and the fleet JSON envelope. Unit-tested without an emulator. |
| `:app` | The Android app: Jetpack Compose and Material 3 UI, services, and the optional Messenger bridge. |
| `:ffmpeg-decoder` | The Media3 FFmpeg decoder library. Its native part is built only when the FFmpeg libraries are present. See [FFmpeg media](docs/FFMPEG_MEDIA.md). |

Built with Kotlin 2.1, Jetpack Compose (Material 3), AndroidX Media3, and Gradle with a version catalog (`gradle/libs.versions.toml`).

## Testing and CI

Every pull request runs three required checks, and releases are published by hand:

| Job | What it does |
| --- | --- |
| **Unit tests + debug APK** | `:core:test`, `:app:testDebugUnitTest`, a debug build, and a signing fingerprint check |
| **Android lint** | `:app:lintDebug`, which fails on errors |
| **Emulator smoke tests (API 34)** | Compose UI tests on an emulator, plus a random-input monkey run and a logcat scan |
| *Publish pre-release* | Manual only, from *Run workflow* with a `release_tag` |

Details: [Testing](docs/TESTING.md).

## Documentation

Start with the **[User Manual](docs/MANUAL.md)**, which describes only features that exist. The same text is built into the app (*Settings → Help & manual*).

| Topic | Docs |
| --- | --- |
| Using the app | [Manual](docs/MANUAL.md) · [Browsing](docs/BROWSING.md) · [Batch rename](docs/BATCH_RENAME.md) · [Archives](docs/ARCHIVES.md) |
| APK and code | [APK tools](docs/APK_TOOLS.md) · [Java view](docs/JAVA_VIEW.md) · [Deep edit](docs/APK_DEEP_EDIT.md) · [DEX and ARSC](docs/MT_NP_FINISH.md) · [Patch presets](docs/PATCH_PRESETS.md) · [App Manager](docs/APP_MANAGER.md) |
| Media and notes | [Media](docs/MEDIA.md) · [Media Pro](docs/MEDIA_PRO.md) · [FFmpeg media](docs/FFMPEG_MEDIA.md) · [Photo tools](docs/PHOTO_TOOLS.md) · [Notes](docs/NOTES.md) · [Notes and profiles](docs/POLISH_NOTES_PROFILES.md) |
| Network | [Network](docs/NETWORK.md) · [Cloud](docs/CLOUD.md) · [Server mode](docs/SERVER_MODE.md) · [Folder sync](docs/FOLDER_SYNC.md) |
| Power and security | [Power tools](docs/POWER_TOOLS.md) · [Plugins](docs/PLUGINS.md) · [Secure folder](docs/SECURE_FOLDER.md) |
| Fleet | [Fleet bridge](docs/FLEET_BRIDGE.md) · [VictorSuite panel](docs/VICTORSUITE_PANEL.md) |
| Project | [Testing](docs/TESTING.md) · [Signing](docs/SIGNING.md) · [Releasing](docs/RELEASING.md) |

## Victorious fleet

VictorFiles is part of the Victorious app fleet, and it can optionally act as an **EXTERNAL_NODE** for DroidCommand AI over a local Messenger bridge. It is not a bound companion: there is no shared signing key, no special bind permission, and no dependency guard. **Allow external control** (*Settings → Fleet*) is off by default, and only DroidCommand AI's packages are accepted as callers. See [Fleet bridge](docs/FLEET_BRIDGE.md) and section 5 of the [manual](docs/MANUAL.md).

<!-- victorious-fleet-readme-refresh:start -->
**Package id:** `ai.victorious.filemanager` (the app is named VictorFiles; the package id is unchanged)

> **Package note:** `app/build.gradle.kts` uses `ai.victorious.filemanager`, matching the fleet-manifest ROADMAP target and what DroidCommand AI and VictorSuite look for.

**Status:** blocked (CI stack) *(from [fleet-manifest ROADMAP](https://github.com/Victorious93/fleet-manifest/blob/main/ROADMAP.md))*

- Monorepo: [https://github.com/Victorious93/victorious-fleet](https://github.com/Victorious93/victorious-fleet)
- Fleet site: [https://victorious-framework.pages.dev](https://victorious-framework.pages.dev)
<!-- victorious-fleet-readme-refresh:end -->

<details>
<summary><b>Development phases</b></summary>

<br>

- **Phases 1–2:** dual panes with tabs, copy, move, delete, rename, operation queue with undo, search, bookmarks, trash; text and hex editors, archives, image and media viewers
- **Phases 3–4:** APK install, sign and rebuild; DEX and smali browsing; resources
- **Phases 5–6:** syntax highlighting, find/replace, diff, duplicates, storage analyzer, encrypted ZIP; smali assemble, ARSC strings, APKS/XAPK, signature v4 and verify, extract installed apps
- **Phase 7:** root (libsu), Shizuku, terminal, app manager backup and restore
- **Phase 8:** FTP, FTPS, SFTP, SMB and WebDAV connections; Wi-Fi transfer
- **Phase 9:** declarative plugins and a script runner; biometric-locked secure folder
- **Phase 10:** indexed search, trash on the same volume, themes, tablet and foldable layouts, compare two APKs, smali tools, RAR extraction ([details](docs/PHASE10.md))
- **Phase 11:** binary manifest editor, ARSC edits in a sealed APK, one-tap APK mod, PTY terminal
- **Phase 12:** media viewers, widgets, Quick Settings tiles, share target ([details](docs/PHASE12.md))
- **Phase 13:** readable Java view
- **Phase 14:** cloud and remote providers
- **Phase 15:** APK patch presets with an authorization gate

Since then: App Manager Pro, Media Pro, notes vault, photo tools, folder sync, server mode, DEX and ARSC tools, single-pane layout and colour themes.

The built-in AI assistant that shipped in Phase 9 has been removed. DroidCommand AI is the only AI that connects.

</details>

<details>
<summary><b>Third-party components</b></summary>

<br>

- Apache Commons Compress (Apache-2.0) and XZ for Java (public domain) for 7z, TAR and compressed files
- junrar for RAR extraction, under the UnRAR licence. See [Archives](docs/ARCHIVES.md) for the compliance notes.
- apksig (Apache-2.0) for APK signing
- Termux and jackpal terminal modules (Apache-2.0) for the PTY terminal
- Per-feature library and licence tables are in [Network](docs/NETWORK.md), [Server mode](docs/SERVER_MODE.md), [Media Pro](docs/MEDIA_PRO.md) and [DEX and ARSC](docs/MT_NP_FINISH.md).

</details>
