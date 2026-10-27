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

## Remote and scheduled sync
- Either side can be local or any saved connection (SFTP/FTP/SMB/WebDAV/cloud), including remote↔remote.
- Jobs (`core/.../sync/SyncJobs.kt`) are stored as JSON with a per-path snapshot (`PairStamp`), so two-way
  sync against remotes that cannot keep the source mtime does not ping-pong. Old local-only job entries still load.
- `FolderSyncWorker` runs a job as a periodic WorkManager task with constraints from
  `SyncRunConstraints.forJob`: network (any / unmetered for Wi-Fi only; always required for remote jobs),
  optional charging, battery-not-low for remote jobs. Transient errors retry with exponential back-off (max 3).
- Tests: `SyncJobTest` (local→remote, no ping-pong, remote→local with trash, remote→remote, store/legacy, constraints).
