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

### Fleet Shizuku standard (feature/fleet-shizuku-standard)

- **Deps:** `dev.rikka.shizuku:api` + `provider` **13.1.5** (current stable on Maven Central). **Sui** supported via `Sui.init(packageName)`; same binder API.
- **Manifest:** `rikka.shizuku.ShizukuProvider` exactly per Shizuku-API README (`${applicationId}.shizuku`, exported, `INTERACT_ACROSS_USERS_FULL`), plus `<queries>` for `moe.shizuku.privileged.api`.
- **Detection / permission:** `ShizukuController` (the only class importing `rikka.shizuku`) uses `Shizuku.pingBinder()`, sticky binder-received / binder-dead / permission-result listeners and `Shizuku.requestPermission()` (`moe.shizuku.manager.permission.API_V23`). Pure state machine `core…priv.ShizukuStateMachine` maps probes to exactly four states: **GRANTED / DENIED / NOT_INSTALLED / NOT_RUNNING**.
- **UserService:** `PrivilegedUserService` (AIDL `IPrivilegedService`, `destroy()` = 16777114) bound with `Shizuku.bindUserService`, runs in the `:priv` process as shell (or root) uid. Bind and calls are blocking and only made from `Dispatchers.IO`.
- **Small interface:** `core…priv.PrivilegedOps` (`list`, `readText`, `writeText`, `chmod`, `chown`, `remount`, `installPackage`, `uninstallPackage`). `PrivilegeManager.ops()` picks Root → Shizuku UserService → `UnprivilegedOps` (`PrivilegedOpsSelector`). Unbound service ⇒ falls back, never crashes.
- **Wired through the UserService:** `ShizukuShellExecutor.exec` now calls the UserService, so `EscalatingFileIo` browsing/reading/writing/copy/move/delete/chmod on `/data`, `/system`, `Android/data|obb`, plus app backup and plugins all use it when Shizuku is the backend. **Settings → Power ops** adds chmod / chown / remount (`/`, `/system`, `/vendor`, `/product`, `/system_ext`, `/odm`) / `pm install -r` / `pm uninstall`.
- **Security:** `GatedPrivilegedOps` + `ConfirmGate`: every mutation via `ops()` needs a single-use approval for that exact action + target, granted only by the confirm dialog. Command output is never logged or shown; errors carry only the first stderr line. Arguments validated (`PowerCommands`). Permission is only requested from a user tap. Interactive terminal still uses the reflective `newProcess` (UserService has no PTY).
- **UX:** Material 3 `ShizukuStatusCard` with a neon dot per state, fleet copy from `ShizukuCopy`, one action (Grant access / Open Shizuku / Get Shizuku) and Re-check. Not running ⇒ copy explains starting via Wireless debugging or root; unprivileged browsing keeps working.

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
- `ShizukuStateMachine` four states + transitions, `ShizukuCopy`, `ShellPrivilegedOps` against a fake UserService runner, `ConfirmGate`, `PrivilegedOpsSelector` fallback.
- `ShellCommands` quoting / builders / package-name validation + `ShellListParser`.
- `AnsiParser` colours / strip.
- `AppBackup` naming, manifest JSON round-trip, filter/sort.
