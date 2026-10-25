# Polish: Notes vault + App Manager profiles

## SAF tree folder picker
Notes vault home screen → **Pick folder (Storage Access Framework)**. Grants a persistable
tree URI (`takePersistableUriPermission`). Primary-storage trees also map to a filesystem path
via `SafTreePaths`; otherwise the vault uses `SafNoteIo` (DocumentFile, relative paths).

## Force-directed graph
`ForceDirectedLayout` (core) runs a Fruchterman–Reingold-style simulation. The Notes graph view
steps the layout while visible; nodes are **draggable** (pinned while dragging).

## App Manager profiles
Fuller Profiles tab: edit name/description, add force-stop/disable/enable steps, reorder (↑/↓),
delete steps, and set a **schedule** (`off` / `once` / `daily` / `weekly`). Schedules use
WorkManager (`ProfileScheduleWorker`); **off by default**. Manual Apply still confirms first.

## Tests
`ForceDirectedTest`, `ProfileEditTest`.
