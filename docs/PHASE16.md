# Phase 16 — DEX repair, merge, string decrypt emulator, add strings

Written from scratch against the public DEX / ResTable specs and dexlib2 / smali / baksmali
(BSD-3). **No MT Manager or NP Manager source was read, copied, or decompiled.**

Code:

- `core/.../apk/mtnp/DexRepair.kt` — magic, Adler-32 checksum, SHA-1 signature, file_size, map list
- `core/.../apk/mtnp/DexMergeSplit.kt` — merge / split / rebalance under 64k methods, duplicate policy
- `core/.../apk/mtnp/StringDecryptEmulator.kt` — find decryptors, restricted interpreter, optional smali write-back
- `core/.../apk/ArscStringAdder.kt` — brand-new `string/<name>` resources in every config
- `core/.../apk/mtnp/DexStringEdit.kt` — `addConstantsClass` for new DEX string constants
- UI: `MtNpScreen` tabs Repair / Merge-Split / Decrypt / Emulate / DEX strings / Add strings / Translate

## 1. DEX repair

`DexRepair.inspect` / `repair` fix:

| Field | Fix |
|-------|-----|
| magic | rewrite to canonical `dex\n035\0` when the file still looks DEX-like |
| endian_tag | set little-endian `0x12345678` when plausible |
| header_size | force `0x70` |
| file_size | set to actual byte length |
| signature | SHA-1 of bytes from offset 32 |
| checksum | Adler-32 of bytes from offset 12 |
| map_off | clamp out-of-range to 0 (full map rebuild happens on merge/split via DexPool) |

Every fix is listed in `Report.fixesApplied`. Completely non-DEX blobs (all zeros) are rejected.

## 2. DEX merge / rebalance

- `merge(dexes, DuplicatePolicy)` — FIRST / LAST / PREFER_LARGER winner for duplicate class descriptors; `winners` + `skippedDuplicates` reported.
- `mergeAndRebalance` — merge then pack under a method budget (default 65 000, under the historical 64k limit).
- `findDuplicates` — class descriptor → list of input DEX indices.
- Split by class count or approximate size still available.

## 3. String decrypt emulator (opt-in)

1. `findDecryptors` — static `String` methods with `String` / `int` / `long` / `byte[]` / `char[]` params (reuses `StringDecryptionHints` when call sites exist).
2. `collectConstantCallSites` — call sites whose argument is a nearby `const-string` / `const`.
3. `emulate` / `emulateCallSites` — restricted Dalvik interpreter:
   - Whitelist: arithmetic, branches, arrays, `String` / `StringBuilder` APIs, pure static calls into the same DEX.
   - **Blocked:** reflection, `Runtime` / `System` / IO / network / Android / `new-instance` except String/StringBuilder, monitors, throws.
   - Budgets: instruction count (default 50 000), wall-clock timeout (default 2 s), call depth (default 8).
4. Optional `inlineResultsInSmali` / `inlineIntoDex` — replace `invoke-static` + `move-result-object` with `const-string` (user must enable write-back in the UI).

Nothing runs unless the user opens the **Emulate** tab and taps **Emulate call sites**.

## 4. Add strings

- **ARSC:** `ArscStringAdder.addStrings` appends values to the global pool, keys to the package key pool, and new entries to **every** string-type configuration (all locales). Compact (sparse/offset16) tables are rejected.
- **DEX:** `DexStringEdit.addConstantsClass` inserts `Lfm/AddedStrings;` (or a chosen type) with `static final` string fields so the literals stay in the pool.
- **APK:** `ModPlan.newArscStrings` / `newDexStrings` → `ApkMod.apply` → existing signer + verify.

## UI

Long-press APK → DEX / ARSC tools (or Tools → APK tools):

- **Repair** — inspect + save repaired DEX with fix list
- **Merge/Split** — duplicate policy chips, Merge, Split, **Rebalance 64k**
- **Decrypt** — literal Base64/XOR helpers (unchanged)
- **Emulate** — find decryptors, emulate, optional const-string write-back
- **DEX strings** — list / replace
- **Add strings** — ARSC + DEX lines → rebuild + sign
- **Translate** / new language — unchanged

## Tests

`Phase16Test` — repair report, duplicate policies + rebalance, XOR emulator + forbidden API, ARSC add (synthetic + fixture.apk), DEX constants.

## Limits

- Emulator is intentionally tiny: many real obfuscators need a fuller VM or native code — those simply fail with a clear error.
- ARSC add-strings does not support sparse/offset16 type tables.
- Adding strings does not invent public.xml / R.java; IDs are assigned at the end of the string type.
