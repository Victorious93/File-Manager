# App Manager — parity pass against MuntashirAkon/AppManager

This pass compared File Manager's App Manager Pro with the **public feature list** (README / docs) of
[MuntashirAkon/AppManager](https://github.com/MuntashirAkon/AppManager) (GPL-3.0). Only the feature
descriptions were used as a guide. **No AppManager source code, resources or data were read or copied.**
The original design is in [APP_MANAGER.md](APP_MANAGER.md).

## Privilege states

A banner at the top of App Manager Pro always shows one of these states:

| State | What works | How to fix |
|---|---|---|
| **Root: ready** | Everything, including Intent Firewall blocking, clearing cache on any Android version, and app data backup | — |
| **Shizuku: ready** | Freeze/unfreeze, permissions, app ops, uninstall/restore, installer options, clearing cache on Android 14+ | Data backup and IFW need root |
| **Limited mode** | Browsing, tracker/library scan, logcat (own app), usage and data stats | "Grant Shizuku" or "Open settings" button |

Buttons that need privilege stay visible with a 🔒 mark. Tapping one says why ("Needs Root or Shizuku").
Every change still goes through the existing Confirm dialog, which lists the exact commands it will run.

## Added in this pass

1. **Real code scan for trackers and libraries.** Each app's `classes*.dex` files (base APK and splits)
   are read with dexlib2, so trackers are found in code, not only in manifest component names.
   A new **library detector** recognises 37 common SDKs (OkHttp, Retrofit, Glide, Compose, Kotlin coroutines, …).
2. **Optional Exodus Privacy tracker list.** "Update tracker list" in the Trackers tab downloads
   `reports.exodus-privacy.eu.org/api/trackers` on demand and caches it in app storage. Only plain package
   prefixes are used; regex signatures are skipped. It is merged with the built-in list, which keeps priority.
3. **More batch operations** on selected apps: Unfreeze, Clear cache, Clear data, Uninstall (for the current user),
   Restore (`pm install-existing`, which brings back a system app removed for the user), Reset app ops, and
   **Backup APKs** (it uses the existing AppBackupService; split apps become `.apks`).
4. **Installer with options** via privileged `pm install`: installer package name, allow downgrade,
   allow test-only, grant all runtime permissions, and bypass the low target SDK block (Android 14+).
   The APK is copied to `/data/local/tmp`, installed, then deleted.
5. **App ops editor.** You can set any mode (allow / ignore / deny / default / foreground) for each op, add an op
   from a catalog or by name (names are checked), and reset all ops to default.
6. **Data usage** (mobile and Wi-Fi, last 30 days, per app) in the Usage tab. It uses NetworkStatsManager
   with the usage access permission the app already has.
7. **Clear privilege states**: the banner and the gated buttons described above.

## Already there before this pass

- Component blocker (pm disable plus Intent Firewall XML), and blocking tracker components in one tap
- Runtime permission grant/revoke, app ops viewer, and setting an op to deny
- Batch disable/enable, force-stop, freeze (suspend)
- Running processes viewer with force-stop
- Usage (screen time) stats
- Profiles with schedules (WorkManager)
- Logcat viewer with filter and export
- APK/.apks backup and restore (Backup tab), and the normal session installer, which handles split APKs

## Skipped, and why

| Feature | Reason |
|---|---|
| Activity interceptor | Needs its own exported proxy activity; it is a security risk in a file manager |
| SSAID / net policy editing | Uses hidden APIs or writes to system XML; it is easy to break the device and hard to test |
| Shared-prefs / database editor | It means editing other apps' private data as root; the existing root file editor already covers it |
| Backup encryption (OpenPGP/RSA/ECC) | Needs key management UI; this is a separate project |
| Debloater lists | The usual community lists have mixed or unclear licences; we will not bundle them |
| Watt / Blocker rule import | Third-party formats; low value |
| Split-APK install with options | The normal installer already does splits; options are for single APKs only |
| Live (streaming) logcat | A snapshot plus refresh is enough for now |

## Limits

- Library and tracker matching is by package prefix. Obfuscated or repackaged SDKs will be missed, and a matching prefix is a hint, not proof.
- Scanning very large apps reads every dex file into memory. The class count is capped at 400k.
- Per-app mobile data can be empty on some devices or OEM builds. The device's Wi-Fi/mobile split is what Android reports.
- Clearing cache without root needs Shizuku on Android 14+ (`pm clear --cache-only`); older versions need root.
- Exodus allows 3 API requests a minute; the list is only fetched when you tap the button.

## Licence notes

- **AppManager (GPL-3.0):** used only as a feature checklist. No code, strings, layouts or data were taken.
- **Exodus Privacy tracker data:** ODbL 1.0 (database) with DbCL 1.0 (contents). It is **not bundled** in the APK or the repo.
  It is downloaded by the user on demand, stored as a separate file, and credited in the UI
  ("Tracker data © Exodus Privacy, ODbL 1.0"). Exodus' own software (AGPL) is not used.
- The built-in tracker and library prefix lists were written for this project from public SDK package names.
