# VictorFiles fleet bridge (EXTERNAL_NODE)

VictorFiles is a standalone app. It can optionally act as an **EXTERNAL_NODE**
for DroidCommand using the RootForge-style Messenger + JSON envelope
(see `Victorious93/rootforge-os` `docs/DROIDCOMMAND_INTEGRATION.md`).
It is **not** a BOUND_COMPANION: no AIDL contract, no `BIND_*` signature
permission, no DependencyGuard, and DroidCommand AI is never required.

## Transport

| Item | Value |
|---|---|
| Service | `ai.victorious.victorfiles.fleet.FileManagerBridgeService` (exported) |
| Bind action | `ai.victorious.victorfiles.action.BRIDGE` — `onBind` returns `null` for any other (or missing) action |
| Package | `ai.victorious.victorfiles` (debug: `ai.victorious.victorfiles.debug`) |
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
