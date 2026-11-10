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

## Added in the second pass (leftovers)

Still written from the public feature list only. No App Manager code, strings or data.

1. **Split APK install with options.** Apps → **Install…** now takes a single `.apk`, an `.apks` / `.xapk`
   bundle, or a folder of split APKs. Bundles are unpacked to the app's external cache (only entries
   ending in `.apk`, no paths, base first). All APKs are then installed in **one** privileged session:
   `pm install-create <options> -S <total>`, one `pm install-write` per split, `pm install-commit`. If any
   step fails the session is abandoned (`pm install-abandon`) and the temp folder in `/data/local/tmp`
   is removed. The same options as single APKs apply (installer name, downgrade, test-only, grant all,
   bypass low target SDK). `.apkm` is refused (it is encrypted by its publisher).
2. **Live logcat.** Logcat → **Live** streams `logcat -v threadtime -T 300` into a 5,000-line ring
   buffer and refreshes twice a second, scrolling to the newest line. **Clear** empties it. With Root or
   Shizuku you can limit it to one package (by its pid); without, Android only shows this app's own log.
   The stream stops when you turn Live off or leave the tab.
3. **Encrypted app backups.** In Backup, tick **Encrypt with a passphrase** (8+ characters, typed twice).
   Format `VFBE1`, designed for this app (not App Manager's OpenPGP/RSA/ECC scheme):
   - Key: PBKDF2-HMAC-SHA256, 600,000 iterations, random 16-byte salt, 256-bit key.
   - Data: AES-256-GCM in 1 MiB chunks. Chunk nonce = random 8-byte prefix + chunk number. Each chunk's
     authenticated data is the file header + chunk number + an "is last chunk" flag, so a cut-off,
     reordered or extended file fails instead of restoring a broken APK.
   - Layout: `"VFBE"`, version 1, KDF id 1, iterations (u32), salt length + salt, nonce prefix (8), chunk
     size (u32), then `length (u32) + ciphertext` per chunk. Every APK, `.apks` and data `.tar` becomes
     `<name>.vfbe` and the plain copy is deleted. `manifest.json` stays readable (it names the app and its
     files) and gets `"encryption": "VFBE1"`. Old plain backups still restore.
   - Restore (**Tools → Restore app backup**) asks for the passphrase, decrypts into the app's private
     cache, installs, and deletes the decrypted copies. A wrong passphrase is reported clearly.
   - The passphrase and key are never saved, logged or sent anywhere. Forget it and the backup is lost.
4. **Debloater (your own list).** A new **Debloat** tab. Import a plain-text file with one package
   name per line (`#`, `//` and `;` start comments; CSV lines and `pm uninstall … com.foo` lines also
   work). Pick **Freeze (suspend)**, **Disable** or **Uninstall for this user (keep data)**. Only
   installed packages get a command. These are always skipped: core system packages (System UI,
   Settings, Phone, package installer, permission controller, telephony/media/settings providers,
   WebView, Google Play services, Shizuku, Magisk, …), this app, your current home screen and your
   current keyboard. **No list is bundled.** **Undo this list** runs the reverse commands (unsuspend,
   enable, `cmd package install-existing`).
5. **Every confirm dialog now lists the exact shell commands** it will run (Intent Firewall rule files
   are shown as "(Intent Firewall rules file)").

## Skipped, and why

| Feature | Reason |
|---|---|
| Activity interceptor | Needs its own exported proxy activity; it is a security risk in a file manager |
| SSAID / net policy editing | Uses hidden APIs or writes to system XML; it is easy to break the device and hard to test |
| Shared-prefs / database editor | It means editing other apps' private data as root; the existing root file editor already covers it |
| Bundled debloat lists | The usual community lists have mixed or unclear licences; you import your own instead |
| OpenPGP / RSA / ECC backup keys | Needs key management UI; the passphrase format above covers the common need |
| Watt / Blocker rule import | Third-party formats; low value |

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
