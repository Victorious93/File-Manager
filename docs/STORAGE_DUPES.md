# Storage analyzer and duplicate finder

Both written from scratch for this app (`core/.../storage/StorageScan.kt`, `core/.../dupes/DuplicateFinder.kt`;
UI in `app/.../ui/StorageDupesScreens.kt`).

## Storage analyzer

- **One walk.** `StorageScan.scan` uses `Files.walkFileTree`, so each entry's size and type come from the directory
  read (no extra `stat` per file as `File.length()` / `isFile` need). It builds a tree of every folder's recursive
  size and file count. Drilling down (`StorageScan.breakdown`) reads only the folder's own file list; sub-folder sizes
  come from the tree, so there is no second walk. The old per-folder `StorageAnalyzer.analyze` is kept as a fallback.
- **Also collected in the same pass:** the 100 largest files (min-heap) and bytes / file counts per `FileCategory`
  (images, videos, audio, documents, archives, APKs, other).
- **Safe:** symlinks are never followed or counted; unreadable folders are counted as "skipped" and the scan goes on;
  cancel is checked every folder and every 1,024 files (leaving the screen cancels); progress is reported at most
  every 150 ms.
- Chart colours come from the theme (fleet cyan / magenta / violet), no fixed light colours.

## Duplicate finder

Stages, each only for files that could still be equal:

1. **Size**, from one `walkFileTree` listing (hidden folders and files skipped unless "Include hidden folders";
   optional minimum size).
2. **Quick check:** SHA-256 over the size + first 16 KB + last 16 KB (`RandomAccessFile`, two seeks). Files of
   32 KB or less are read whole here and need no stage 3. Comparing both ends catches videos and photos that share a
   header but differ at the end.
3. **Full check:** full SHA-256 for files that still match, on a small thread pool (cores, max 4).

A `HashCache` keyed by path + size + modified time keeps full hashes for the rest of the app session, so a second
scan only reads files that changed. Keep-rules: shortest path (default), newest, oldest. "Trash N" uses the app
trash, so it can be undone.

## Tests

`StorageScanTest` (folder sizes match `StorageAnalyzer`, instant breakdown, largest files, by-type totals, symlinks
not followed, progress, cancel), `DuplicateFinderFastTest` (same edges / different middle, cache reuse and
invalidation, hidden + min size, 1 vs 4 threads give the same groups, keep newest / oldest, cancel), and the
existing `StorageAnalyzerTest` / `DuplicateFinderTest`.
