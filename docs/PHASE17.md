# Phase 17 — Droidcommand AI code assist, secure folder, Dolby/DTS audio, translation & utilities, plugins

Written from scratch. **No MT Manager or NP Manager code was read, copied or decompiled**; only their
public feature lists informed the scope.

## 1. Code assist via Droidcommand AI

The text editor (used for code files and for smali exported from APK surgery) has an
**Ask Droidcommand AI** button (sparkle icon). It offers **Explain** and **Suggest edit** (with an
optional instruction). A suggested edit is split into hunks and shown as a red/green diff; tick the
hunks to keep and **Apply**, or **Reject**. Applied edits go through the editor's undo history and
are not saved until you tap Save.

VictorFiles holds **no AI API key and makes no AI network calls**: Droidcommand AI
(`ai.droidcommand.app`) owns the model, keys and network. If it is missing, the dialog shows
*Get Droidcommand AI*; if it is installed but does not handle the action, *Update Droidcommand AI*.

### Contract v1 (VictorFiles = client, Droidcommand AI = provider)

| | |
|---|---|
| Transport | `startActivityForResult` (ActivityResult API), explicit package `ai.droidcommand.app` |
| Action | `ai.droidcommand.action.CODE_ASSIST` |
| Request extra | `ai.droidcommand.extra.REQUEST_JSON` |
| Result extra | `ai.droidcommand.extra.RESULT_JSON` (with `RESULT_OK`) |

Request:

```json
{"v":1,"task":"explain|suggest_edit","fileName":"Foo.smali","language":"SMALI",
 "code":"…","instruction":"…","truncated":false,"prompt":"ready-made prompt incl. code"}
```

`code` is capped at 60 000 chars (binder limit); `truncated` says so. Result:

```json
{"v":1,"ok":true,"explanation":"…","revisedCode":"full new file (suggest_edit only)"}
{"v":1,"ok":false,"error":"…"}
```

If `revisedCode` is missing, VictorFiles takes the largest fenced code block in `explanation`.
A result with `v` greater than 1 is refused. `RESULT_CANCELED` = user cancelled.

### What Droidcommand AI needs to add (not done here — repo untouched)

As of droidcommand-AI `22ed36e`, its manifest exports only `MainActivity` (launcher) and the
Shizuku provider; it consumes companions (`ICompanionService`, `IHackerAIService`, …) but exposes
no AI entry point to other apps. To light this up it needs:

1. An exported activity with an intent-filter for `ai.droidcommand.action.CODE_ASSIST`
   (default category) that reads `REQUEST_JSON`, shows the request to the user for consent,
   runs it through its configured model, and `setResult(RESULT_OK, Intent().putExtra(RESULT_JSON, …))`.
2. Caller check: `getCallingActivity()` / `callingPackage` must be `ai.victorious.filemanager`
   (or `.debug`) and its signing cert must match the fleet cert (its existing `NodeSignatureGuard`).
3. Optional: `<package android:name="ai.victorious.filemanager"/>` is already in its `<queries>`.

Code: `core/…/ai/CodeAssist.kt` (`CodeAssistContract`, `PromptBuilder`, `EditHunks`),
`app/…/ai/DroidCommandAssist.kt`, `app/…/ui/AiAssist.kt`. Tests: `CodeAssistTest`.

## 2. Biometric-locked secure folder

Already shipped in Phase 9 and verified against this brief (no new code needed): `SecureVault`/`VaultCrypto`
(AES-256-GCM, 64 KB chunks, Keystore key requiring user auth), `SecureFolderManager` (AndroidX BiometricPrompt
with device-credential fallback), move in/out, and `VaultLock` auto-lock (timeout + on background). Tests:
`SecureVaultTest` (crypto round-trip, tamper detection). Details: docs/SECURE_FOLDER.md.

## 3. Dolby / DTS audio via Media3 + FFmpeg

| Format | Status |
| --- | --- |
| AC-3, E-AC-3 (.ac3 .eac3 .ec3, and inside MKV/MP4/TS) | Plays: platform decoder first, then FFmpeg |
| DTS, TrueHD/MLP inside MKV/MP4/TS | Plays via FFmpeg (`dca`, `truehd`, `mlp`) |
| Bare .dts / .thd | Container-dependent: Media3 has no elementary-stream extractor; remux to MKV |
| WMA (.wma, ASF) | Unsupported: Media3 has no ASF extractor. A clear hint is shown instead of a crash |

Renderer mode `EXTENSION_RENDERER_MODE_ON`. On a player error `MediaProEngine` shows a hint from
`AudioCodecSupport.failureHint` (also says when the native FFmpeg lib failed to load).
Licence: FFmpeg built from source as LGPL 2.1+ (CONFIG_GPL off), dynamically linked via JNI; LGPL notice in
app assets. Dolby/DTS patents are not covered by the LGPL. Tests: `AudioCodecSupportTest`.

## 4. Translation mode & utilities

- **Translate strings.xml** (Tools): select decoded `res/values/strings.xml` (+ optional `values-xx/strings.xml`
  or a CSV). Side-by-side editor, "only untranslated" filter, progress, Save target (writes
  `values-<locale>/strings.xml`), Export/Import CSV. Markup like `<xliff:g>` is kept; bare `& < '` are escaped.
  `translatable="false"` strings are skipped; plurals/arrays untouched. ARSC translation (compiled resources)
  remains in MT/NP → Translate (Phase 12). Tests: `StringsXmlTranslateTest`.
- **Utilities** (Tools): hashes MD5/SHA-1/SHA-256/SHA-512 for text or the selected file with verify; Base64
  (standard/url) and hex encode/decode; Unix timestamp ⇄ date (seconds or ms auto-detect, ISO, local);
  colour picker (#RGB/#ARGB/#RRGGBB/#AARRGGBB/rgb()/rgba(), sliders, HSV, Android int); regex tester
  (flags, groups, replacement). Tests: `DevUtilitiesTest`.

## 5. Plugin system

JSON plugins (API v1, Phase 9) are unchanged. Phase 17 adds **API v2 app plugins**: separate APKs discovered
by intent action, listed with enable/disable, launched with read-only URI grants to the selected files only.
Documented in docs/PLUGINS.md. Tests: `AppPluginsTest`.
