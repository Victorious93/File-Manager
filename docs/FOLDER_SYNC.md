# Folder sync

One-way and two-way sync between:

- **Local folders**
- **SMB / SFTP / WebDAV** (and S3 via the same `RemoteBackend` stack) using a saved connection id
- Cloud providers that expose a `RemoteBackend` through the existing factory

## Features
- Conflict policies: **keep both**, **newest**, **ask** (skip until user resolves)
- **Dry-run preview** lists planned copies/deletes/conflicts
- **WorkManager** periodic jobs for local↔local (off until you schedule)
- **Deletes → trash** for local sides (`TrashStore`); remote deletes are direct

## Code
- `core/.../sync/FolderSync.kt` — planner/executor + `MemorySyncFs` / `RemoteSyncFs`
- `app/.../sync/LocalSyncFs.kt`, `FolderSyncWorker.kt`
- Tools → **Folder sync**

## Tests
`FolderSyncTest` (4 cases).
