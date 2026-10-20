# MT / NP finish (DEX + ARSC tools)

Phase closing remaining “MT Manager / NP Manager style” APK deep-edit gaps. **Written from scratch**
against public DEX / ARSC formats and dexlib2 (BSD-3). No MT/NP source was read or copied.

Code: `core/.../apk/mtnp/` — `DexRepair`, `DexMergeSplit`, `StringDecrypt`, `DexStringEdit`, `ArscTranslate`.

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
- **DEX:** `DexStringEdit.replaceStrings` / `replaceOne` (DexRewriter).
- **ARSC:** existing `ArscEditor` (edit text of existing string resources only).

## Translation mode
Side-by-side locales, CSV and `strings.xml` export/import, missing-locale report, AI prompt helper
for the existing assistant. Suggestions are **user-approved** before apply.
**Limitation:** adding a locale that has no type-config in the ARSC yet is not supported (same as
`docs/APK_DEEP_EDIT.md`); import skips unknown locales instead of corrupting the table.

## Tests
`MtNpFinishTest` — repair, merge/split, decrypt, DEX string replace, ARSC CSV/XML/AI helpers (uses
`fixture.apk` when present).
