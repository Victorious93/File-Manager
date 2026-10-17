# Phase 10: gap fill

The audit of main (Phases 1–9) found these already present and solid: trash with restore,
bookmarks, batch operation queue with undo, storage analyzer, duplicate finder. Phase 10
adds or strengthens the rest.

## Fast indexed search
`core/search/FileIndex`: one background walk builds flat arrays of lower-cased names and
relative paths. Each query is then an in-memory scan: about 24 ms over 50k files on the
build box. The old `FileSearch` walked the whole tree on every keystroke.
- Query syntax: space-separated terms are AND-ed (`beach plan`). `*.jpg` or `.jpg` filters
  by extension. `/photos` matches against the relative path.
- The Search tab debounces (200 ms) and cancels the previous query while you type.
- The index is invalidated after every file operation, undo, restore and app resume, and
  it expires after 10 minutes. `.victorious-trash` and `.git` are skipped.

## Trash on the same volume
Deleting from shared storage moves the item to `<volume>/.victorious-trash/items/<id>`.
That is a rename, so a 4 GB video no longer gets copied into app storage. A `.nomedia`
file keeps the trash out of the gallery. The trash index (JSON) stays private in
`filesDir/trash`.
- If you trash the trash folder itself, or a folder that contains it, the item falls back
  to the private trash.
- Note: payloads on shared storage survive uninstall. Empty the trash first if that matters.

## Themes
Settings → Appearance:
- Modes: System / Light / Dark / AMOLED black.
- Dynamic color (Material You, Android 12+).
- Accent presets (Blue, Teal, Purple, Orange, Pink, Green) when dynamic color is off.

Choices persist in the `fm_theme` prefs. Scheme selection is a pure function covered by unit tests.

## Tablet / foldable layouts
Layout is driven by window size (`BoxWithConstraints`), not screen size, so split-screen
and foldables adapt too.
- At 600 dp and wider, the bottom navigation becomes a NavigationRail.
- The two file panes sit side by side whenever the pane area is at least 520 dp, or in
  landscape at 400 dp or more. Otherwise they stack.
- This also clears the lint `ConfigurationScreenWidthHeight` warning.

## Compare two APKs
Tools → Compare two APKs (select 2 `.apk`). It diffs:
- manifest fields (package, versions, SDKs, app label, debuggable, …),
- permissions added and removed,
- DEX classes added and removed, plus changed classes (method signatures, field counts),
- zip entries by CRC and size: resources, assets, native libs. Signature files are flagged.

"Save report" writes a text file into the focused folder.

## Smali tools (optional deobfuscation helpers)
Tools → Smali tools (select one `.apk`/`.dex`, optionally plus its `mapping.txt`):
- Search classes, methods and `const-string`s. Mapping-aware: finds by the original or the
  obfuscated name.
- String decryption hints: static `String f(String|byte[]|int|char[])` methods ranked by
  call sites and by encoded-looking string arguments. Heuristics only; nothing is executed.
- Export smali: with a mapping, class paths and class/field/method references are
  rewritten to their original names. Output paths are confined to the output folder, so a
  hostile mapping with `../` cannot escape it.

## RAR
See `ARCHIVES.md`: list and extract RAR4/RAR5/solid/password archives via junrar, under
the UnRAR licence (extraction only).

## Big folders
`NioFileIo.list` uses one `DirectoryStream` plus a single `readAttributes` per child. The
sort key (folders first, then case-insensitive name) is precomputed, not re-lowercased on
every comparison.
- Rows are a keyed `LazyColumn`, so only visible rows compose.
- Listing and sorting always run on `Dispatchers.IO`.
- One shared `DateFormat` replaces one per row.

Benchmark (`FileIndexAndBigFolderTest.list50kFilesAndIndexSearchAreFast`, build box, JDK 17):

| step | time |
|---|---|
| list + sort 50,000 files (median of 3) | 119 ms and 163 ms (two runs) |
| index build (50k) | 240–249 ms |
| index search ("photo", 7,143 hits) | 23–24 ms |

The test fails if listing takes over 8 s or searching over 2 s. Those limits are generous
on purpose: the test is a regression guard, not a micro-benchmark. On-device numbers are
not measured yet.
