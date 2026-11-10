# Compare: files, words and folders

Written from scratch (no third-party diff code). Code: `core/.../diff/`.

| Piece | File | What it does |
|---|---|---|
| Line diff | `LineDiff.kt` | Myers O(ND) line diff, unified + side-by-side rows (existing). |
| Word diff | `WordDiff.kt` | Splits a changed line pair into words / whitespace / punctuation and runs the same Myers engine, so the side-by-side view highlights only the words that changed. Lines over 4 000 tokens are marked as fully changed (keeps memory bounded on minified files). |
| Folder compare | `FolderCompare.kt` | Recursive two-folder compare: `SAME`, `DIFFERENT`, `ONLY_LEFT`, `ONLY_RIGHT`, `TYPE_MISMATCH` (file vs folder), sizes, and which side is newer. |

## Folder compare details

- **Exact** mode: equal sizes are confirmed with SHA-256 over the whole file. **Quick** mode: size + modified
  time (seconds), much faster, can miss same-size same-time edits (documented in the UI).
- A folder present on one side only is listed once with its total size (not expanded).
- Symlinked folder loops are walked once.
- `copyMissing` copies one-sided entries across, keeps modified times, never overwrites, refuses targets
  that would land outside the destination, and reports copied / skipped counts.
- UI: **Tools → Compare two folders** (two selected folders, or the left and right pane folders when nothing
  is selected). Changed text files open the line + word diff.

## Tests

`WordDiffTest` (changed-word isolation, one-sided inserts, similarity, huge-line guard, lossless tokens) and
`FolderCompareTest` (every status, newer side, quick vs exact trade-off, ignore filter, copy-missing never
overwrites, bad input).
