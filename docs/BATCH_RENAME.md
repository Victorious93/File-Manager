# Batch rename

Select two or more items in a pane and tap **Rename**. (One item still opens the plain rename dialog.)

Rules apply to each base name in this order: find/replace (literal or regex, optional case-insensitive; regex replacements support `$1`) → case (keep/upper/lower/title) → template. Template tokens: `{name}` (result so far), `{n}` (counter with start/step/zero-pad), `{ext}`. The extension is kept unless you set a new one (blank keeps it).

The dialog shows a live preview. **Rename** is enabled only when no item is invalid (bad name, bad regex) or conflicting (duplicate target, or a name already used by an item outside the batch). Items whose name would not change are skipped.

The whole batch is one queue operation: swaps and chains (`a→b`, `b→a`) work via temporary names, a mid-batch failure rolls back, and **Undo** reverts the entire batch. Renames stay within each file's folder; nothing is moved.

Logic lives in `core/rename/BatchRename.kt` (planner) and `OperationQueue.enqueueBatchRename`; tests in `BatchRenameTest`.
