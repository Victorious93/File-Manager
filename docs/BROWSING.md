# Browsing essentials (Phase 10)

- **Sort / filter / hidden** — each pane has a filter box and a *Sort* menu: name (natural order, `file2` before `file10`), size, modified, type; ascending/descending; folders first; show hidden. Applies to both panes and only changes display. **All** selects everything currently visible (respects the filter).
- **Properties** — long-press a file or folder → *Properties*. Files show MD5 / SHA-1 / SHA-256 and a paste-to-compare field; folders show recursive file/folder counts and total size (symlinks are counted, never followed). Local storage only; remote (network) paths are not supported.

Logic: `core/listing/ListingOptions.kt`, `core/props/FileProperties.kt`; tests in `ListingOptionsTest`, `FilePropertiesTest`.

## MT/NP Manager-style tools (Phase 10b)

- **Edit inside archives** — open a ZIP/JAR/APK, tap an entry → *Edit text*, *Rename* or *Delete* without extracting. The archive is rewritten to a temp file and swapped in only on success. `resources.arsc`, native libs and already-STORED entries stay STORED and aligned. v1 signature files are dropped (the edit invalidates them) — **sign the APK again** before installing. Password-protected ZIPs and non-ZIP formats (7z/TAR) are read-only. Logic: `core/archive/ZipEditor.kt`.
- **Search in files** — Search tab → *File contents*: literal or regex, optional name globs (`*.smali, *.xml`), scoped to the focused pane's folder; binary and >4 MB files are skipped. Logic: `core/search/ContentSearch.kt`.
- **Permissions** — Properties → mode field (`755` or `rwxr-xr-x`), optionally recursive. Local storage only; uses Java POSIX permissions, so it will not work on filesystems without them (e.g. FAT/sdcard emulation) or on root-only paths (no root escalation here).
