# Victorious Bridge file access (ShizukuV)

Victorious Bridge (`Victorious93/ShizukuV`, package `ai.victorious.bridge`) is the local privileged-access service.
File Manager can use it for **bounded, scoped** file operations through `core.bridge.BridgeFileIo`.
This is separate from the existing upstream-Shizuku route (`priv/ShizukuController`), which is left unchanged.

## Status (honest)

| Item | State |
|---|---|
| `BridgeFileIo` (chunking, cancel, partial-copy cleanup, error mapping) | **Implemented, JVM-tested** against an in-memory stand-in (9 + 3 tests) |
| Contract match with the Bridge (`bridge-contract-v1.json`) | **Tested** on both sides |
| Gateway on Android (`VictoriousBridge.execute`) | **Not written here** - exact handoff below |
| UI to pick the Bridge as a backend | **Not built** |
| Run against a real Bridge on a device/emulator | **Not done** |

## What it guarantees and what it does not

- The *service* enforces the approved folders, `..` rejection, symlink refusal and protected locations. File Manager
  adds no authority and has no fallback: if the Bridge says `PATH_OUT_OF_SCOPE`, `DENIED`, `REVOKED` or `EXPIRED`,
  the operation fails with that category (`BridgeFileException.isOutOfScope` / `isAccessLoss`). It never retries
  through a shell or root.
- Out-of-scope paths are reported as errors, **not** as "does not exist", so the UI cannot be fooled into thinking a
  protected folder is empty.
- ADB (shell) mode **cannot read other apps' private data**; the Bridge refuses `/data/data`, `/data/user*`,
  `/data/system` etc. even if asked. Root mode is bound by SELinux and OEM rules.
- Large files: at most 1 MiB per Bridge call, so copies stream chunk by chunk (memory stays bounded), report progress,
  stop promptly on cancel and delete the half-written destination.
- The Bridge has no rename/copy/move primitive and only deletes empty folders. `rename`/`move` are therefore
  copy + delete (**not atomic**; the source is kept if the copy fails) and `deleteRecursively` removes children first.
- Text view refuses files over 8 MiB rather than loading them.

## Handoff: wiring it into `app`

1. Dependency: publish/consume ShizukuV's `:bridge-sdk` AAR (see ShizukuV `docs/INTEGRATION.md`) or vendor its source.
2. Manifest: declare the SDK provider, authority `${applicationId}.victoriousbridge`, `exported=true`,
   `multiprocess=false`, `android:permission="android.permission.INTERACT_ACROSS_USERS_FULL"`. Do **not** add the
   legacy permission; File Manager needs only scoped file access.
3. Gateway (app module):
   ```kotlin
   class SdkGateway : BridgeFileGateway {
       override fun call(operation: String, args: Map<String, Any>): BridgeReply {
           val r = VictoriousBridge.execute(operation, args, timeoutMs = 60_000)
           return BridgeReply(r.ok, r.errorCode?.name, r.message, r.data)
       }
       override fun isCancelled() = currentOperationCancelled()   // the existing cancel flag of the operation queue
   }
   ```
4. Request access once, with the folders File Manager wants approved:
   `VictoriousBridge.requestAccess(code, setOf(Capability.FILES_READ, Capability.FILES_WRITE), listOf("/storage/emulated/0"))`;
   the owner may narrow it and picks the duration in the Bridge prompt.
5. Offer `BridgeFileIo` as one more `FileIo` choice (alongside `EscalatingFileIo`); show negotiated mode and approved
   folders from `VictoriousBridge.negotiate()`.

Not verified: all of step 3-5 on a device. The `core` tests do not need Android.
