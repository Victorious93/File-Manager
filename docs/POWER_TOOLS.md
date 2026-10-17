# Phase 7 — Power tools (root, Shizuku, terminal, app manager)

Built from scratch. No MT Manager code was copied or decompiled.

## Root

- Toggle in **Settings → Root**. **Off by default.**
- Uses [libsu](https://github.com/topjohnwu/libsu) (`com.github.topjohnwu.libsu:core`, Apache-2.0) via JitPack.
- When Root is enabled and `su` grants access, protected paths (`/data`, `/system`, `Android/data`, `Android/obb`, …) use shell `ls` / `cp` / `mv` / `rm` / `chmod` through `EscalatingFileIo`.
- Clear snackbar / settings text when `su` is missing or not granted.

## Shizuku

- Toggle in **Settings → Shizuku**. **Off by default.**
- Uses [Shizuku-API](https://github.com/RikkaApps/Shizuku-API) (`dev.rikka.shizuku:api` + `provider`, MIT).
- The `rikka.shizuku.ShizukuProvider` entry is the library’s documented provider (not a fleet `BIND_*` permission).
- **Request Shizuku permission** button prompts the user when Shizuku is running.
- Fallback order for elevated ops: **Root → Shizuku → normal**.

## Terminal

- **Tools → Terminal** (or full-screen session UI).
- Runs `sh` via the active privilege backend (`su` / Shizuku process / normal `ProcessBuilder`).
- Scrollback with basic ANSI SGR colours, input line, extra keys (Esc, Tab, Ctrl, arrows).
- Multiple sessions (add / close). No PTY resize / full Termux package manager — deferred.

## App manager

- Bottom nav **Apps**: list installed apps (user / system / all), search, sort by name / size / install date.
- Actions: Open, App info, Uninstall, **Backup** (APKs + optional `.apks` zip; optional `/data/data` tar when Root is active).
- Backups write `manifest.json` under `<package>_<versionCode>_<utcStamp>/` in the focused pane directory.
- **Tools → Restore app backup**: select a backup folder with `manifest.json`, then install APKs (multi-session for splits). Data restore requires Root.

## Fleet constraints (unchanged)

- Still `EXTERNAL_NODE` Messenger stub only.
- No DependencyGuard, no shared signing key, no `ai.*.companion` packages.
- No new fleet `BIND_*` permissions.

## Unit tests (`:core`)

- `PrivilegeSelector` fallback + path heuristics + missing-reason copy.
- `ShellCommands` quoting / builders / package-name validation + `ShellListParser`.
- `AnsiParser` colours / strip.
- `AppBackup` naming, manifest JSON round-trip, filter/sort.
