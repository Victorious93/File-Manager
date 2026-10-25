# Phase 8 — Network (FTP/SFTP/SMB/WebDAV + Wi-Fi transfer)

Built from scratch. Open-source clients only (licenses allow redistribution).

## Saved connections

| Protocol | Library | License |
|----------|---------|---------|
| FTP / FTPS | Apache Commons Net | Apache-2.0 |
| SFTP | mwiede/jsch | BSD-style |
| SMB 2/3 | smbj (Hierynomus) | Apache-2.0 |
| WebDAV | OkHttp + PROPFIND/MKCOL/MOVE | Apache-2.0 |

- Add / edit / remove under **Tools → Network connections**.
- Browse opens a dual-pane tab at `/remote/{id}/…` (virtual path).
- Copy / move between local and remote uses the existing `OperationQueue` via `CompositeFileIo`.
- Passwords and private keys are stored in **EncryptedSharedPreferences** (Android Keystore). Connection metadata (host, user, port) is in ordinary prefs without secrets. Secrets are never logged (`CredentialRedactor`).

## Wi-Fi file transfer

- **Tools → Wi-Fi file transfer** starts a NanoHTTPD server bound to the device’s **LAN IPv4** (not a wild `0.0.0.0` bind in the UI path).
- Shows URL + QR (ZXing) and an optional PIN.
- PC browser can list, download, and upload into the focused folder.
- Stops when the user taps Stop.
- **PIN guessing is throttled per client.** The PIN is only four digits, so after 5 wrong PINs in a row a
  client (identified by IP address) gets `429 Too Many Requests` with a `Retry-After` header for 60 s,
  doubling on each repeat up to 15 min. While locked out, even the correct PIN is refused.
  - Only a *wrong* PIN counts as a guess. Opening the page with no PIN is not a guess, and a correct PIN
    clears that client's record.
  - Lockouts are per client, never server-wide, so another device on the network cannot lock the owner out.
  - The state is in memory; each time the server is started it gets a new PIN and a clean slate.
- File and folder names are percent-encoded in links and in the upload form's action, so names containing
  `%`, `?`, `#` or spaces download and upload correctly.

## Cloud drives

Google Drive / Dropbox need OAuth client IDs we do not have. **Deferred** — UI notes the placeholder; no client IDs or secrets are committed.

## Fleet / signing

Unchanged: `EXTERNAL_NODE`, no DependencyGuard / companion packages / fleet `BIND_*` / shared fleet signing key. Debug signing still uses the File-Manager-only key from `docs/SIGNING.md`.

## Unit tests (`:core`)

- Remote path mapping (`/remote/{id}/…`)
- Connection URI parsing
- Credential redaction
- `CompositeFileIo` list + download via in-memory remote
- Wi-Fi router PIN auth + download/upload
