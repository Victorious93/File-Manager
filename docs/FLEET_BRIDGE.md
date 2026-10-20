# VictorFiles fleet bridge (EXTERNAL_NODE)

VictorFiles is a standalone app. It can optionally act as an **EXTERNAL_NODE**
for DroidCommand using the RootForge-style Messenger + JSON envelope
(see `Victorious93/rootforge-os` `docs/DROIDCOMMAND_INTEGRATION.md`).
It is **not** a BOUND_COMPANION: no AIDL contract, no `BIND_*` signature
permission, no DependencyGuard, and DroidCommand AI is never required.

## Transport

| Item | Value |
|---|---|
| Service | `ai.victorious.filemanager.fleet.FileManagerBridgeService` (exported) |
| Bind action | `ai.victorious.filemanager.action.BRIDGE` — `onBind` returns `null` for any other (or missing) action |
| Package | `ai.victorious.filemanager` (debug: `ai.victorious.filemanager.debug`) |
| Request | `Message.what = 1`, `data["request"]` = JSON line, `replyTo` required |
| Response | `Message.what = 2`, `data["response"]` = JSON line |

## Envelope (protocol_major 1)

Request:
```json
{"protocol_major":1,"request_id":"r1","target_node_id":"fm-…","operation":"filemanager.status","timeout_ms":10000,"parameters":{}}
```
`target_node_id` may be empty; if set it must match this node.

Response:
```json
{"protocol_major":1,"request_id":"r1","node_id":"fm-…","ok":true,"result":{…},"error":null}
```
On failure `ok:false` and `error = {"category": "...", "message": "..."}`.

## Operations

| Operation | Tier | Notes |
|---|---|---|
| `filemanager.capabilities.get` | read | `protocol_major`, `node_id`, `fleet_class:"external_node"`, `live:false`, `mode:"stub"`, `dca_required:false`, `enabled`, `caller_package`, `cert_pinning`, `operations[]` |
| `filemanager.status` | read | app id/version, `fleet_class`, `enabled`, `live:false`, `mode:"stub"`, `dca_required:false` |
| `filemanager.list` | read (stub) | Never exposes device files; returns empty `entries` |

Nothing in this bridge claims LIVE control.

## Gating order

1. Envelope: empty/malformed → `malformed_request`; wrong `protocol_major` → `protocol_mismatch`; wrong `target_node_id` → `bad_parameters`.
2. **Caller identity** → `unauthorized_caller` (applies to every op, including capabilities/status, and regardless of the toggle).
3. **External control toggle** → `external_control_disabled` for every op **except** `filemanager.capabilities.get` and `filemanager.status`, which still answer read-only with `enabled:false` so the hub can show "installed but disabled". Decision: these two ops reveal no user data, and answering them lets DroidCommand prompt the user to enable control instead of guessing.
4. Unknown op → `unknown_operation`.

## Error categories

`malformed_request`, `protocol_mismatch`, `bad_parameters`, `unauthorized_caller`,
`external_control_disabled`, `unknown_operation`, `operation_failed`.

## Caller identity

Messenger handles messages on a `Handler`, outside the binder transaction, so
`Binder.getCallingUid()` there would return our own uid. The service therefore
uses `Message.sendingUid` (kernel-attested, API 22+; `-1` → rejected), then
`PackageManager.getPackagesForUid()` and each package's signing certificates
(`GET_SIGNING_CERTIFICATES` on API 28+, `GET_SIGNATURES` below), hashed with
SHA-256 (lowercase hex). A uid is allowed if any of its packages is allowlisted
(and, if pinned, has a matching cert).

Allowlist: `ai.droidcommand.app`, `ai.droidcommand.app.debug`.

### Certificate pinning (optional, build-time)

No hashes are hard-coded. Pins come from the Gradle property
`fleetCallerCertPins` and land in `BuildConfig.FLEET_CALLER_CERT_PINS`:

```
./gradlew :app:assembleRelease \
  -PfleetCallerCertPins="ai.droidcommand.app=<sha256hex>;ai.droidcommand.app.debug=<sha256hex>"
```

Format: `pkg=hash[|hash2];pkg2=hash`. Colons and case are ignored. A package
without a pin is accepted by package name only (the default for all builds
today); `capabilities.get` reports `cert_pinning` so this is visible. Obtain
the hash with `apksigner verify --print-certs <dca.apk>` (SHA-256 digest).
Release builds should set pins once DroidCommand's signing cert is known.

## Toggle

Settings tab → **Allow external control**. Persisted in SharedPreferences
`fleet` / `external_control_enabled`, default **false**.

---

## Fleet remote-control envelope v1 (FROZEN, protocol 1.1)

Implements DroidCommand AI `docs/specs/REMOTE_CONTROL.md` (frozen v1) and
`docs/specs/STATUS_TRAY.md` (`node.status.get`). `BRIDGE_PROTOCOL_MAJOR = 1`,
`BRIDGE_PROTOCOL_MINOR = 1` (minor is advertised in `capabilities.get` only; no
required `protocol_minor` field). Code: `core/.../fleet/EnvelopeExecutor.kt`,
`Envelope.kt`, `NodeStatus.kt`, `RemoteOps.kt`. The legacy `filemanager.*` ops
above are unchanged.

Same Messenger transport and outer `BridgeRequest`. The envelope fields are
**flat camelCase keys at the top of `parameters`**; `requestId` **is** the
outer `request_id` (not repeated):

```json
{"protocol_major":1,"request_id":"6f1a…","target_node_id":"fm-…","operation":"remote.command",
 "parameters":{"targetNodeRef":"fm-…","actionRef":"node.status.get","args":{},
   "nonce":"0123456789abcdef0123456789abcdef","timestampMs":1760920000000,"ttlMs":120000,
   "destructive":false,"trigger":{"sourceNumber":"+15555550100","matchedKeyword":true,"rawExtras":{}}}}
```

| Field | Notes |
|---|---|
| `targetNodeRef` | Must be empty or this node's id, else `not_found` |
| `actionRef` | Op to run (`remote.command`); `macro.step` may use `op` instead |
| `args` | Object; `params` accepted as an alias |
| `nonce` | 128-bit: 32 hex chars, or base64/base64url of 16 bytes |
| `timestampMs` | Epoch ms, required |
| `ttlMs` | Default 120000, max 300000 (`invalid_parameters` outside 1..300000) |
| `destructive` | **Advisory only**, never used for gating; the node classifies ops itself |
| `trigger{}` | **Informational only**, never used for auth |
| `macro.step` | + `macroId`, `stepIndex` (≥0), `op`, `params`, `onError` (`abort`\|`continue`, default abort) |
| `macro.run` | + `macroId`, `steps[{op, params, onError, timeoutMs}]` (1..32), `dryRun` |

### Ops

| Op | kind | remoteAllowed | requiresConfirm | Notes |
|---|---|---|---|---|
| `capabilities.get` | read | true | false | `{protocolMajor:1, protocolMinor:1, nodeId, packageName, connectionClass, enabled, callerPackage, ops[{name,kind,remoteAllowed,requiresConfirm}]}`. Answers even when external control is off. |
| `node.status.get` | read | true | false | One `NodeStatusSnapshot` (below) |
| `remote.command` | action | true | false | Runs `actionRef` on this node |
| `macro.step` | action | true | false | One step; echoes `macroId`/`stepIndex`/`onError` + `result` |
| `macro.run` | action | true | false | Sequential, per-step `{index, op, onError, status: ok\|error\|skipped\|validated, result?, error?}`; `status: completed\|completed_with_errors\|aborted`; `rollback:false` (never promised) |
| `filemanager.status` | read | true | false | Legacy status object, reachable via `remote.command` |
| `media.play`, `media.pause`, `media.toggle`, `media.next`, `media.prev`, `media.seek{positionMs}`, `media.seekBy{deltaMs}`, `media.volume{level}`, `media.state` | action (state: read) | true | false | Media Pro (`docs/MEDIA_PRO.md`). Idle → `no_session`. `capability_off` only when no player is wired. |

`dryRun:true` runs every gate for every step (exists, remoteAllowed, capability on,
confirmation) **without executing** anything; runnable steps report `validated`.
`onError:abort` stops at the first failing step and reports the rest as `skipped`
(partial result, no rollback); `continue` keeps going. Meta-ops cannot be nested.

### Gating order

1. **Caller identity = package + signing cert** (`Message.sendingUid` → `getPackagesForUid` →
   SHA-256 of signing certs). Not allowlisted / unresolved → `not_paired`; allowlisted but pin
   mismatch → `unauthorized`. **No envelope field or `trigger.*` is ever consulted.**
2. Envelope parse → `invalid_parameters`; wrong target → `not_found`; op outside the caller's
   scope → `unauthorized`.
3. External control (Settings, **OFF by default**) → `external_control_disabled` (all v1 ops
   except `capabilities.get`).
4. Freshness: `now > timestampMs + ttlMs` **or** `|now − timestampMs| > 120000` → `expired`.
5. Replay: seen-nonce cache keyed by `(sourceNodeRef, nonce)` where `sourceNodeRef` =
   authenticated `package#certSha256`; entries kept **10 minutes** (constructor refuses less);
   duplicate → `replay_detected`. Only envelopes that passed 1–4 consume a nonce. If the cache
   is full of live nonces the request fails closed (`internal`) rather than evicting.
6. Node policy: unknown op → `not_found`; `remoteAllowed:false` → `unauthorized`; capability off
   → `capability_off`; `kind:destructive` or `requiresConfirm` → `confirmation_required` unless
   the user confirmed **on this device**. The default `RemoteConfirmation.NEVER` never approves
   (no remote confirmation UI is wired yet), so remote triggers are never auto-approved.
   Unhandled exceptions → `internal` (message not leaked). Unknown top-level ops fall through to
   the legacy path (`unknown_operation`); `unsupported_op` is returned for non-v1 ops reaching
   the v1 executor.

Error codes: `invalid_parameters`, `not_paired`, `not_found`, `internal`, `unauthorized`,
`replay_detected`, `expired`, `external_control_disabled`, `confirmation_required`,
`unsupported_op`, `capability_off`.

### `node.status.get` (STATUS_TRAY.md)

Result is one `NodeStatusSnapshot`:

```json
{"nodeId":"fm-…","packageName":"ai.victorious.filemanager","connectionClass":"EXTERNAL_NODE",
 "updatedAtMs":1760920000000,
 "domains":[{"name":"node_health","state":"ok","detail":"alive; external control on"},
            {"name":"other","state":"ok","detail":"shizuku: connected"}]}
```

Shizuku → `other`: GRANTED `ok`, DENIED `degraded`, NOT_RUNNING `down`, NOT_INSTALLED `off`,
unknown `unknown`. Optional `args.nodeId` must match this node (`not_found`). The codec
**refuses** (`invalid_parameters`, never silently strips) any `detail` over 200 chars, containing
a forbidden term (`password`, `token`, `secret`, `apiKey`, `codeWord`, `whitelist`,
`signingKey`, `privateKey`, …) or anything path-like (`/`, `\`, drive letters, `file:`,
`content:`, common file extensions). **Snapshots never contain file paths or file contents.**

### Caller allowlist

| Package | Pin | Scope |
|---|---|---|
| `ai.droidcommand.app`, `ai.droidcommand.app.debug` | optional build-time pin (`fleetCallerCertPins`) | all ops |
| `com.victorious.keyboard.debug` | **built-in, mandatory**: `47:1E:AD:6B:3F:96:7E:F3:C8:63:EF:49:08:74:8B:3B:E3:63:F9:1D:75:0B:FD:B1:E9:28:05:BC:F1:6B:AF:4F` (cannot be overridden by the build property) | **`media.*` only** (incl. legacy ops: denied); currently every `media.*` op is `capability_off` |
| `com.victorious.keyboard` (release) | unpinned | **denied** (`not_paired`) until a release cert is pinned |

Note: because the keyboard is scoped to `media.*`, it cannot call `capabilities.get` or
`node.status.get` either. The keyboard pin also makes legacy `cert_pinning` report `true`.

### `config.export` / `config.import` (UNIFIED_BACKUP.md, FROZEN)

Standalone top-level ops (same envelope fields). Code: `core/.../fleet/ConfigBackup.kt`.

| Op | kind | remoteAllowed | requiresConfirm |
|---|---|---|---|
| `config.export` | action | **false** | false |
| `config.import` | action | **false** | **true** |

Because `remoteAllowed` is false (spec default; only an explicit owner decision —
`BridgeExecutor(configRemoteAllowed = true)` — may raise it), **every bridge caller gets
`capability_off`**. Note: this PR maps `remoteAllowed:false` to `capability_off` for all ops
(per UNIFIED_BACKUP §6), replacing the earlier `unauthorized`.

- **Export** → one `UnifiedBackupEntry {packageName, schemaVersion:1, ciphertext | clearPayload}`.
  Payload schema v1 = `{"preferences":{key:primitive}, "bookmarks":[{id,path,label,createdAtEpochMs}]}`.
  Preferences and bookmarks only — never file contents. Dropped: the external-control switch,
  any key/value matching the secret list (`password`, `token`, `apiKey`, `secret`, `bearer`,
  `cookie`, `credential`, `oauth`, `twilio`, `authToken`, `numberPool`, `codeWord`, `whitelist`,
  `privateKey`, `signingKey`, `keystore`, …), and secret-bearing bookmark paths (secure folder /
  vault, `cloud`, `.ssh`, `Android/data`, app-private prefixes, secret-named paths). The final
  payload is re-scanned and refused (`invalid_parameters`) if anything slipped through.
  Ciphertext: `AES/GCM/NoPadding`, 12-byte IV, 128-bit tag, `v1.<b64url iv>.<b64url ct>`,
  AAD `packageName|schemaVersion|sourceNodeRef`. `clearPayload:"true"` is local-dev only.
  `packageName` arg other than this app → `not_found`.
- **Import** args: `bundle` (JSON string), `conflictPolicy` `skip` (default) | `overwrite` |
  `merge-by-id`, `dryRun` (`"true"`, or envelope top-level). Order: version (fail closed) →
  duplicate package → wrapping/ciphertext-vs-clear rules → decrypt (failure = `internal`) →
  secret scan → dry-run report (no writes, no confirmation needed) → apply → **external control
  forced OFF**. Non-dry-run always needs on-device confirmation (`confirmation_required`;
  default `RemoteConfirmation.NEVER`). Ids: preferences by key, bookmarks by `id`.

**App wiring:** `AppConfigStore` (SharedPreferences `fm_theme` + `BookmarkStore`; privilege, AI,
cloud, connection and fleet prefs are never included) and `BackupKeystore` (AES-256-GCM key
`victorfiles_config_backup_v1` in the Android Keystore, `wrapping: "android-keystore"`; the alias
is not in the file, so an encrypted export restores on this device/install only). Bookmarks under
the app's data dirs are dropped. **Settings → Backup & restore**: *Export to file* (SAF) and
*Import from file* → dry-run preview → choose `skip` / `overwrite` / `merge-by-id` → *Confirm
import*. Over the bridge both ops remain `remoteAllowed:false` → `capability_off`.

### On-device confirmation (held requests)

`ConfirmationBroker` + `FileManagerBridgeService` (`BridgeExecutor.handleAsync`). A
`remote.command` / `macro.step` (or a remote-allowed `config.import`) whose op is destructive or
`requiresConfirm` is **held** after every other gate (identity, scope, external control, expiry,
replay — so its nonce is already consumed) and the reply is deferred:

- A high-priority notification with a full-screen intent opens `RemoteConfirmActivity` (Compose):
  caller app, op, actionRef, a **redacted params summary** (secret-named keys/values →
  `[redacted]`, path-like values → `[path hidden]`, long text → length only, max 8 lines) and
  **Approve / Deny** with a countdown.
- Deadline = the envelope's own `timestampMs + ttlMs`; at the deadline it is **denied**
  (`confirmation_required`, "timed out"). Deny → `confirmation_required` ("denied on device").
  Approve → the op runs once and the real result is returned.
- Never auto-approved. A decision is single-use and must carry the held request's nonce; a late
  approval counts as a timeout. At most 8 requests are held; beyond that → `confirmation_required`.
- `macro.run` steps are **not** held: a confirm-needing step still reports
  `confirmation_required` per step. The synchronous `handle()` path never holds.
