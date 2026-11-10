# My PC (SFTP to your own PC), phase 1

**My PC** opens your Ubuntu PC's files in either pane of the browser, over SFTP. It is built for
one owner and their own machines: every PC is locked to its SSH host key, the phone logs in with
its own hardware-backed key, and big copies run in the background and pick up where they stopped.

It follows the fleet spec **victorpc v1**, `docs/specs/VICTORPC_PAIRING.md` in VictorSuite (merged
in VictorSuite #99). Phase 1 is the File Manager side on its own. Pairing by QR through VictorSuite
comes in phase 3 (see below).

## What you need

- An Ubuntu 22.04 or 24.04 PC (other Linux works too) running OpenSSH server (`sudo apt install openssh-server`).
- The phone on the same Wi-Fi, **or** the official Tailscale app connected on the phone and the PC, for
  access away from home.

## Add a PC

1. Tools → **My PC** → **+**.
2. Fill in:
   - **Name**: anything, e.g. *Desk*.
   - **PC user**: your Linux login, e.g. `don`.
   - **Hosts**: one or more addresses, LAN first, separated by commas. For example
     `192.168.1.42, desk.local, desk.tail1234.ts.net`. The app tries them in this order.
   - **SSH port**: normally 22.
   - **Host key** (optional): the PC's key in the form `ssh-ed25519:SHA256:…`. On the PC run
     `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` and copy the `SHA256:…` part, prefixed with
     `ssh-ed25519:`. Several keys can be separated with `;`.
   - **Start folder** (optional): where the pane opens. Empty means your home folder.
3. Tap **Save**. The app creates a new key for this PC and shows it straight away.

## Let the phone log in (authorized_keys)

The app makes **its own** key for each PC: an EC P-256 key inside the phone's Android Keystore,
named `victorpc.<profileId>.filemanager`. The private half cannot be copied out of the phone.

Tap **Key** on the PC's card. It shows one line like:

```
restrict ecdsa-sha2-nistp256 AAAAE2VjZHNh… victorpc:<deviceId>:filemanager
```

Tap **Copy line**, then on the PC add it to `~/.ssh/authorized_keys` of that user (one line, then save).
`restrict` turns off port forwarding and agent forwarding for this key; SFTP and commands still work.
The `victorpc:…:filemanager` comment lets you find and remove exactly this app's key later.

## Host keys: pinned, and a change is a hard stop

- If you typed the host key when adding the PC, it is pinned from the start.
- If you left it empty, the first **Open in pane** stops and shows the key the PC presented. Compare
  it with `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` on the PC. Tap **It matches, pin it**
  only if it matches. Nothing is sent to the PC before you confirm.
- After that, if the PC ever answers with a different key, the app **blocks the connection** with a
  red warning showing the pinned and presented keys. There is no "connect anyway", it does not try
  the next address, and background transfers stop without retrying. If you reinstalled the PC
  yourself, remove it and add it again.

The pin format is the fleet one: `<keytype>:SHA256:<base64 without padding>`, compared as exact text.

## Browse

Tap **Open in pane**. The PC opens in the focused pane at your start folder, under
`/remote/mypc-<id>/…`. It also appears in the `/remote` list. You can:

- list folders and move around (breadcrumbs work as usual),
- **rename**, **delete** and **create folders** on the PC,
- use the other pane for the phone, so copying is one tap.

## Copy and move (background transfers)

With a phone folder in one pane and a PC folder in the other, select files or folders and tap
**Copy** or **Move**. (With one pane, use Copy/Move then **Paste here**.)

- Each item becomes a background job. Android shows a notification with bytes done and a
  **Cancel** button; Tools → My PC lists the jobs too.
- Data is written to `<name>.partial`. If Wi-Fi drops or the phone sleeps, the job retries with
  back-off and **resumes from where the `.partial` file ends**.
- When it finishes, the app checks the **size**, then the **SHA-256** on both ends (the PC side
  uses `sha256sum`). Only then is `.partial` renamed to the real name. If a resumed file fails the
  hash, it is redone once from the start; if it fails again it is discarded.
- **Move** deletes each source file only after its copy passed that check, and a source folder only
  once it is empty.
- If the name is taken at the destination, the copy gets `name (1).ext`.
- **Cancel** stops the job. Files that already finished stay; the unfinished `.partial` is removed.
- A PC without `sha256sum` (e.g. Windows) still works; the job then says "size verified".

Copies between two folders on the same PC, or between the PC and another network place, use the
normal file operation instead.

## Connecting from away

The app tries the hosts in your order. LAN addresses get a short timeout (about 1.5 seconds) so it
moves on quickly when you are out. Away from home, connect the **official Tailscale app** on the
phone and use the PC's MagicDNS name (`*.ts.net`) or `100.x` address as a later host. VictorSuite's
built-in Tailscale proxy is not used: the spec says it is not a shared contract (§9).

## Remove a PC

**Remove** deletes the profile, its pinned host key and the phone's key for it. Also delete the
line ending in `victorpc:<deviceId>:filemanager` from `~/.ssh/authorized_keys` on the PC.

## Privacy and security

- Profiles store public data only: name, user, hosts, port, pinned host keys, last host reached.
  No password, token or private key is stored or logged.
- The login key is non-exportable Android Keystore EC P-256 (`SHA256withECDSA`).
- The public key line may be copied to the clipboard: it is a public key, not a secret.
- Remote file names are passed to `sha256sum` as quoted data, never as shell syntax.

## Libraries

| Use | Library | Licence |
|---|---|---|
| My PC SSH + SFTP (`SFTPv3Client`) | `org.connectbot:sshlib` 2.2.51 | BSD-3-Clause |
| Its Ed25519/X25519 helpers | Tink (`tink-android`, already in the app via security-crypto) | Apache-2.0 |
| Background jobs | AndroidX WorkManager (dataSync foreground service) | Apache-2.0 |

Manual SFTP connections (Network screen) still use JSch, unchanged.

### Library status

sshlib 2.2.51 (October 2026) is the latest `org.connectbot:sshlib`, but its classes are now marked
deprecated: upstream says this line gets no further updates, including security fixes, and points
to its successor **cbssh** (`org.connectbot.sshlib:sshlib`, Apache-2.0, still 0.x). Phase 1 uses
2.2.51 as planned; all sshlib calls sit in one file (`core/.../mypc/MyPcSftp.kt`) so moving to
cbssh in phase 2 touches only that file. The host-key, resume and verify logic does not depend on it.

## What phase 1 does not do yet

**Phase 2**
- Find PCs on the LAN automatically (NSD / mDNS `_victorpc._tcp`) and a "scan subnet" helper.
- Faster transfers (pipelined SFTP reads/writes; today it is one 32 KB request at a time).
- Open/preview PC files directly from the pane without copying first; thumbnails for PC images.
- A per-transfer "pause" and a transfer history that survives clearing.

**Phase 3** (blocked until VictorSuite PR 8 ships the provider and enroll service)
- Pick paired PCs from VictorSuite (`content://com.termux.victor.pc/profiles`) instead of typing them.
- Enroll this app's key through VictorSuite's `PcEnrollService` (no manual authorized_keys step).
- Handle VictorSuite's `com.termux.victor.action.OPEN_PC` (mode `sftp`) and its consent screen.
- Pin VictorSuite's signer before trusting its provider.
