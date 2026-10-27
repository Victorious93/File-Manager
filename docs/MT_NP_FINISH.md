# MT / NP finish (DEX + ARSC tools)

Phase closing remaining “MT Manager / NP Manager style” APK deep-edit gaps. **Written from scratch**
against public DEX / ARSC formats and dexlib2 (BSD-3). No MT/NP source was read or copied.

Code: `core/.../apk/mtnp/` — `DexRepair`, `DexMergeSplit`, `StringDecrypt`, `StringDecryptEmulator`, `DexStringEdit`, `ArscTranslate`.
Also `ArscStringAdder` (package `.../apk/`) for brand-new string resources. See `docs/PHASE16.md`.

## DEX repair
Recomputes Adler-32 **checksum**, SHA-1 **signature**, and **file_size** from the on-disk bytes.
Validates `map_off` / map list bounds. A map that overflows EOF can be clamped to 0; a full map
rebuild happens automatically when you merge/split (DexPool rewrite).

## DEX merge / split
Uses dexlib2 `DexPool` + `MemoryDataStore`.
- **Merge** several `classes*.dex` → one; duplicate class descriptors: first wins (listed as skipped).
- **Split** by class count (default 10 000) or approximate byte budget → `classes.dex`, `classes2.dex`, …

## String decrypt helpers
Detects Base64 / hex / high-entropy literals (also reuses `StringDecryptionHints.looksEncoded`).
Previews under user-selected methods: Base64, Base64+XOR, single-byte XOR, rolling XOR, hex→UTF-8.
**Nothing is written** until the user picks a method and applies edits via string replace.

## Add / edit strings
- **DEX:** `DexStringEdit.replaceStrings` / `replaceOne` (DexRewriter); `addConstantsClass` for new literals.
- **ARSC:** `ArscEditor` (edit existing); **`ArscStringAdder`** adds brand-new `string/<name>` entries in every config (Phase 16).

## Translation mode
Side-by-side locales, CSV and `strings.xml` export/import, missing-locale report, AI prompt helper
for the existing assistant. Suggestions are **user-approved** before apply.
**Limitation:** adding a locale that has no type-config in the ARSC yet is not supported (same as
`docs/APK_DEEP_EDIT.md`); import skips unknown locales instead of corrupting the table.

## Tests
`MtNpFinishTest` — repair, merge/split, decrypt, DEX string replace, ARSC CSV/XML/AI helpers (uses
`fixture.apk` when present).

## New language
`ArscLocaleAdder` adds a brand-new `values-<code>` string table to resources.arsc: texts are
appended to the global pool (existing indices unchanged), a new `string` type chunk copies the default
config with language/region set, untranslated entries are left out (fallback to default), and the
type spec gets the locale-config bit. `ModPlan.newLocales` builds and signs the APK. Tests:
`Phase6Test` (synthetic table, fallback, two languages, bad input) and `AxmlAndModTest`
(fixture APK, signed + verified, read back by `aapt2 dump resources`).

Also fixed: `Arsc.decodeConfig` read the config 4 bytes early (it took the config size as the MCC),
so non-default locales were shown as `mcc…` instead of e.g. `fr`.
