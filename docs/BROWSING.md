# Browsing essentials (Phase 10)

- **Sort / filter / hidden** — each pane has a filter box and a *Sort* menu: name (natural order, `file2` before `file10`), size, modified, type; ascending/descending; folders first; show hidden. Applies to both panes and only changes display. **All** selects everything currently visible (respects the filter).
- **Properties** — long-press a file or folder → *Properties*. Files show MD5 / SHA-1 / SHA-256 and a paste-to-compare field; folders show recursive file/folder counts and total size (symlinks are counted, never followed). Local storage only; remote (network) paths are not supported.

Logic: `core/listing/ListingOptions.kt`, `core/props/FileProperties.kt`; tests in `ListingOptionsTest`, `FilePropertiesTest`.
