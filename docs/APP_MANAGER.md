# App Manager Pro

Built-in app inspector for VictorFiles. **Written from scratch.** The public feature list of
[MuntashirAkon/AppManager](https://github.com/MuntashirAkon/AppManager) (GPL-3) was used only as a
guide to what an Android app manager typically offers — **none of its source was read or copied**.

Code: `core/.../appmgr/` (pure JVM command builders, parsers, tracker signatures) and
`app/.../appmgr/AppManagerService.kt` + `ui/AppManagerPro.kt`.

## Features

| Feature | How |
|---|---|
| Component blocker | Activities / services / receivers / providers. **Root:** Intent Firewall rules in `/data/system/ifw/<pkg>.xml` (survives reboot; providers fall back to `pm disable`). **Shizuku:** `pm disable/enable --user <u> pkg/class`. |
| Tracker scan | Self-built signature list of tracker **class-name prefixes** (`TrackerSignatures`). Matching is prefix-only (`com.facebook.ads.` does not match `com.facebook.adsfoo.`). Idea of class-prefix detection is popularised by [Exodus Privacy](https://exodus-privacy.eu.org); we did **not** use their dataset (licence / independence). One-tap "Block tracker components". |
| Permissions | List requested perms; grant/revoke via `pm grant`/`pm revoke` (needs Root/Shizuku). Grant state from `dumpsys package` when elevated. |
| AppOps | `cmd appops get/set` (allow/deny/ignore/default). |
| Freeze / unfreeze | `pm disable-user` / `pm enable`, plus `pm suspend` / `pm unsuspend`. Force-stop via `am force-stop`. Clear data via `pm clear`. |
| Batch ops | Multi-select → Disable / Enable / Force-stop / Freeze. Always shows a **Confirm** dialog with the full plan before any shell runs. |
| APK + data backup | Existing `AppsPane` / `AppBackupService` (APK always; `/data` needs root). Tab **Backup**. |
| Logcat | `logcat -d -v threadtime`; filter by level / tag / text; export to a cache file. |
| Usage stats | `UsageStatsManager` over the last 7 days. Prompts for `PACKAGE_USAGE_STATS` via system settings when missing. |
| Profiles | Saved JSON sets of actions (disable/enable/suspend/block/grant/revoke/appop/force-stop). Apply runs the plan after confirm. |
| Running apps | Parses `ps -A`; force-stop by package (confirm). |

Every destructive action goes through `ConfirmPlan` → `AppManagerService.execute`.

## Privilege

| Action | Normal | Shizuku | Root |
|---|---|---|---|
| List apps / components / local tracker scan | ✓ | ✓ | ✓ |
| Logcat (own) / usage (with permission) / running (best-effort) | ✓ | ✓ | ✓ |
| Block / freeze / permissions / AppOps / profiles / IFW | — | ✓ (`pm`) | ✓ (IFW + `pm`) |
| Data backup/restore | — | — | ✓ |

## Tracker signatures

See `TrackerSignatures.ALL` (~45 SDKs across analytics, advertising, crash reporting, attribution,
profiling, location, social). Prefixes end with `.`. Credit: Exodus Privacy for the *approach*;
list content is independent.

## Tests

`core/.../appmgr/AppManagerProTest.kt` — IFW XML, pm quoting, permission/AppOps parsers, tracker
prefix matching, logcat filter, usage merge/sort, `ps` parse, profile codec/plan, batch plans.
