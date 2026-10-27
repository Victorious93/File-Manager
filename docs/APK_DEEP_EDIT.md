# Phase 11: APK deep edit + PTY terminal

## Binary AndroidManifest (AXML) editor

`core/apk/AxmlEditor.kt` parses the AXML chunk stream (string pool, resource-id map,
namespace / element / text nodes) into an editable model and re-encodes a fresh, byte-valid
binary XML. It never compiles from text.

`ManifestEditor` applies high-level edits:

| Edit | Behaviour |
|---|---|
| Package name | Optional clone-style rename: relative class names become absolute; authorities and permissions owned by the old package move to the new one |
| versionCode / versionName | Typed int / string attributes |
| minSdk / targetSdk | Creates `<uses-sdk>` if missing; rejects target < min |
| Permissions | Add / remove `<uses-permission>` |
| Debuggable | Sets or removes `android:debuggable` on `<application>` |
| Exported | Sets `android:exported` on a named component |

Framework attributes that are missing from the manifest's resource map (e.g. first-time
`debuggable`) are inserted at the end of the mapped name range; every pool index in every
node is shifted to match. Attributes stay sorted by resource id.

Round-trip tests use a tiny aapt2-built fixture (`core/src/test/resources/apk/fixture.apk`)
and check parseability with our `AxmlParser` and with `aapt2 dump xmltree`. After edit +
sign, `apksig` verification must pass.

## ARSC editing inside a sealed APK

`Arsc.withEdits` already rewrote the global string pool. Phase 11 adds:

- `ArscEditor.apply`: edits by `(resId, locale)`, with a **shared-slot guard** — if two
  resources share a pool index, editing one alone is rejected (aapt2 often dedupes equal
  strings).
- `ApkBuilder.rewrite`: stream an APK, replace named entries, keep `resources.arsc` /
  native libs STORED and aligned, drop old v1 signatures so the result can be re-signed.

### Adding new string resources (Phase 16)

`ArscStringAdder` appends new `string/<name>` resources: global pool values, package key
pool names, and entries in **every** string-type configuration. Compact (sparse / offset16)
tables are still unsupported. Styled / complex entries remain list-only. See `docs/PHASE16.md`.
`ArscEditor` continues to edit the text of *existing* string values only.

## One-tap "APK mod" flow

Tools → **APK mod**, or long-press an `.apk` → **APK mod**.

1. Open the sealed APK (manifest summary + string list load on IO).
2. Edit manifest fields, string values, and/or paste one smali class.
3. **Rebuild + sign + verify** → `ApkMod.applyTo`:
   - applies the plan via `ApkBuilder.rewrite`,
   - signs with the **File-Manager debug key** or a **user-chosen keystore**,
   - never a bundled release key,
   - runs `ApkSigning.verify` and fails the op if verification fails.
4. **Install** (optional) via the existing PackageInstaller session.

Smali classes replace the class of the same type in the dex that already holds it (or
`classes.dex` if new), using the existing `SmaliAssembler`.

## Terminal upgrade (real PTY)

Dependencies (Apache-2.0 — jackpal Android Terminal Emulator code, redistributed by Termux):

- `com.github.termux.termux-app:terminal-emulator:v0.118.3`
- `com.github.termux.termux-app:terminal-view:v0.118.3`

The rest of `termux-app` is GPLv3 and is **not** used. Licence note:
`assets/licenses/termux-terminal-Apache-2.0.txt`.

`PtySupport.available` loads `libtermux.so` once. On success, `TerminalScreen` shows
`PtyTerminalScreen` (TerminalView): resize, full VT100/ANSI, long-press select, copy/paste
via clipboard callbacks, Esc/Tab/Ctrl/Alt/arrows. Shell is `/system/bin/sh`, or `su` when
the Root privilege backend is active. Shizuku cannot host this PTY, so Shizuku sessions
still use `/system/bin/sh` (or the pipe fallback).

If `libtermux.so` fails to load, the existing pipe-based shell + `AnsiParser` is used
unchanged.

## Regenerating the fixture APK

```bash
# From a scratch dir with AndroidManifest.xml + res/ as in the fixture README:
aapt2 compile --dir res -o res.zip
aapt2 link -o fixture-unsigned.apk \
  -I $ANDROID_HOME/platforms/android-35/android.jar \
  --manifest AndroidManifest.xml --min-sdk-version 24 --target-sdk-version 34 res.zip
# optional: javac + d8 a tiny class, then add classes.dex stored into the APK
```

## Tests

- `AxmlAndModTest`: AXML round-trip, manifest edit + sign + verify + aapt2 dump, ARSC edit
  (incl. sealed APK), smali replace + sign, invalid package rejection.
- Existing `Phase6Test.arscParseEditRoundTrip`, `AnsiParser` tests remain.
