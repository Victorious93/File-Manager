# Phase 12: MT Manager–class gap fill (core logic)

Clean-room implementations written from public feature descriptions only. No code or assets
from any third-party APK were used.

## Status
| Feature | `:core` logic | Unit tests | UI wired |
|---|---|---|---|
| Hex search (`hex/HexSearch.kt`) | yes | yes | **no** |
| Saved archive passwords (`archive/ArchivePasswordStore.kt`) | yes | yes | **no** |
| File split / join (`ops/FileSplit.kt`) | yes | yes | **no** |

`:core:test` passes (255 run, 1 pre-existing skip). `:app` was not built or tested for this change
(no Android SDK in the authoring environment).

## Behavior
- **Hex search**: hex patterns with `??` wildcards or text; sees unsaved edits; forward/backward
  with optional wrap; `findAll` capped (default 10,000). Start offsets past the last valid
  position are clamped.
- **Password store**: list is encrypted through a `SecretCodec` before reaching `BlobStorage`.
  The app must supply a Keystore-backed codec; none exists yet. Undecryptable data loads as empty.
  `tryAll` retries saved passwords only on wrong-password exceptions.
- **Split/join**: `name.001…name.999` plain slices (joinable with `cat`). Never overwrites;
  join refuses gaps instead of producing a truncated file.

## Next (UI wiring, needs device/emulator testing)
Hex editor find bar, Keystore `SecretCodec`, archive-extract password prompt integration,
and Split/Join entries in the file long-press menu.
