# APK patch presets (static, lawful use only)

Phase 15 adds **static APK patch presets**: one-tap edits applied to an APK's
`AndroidManifest.xml` and smali, then **rebuilt → re-signed → verified → (optionally) installed**
through the signing path from earlier phases. Everything happens on static files.

## What this is NOT
This feature does **no runtime hooking**. There is **no** LSPatch/LSPosed/Xposed, no Frida, no
JD‑Core/GPL decompiler, no auto-download of APKs, and **no built-in list of target apps or
vendors**. Detection is purely pattern-based on generic Android APIs and method names. We never
decompile, copy, or reference any third-party tool's binary.

## Authorization gate (required, no bypass)
Before any patch is applied, the user must confirm, per **package name + signing‑certificate
SHA‑256**, that they own the app or are legally authorized to modify it. The exact wording shown
is `PatchGate.CONFIRMATION_TEXT`:

> I confirm that I own this app, or I am legally authorized by its owner to modify it. I will only
> patch APKs I have the right to change, for lawful purposes such as my own development, testing,
> accessibility, security research, or interoperability. I will not use this to bypass licensing,
> piracy protection, or anyone else's rights.

Confirmations are stored **locally** (`PatchGate`, a JSON file in app storage) and checked by
`PatchSession.apply`, which throws `PatchNotAuthorizedException` when no matching record exists.
A different signing cert for the same package is treated as a different app and must be
authorized separately. There is no skip path.

## Lawful uses
Patch only APKs you have the right to change. Legitimate uses include: your own app during
development; QA/testing builds; accessibility or interoperability fixes you are permitted to make;
and authorized security research. Do **not** use presets to defeat another party's licensing,
copy protection, or to redistribute modified apps. You remain responsible for complying with the
app's licence and applicable law.

## Workflow
1. Open an APK, read its package + cert, and authorize it (gate).
2. Pick presets. `PatchSession.preview(steps)` shows a **unified diff** per changed entry
   (decoded manifest XML, or disassembled smali) **before** anything is written.
3. `PatchSession.apply(steps, out, key)` rewrites the entries, re-signs with a key you supply
   (the File‑Manager debug key or your own keystore — **never a bundled release key**), verifies
   the signature, and writes `out`. A backup of the original is kept; `PatchApplied.undo()`
   restores it. Install is the existing user-confirmed `PackageInstaller` step.

## Presets
### Manifest flags (`<application>`)
- **debuggable** — set/clear `android:debuggable`.
- **allowBackup** — set `android:allowBackup`.
- **usesCleartextTraffic** — set `android:usesCleartextTraffic` (allow plain HTTP).
- **extractNativeLibs** — set `android:extractNativeLibs`.

Round-tripped and re-signed; `ManifestEditor` reads them back after apply.

### Signature / license-check neutralization (pattern-based)
`SmaliPatch.detect` scans disassembled classes for methods that *look like* a signature or license
check, using generic signals only: references to `Landroid/content/pm/Signature;`,
`getPackageInfo`, `GET_SIGNATURES`, `signingInfo`, the AOSP `com.android.vending.licensing`
classes, and generic name hints (`verifySignature`, `checkLicense`, `isLicensed`, …). It reports
candidates with the signals that matched; **the user chooses** which (if any) to neutralize.
`NeutralizeMethod` rewrites the chosen method body to a constant return (`true`/`false`/0/null for
the method's return type). No commercial target list is shipped.

### Developer aids
- **log-inject** — `InjectEntryLog` adds a `Log.d(tag, message)` at a method's entry. Methods in
  `.registers` form are first converted to `.locals` form (so parameters become p‑registers and
  adding temporaries never shifts them), then two fresh locals carry the strings.
- **remove-toast** — `RemoveToasts` deletes every `Landroid/widget/Toast;->show()V` call in a
  class (register-safe: it removes invoke lines only; any `Toast.makeText` result is left inert).

### Network security config (trust user CAs) — deferred
Trusting user-installed CAs requires a new `res/xml/network_security_config.xml` **resource table
entry**, which the current static ARSC tooling cannot add (see `docs/APK_DEEP_EDIT.md`).
`PatchStep.TrustUserCertificates` is previewed as *not applicable* and refused by `apply`. For
plain‑HTTP needs today, use the **usesCleartextTraffic** preset.

## Safety / engineering notes
- Transforms are text-level on smali and re-assembled with Google's smali assembler
  (`SmaliAssembler`); a broken transform fails assembly rather than corrupting the DEX.
- Signing never bundles a release key; the File‑Manager debug key is per-install only.
- No new third-party libraries were added. Reuses dexlib2/baksmali/smali (BSD‑3‑Clause, maintained
  by Google under `com.android.tools.smali`), apksig (Apache‑2.0), and kotlinx.serialization
  (Apache‑2.0). No GPL/LGPL code, no LSPatch/Xposed, no JD‑Core.
