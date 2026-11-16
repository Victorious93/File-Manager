# VictorLinux integration for My PC (design only)

**Status: planning only.** This document describes optional extras for the existing
[My PC](../MYPC.md) SFTP pane when the PC is running VictorLinux. It does not implement
those extras. No feature code should land until Don approves this plan. This pull
request must stay open until then, and it must not be merged as a substitute for that
yes.

A draft of the user-facing connect guide (item 1) is in [docs/VICTORLINUX.md](../VICTORLINUX.md).
That page is also a draft: it is not in the app yet, and the in-app Help manual still
describes only features that exist today.

Checked against:

- File Manager `main` at `60f458d` (12 Nov 2026, 1:29 PM EST), after
  [#67](https://github.com/Victorious93/File-Manager/pull/67). My PC phase 1 landed in
  [#62](https://github.com/Victorious93/File-Manager/pull/62) (merged 10 Nov 2026, 5:48 PM EST,
  squash `92e994f`). Pull requests [#63](https://github.com/Victorious93/File-Manager/pull/63)
  through [#67](https://github.com/Victorious93/File-Manager/pull/67) added archive search,
  parked Root/Shizuku work, size-safe elevated I/O, and a later cleanup of a priv-kit spike.
  They did not change My PC code or `docs/MYPC.md`.
- VictorLinux `main` at `01fdd30` (15 Nov 2026, 12:46 AM EST), including
  `docs/security.md`, `docs/remote-access.md`, `docs/storage.md`,
  `debian-edition/docs/SECURITY.md`, `debian-edition/docs/PERSISTENCE.md`,
  the Arch Control Center (`web/index.html`, tab **Server & Access**),
  the Debian Tk Control Center (`victor-control`, tab **Remote access**),
  and `victor-priv` in both editions.

Facts that could not be confirmed in those sources are marked **to confirm with
VictorLinux**.

## 1. Standalone in both directions (hard rule)

File Manager works fully without VictorLinux, and VictorLinux works fully without
File Manager. There is no dependency, no bundling, no shared signing key, and no
code path that requires the other app or ISO to be present.

- My PC already talks to an ordinary OpenSSH server. A plain Ubuntu (or other Linux)
  PC keeps working exactly as [docs/MYPC.md](../MYPC.md) describes, whether or not
  VictorLinux is ever installed.
- Every extra in this plan is optional and sits on top of that plain SSH or SFTP
  session. If a folder is missing, a path probe fails, or the PC is not
  VictorLinux, the extras stay hidden and the rest of the pane is unchanged.
- VictorLinux does not need File Manager for inference, storage, SSH, or anything
  else. This app will not call the VictorLinux gateway to move files (see §2).

## 2. What already exists (My PC phase 1)

This plan reuses the phase 1 behaviour. It does not change host-key pinning or the
transfer pipeline.

- **Tools → My PC.** You add a PC by hand: name, user, hosts (LAN first), port,
  optional host-key pin, optional start folder.
- **Login key.** A non-exportable Android Keystore EC P-256 key, alias
  `victorpc.<profileId>.filemanager`. **Key** shows a line like
  `restrict ecdsa-sha2-nistp256 … victorpc:<deviceId>:filemanager`, with **Copy line**.
- **Host-key pinning** uses the fleet form `<keytype>:SHA256:<base64 without padding>`
  and compares it as exact text (`HostKeys.kt`). A changed key is a **hard stop**:
  red warning, no "connect anyway", no fall-through to the next address, and
  background jobs fail at once without retry. Trust on first use is only for a
  manual profile that has no pin yet, and nothing is sent until you confirm.
- **Transfers** run as a WorkManager `dataSync` foreground service
  (`MyPcTransferWorker`). Bytes go to `<name>.partial`. After a drop, the job
  retries with back-off and resumes from the `.partial` file's current length
  (`ResumableTransfer.kt`). When the size matches, both ends are hashed
  (PC side: `sha256sum -b`, path quoted). Only then is `.partial` renamed. A
  resumed file that fails the hash is redone once from zero. Move deletes a source
  only after that check. A taken name becomes `name (1).ext` (`TransferNames.unique`).
  Cancel keeps finished files and deletes the unfinished `.partial`.
- **Library:** `org.connectbot:sshlib` 2.2.51, `SFTPv3Client`, in
  `core/.../mypc/MyPcSftp.kt`. Phase 1 writes one 32 KB request at a time. There is
  **no** `statvfs@openssh.com` call today, and no free-space warning before a copy.
- **Pairing through VictorSuite (P3)** is still blocked until VictorSuite's
  provider and enroll service (VictorSuite PR 8). Until then, the public key is
  added on the PC by hand.

## 3. What VictorLinux already does (confirmed unless noted)

SSH and files:

- SSH is **key-only**. Password SSH is not supported.
- The account is **`victor`**. `AllowUsers victor`, `PermitRootLogin no`.
- SSH is **off by default** (`sshd` / `ssh` is disabled in the image). You turn it
  on in the Control Center after adding a public key.
- **Arch edition** Control Center, tab **Server & Access**, card **"SSH (off by
  default)"**: field **"Public key (ssh-ed25519 …)"**, buttons **Add key**,
  **Enable**, **Disable**, **Remove all keys & disable**, and a listen-address
  field that defaults to `127.0.0.1`. Status reads `SSH RUNNING on <address>` or
  `SSH stopped`, plus the key count and "key authentication only". Enabling
  prints the host-key fingerprint from
  `ssh-keygen -l -f /etc/ssh/ssh_host_ed25519_key.pub`.
- The Arch helper `victor-priv ssh-add-key` accepts **one plain public key line**.
  The first token must be a key type (`ssh-ed25519`, `ecdsa-sha2-nistp256`, …).
  A line that starts with `restrict` is rejected: *"Unsupported key type or key
  options present. Paste a plain public key (ssh-ed25519 …)."* File Manager's
  **Copy line** includes `restrict`, so that full line cannot be pasted into the
  Arch Control Center as-is. See open question Q1.
- **Debian edition:** docs say enable SSH in **Control Center → Remote access**
  with your public key, and `victor-priv ssh-enable <keyfile> <bind>` exists.
  In the Tk UI on `main` (`01fdd30`), the **Remote access** tab only covers the
  inference API (listen address, HTTPS, **Revoke ALL remote access**). No SSH
  add-key or enable controls were found there, and first-run only sets the owner
  password. **To confirm with VictorLinux:** how a Debian user is meant to enable
  SSH today (Q2).
- Host keys are generated when SSH is enabled (`ssh-keygen -A`), and the ISO
  ships with none. **To confirm with VictorLinux:** whether those host keys and
  `/home/victor/.ssh/authorized_keys` survive a live reboot onto `VICTORDATA`.
  Debian's persistence doc says files under `/home/victor` do not survive unless
  they live on the data partition (Q3). A new host key after reboot would hit
  File Manager's hard stop, which is correct, but it would surprise someone who
  only rebooted the live image.

Files are SFTP only:

- The VictorLinux **gateway** (`gateway.py`) exposes inference routes only
  (`/victor/health`, `/victor/capabilities`, OpenAI / Ollama / llama.cpp paths).
  `docs/remote-access.md` and `docs/security.md` state it has no shell, file,
  storage or volume routes. The phone-companion API is the same. So this app
  will not send models or documents through the gateway. Copies stay on the
  existing My PC SFTP session.

Folder paths (optional destinations, not fixed):

- **Arch edition:** after `victor-storage use models|documents|backups …`,
  desktop links appear at `~/victor-storage/models`, `~/victor-storage/documents`
  and `~/victor-storage/backups`. Volumes mount under `/mnt/victor-vol/<UUID>`.
  If the drive is missing, the link dangles and "resolves to no path".
- **Debian edition** does not have that destination feature yet. Persistent
  models and documents live on `VICTORDATA` as `models/` and `documents/`,
  and the persistence doc tells users to keep work in `~/VictorData/documents/`.
  There is no `backups` destination in that layout. **To confirm with
  VictorLinux:** the exact user-visible paths (and whether `~/VictorData/models`
  is a stable symlink) (Q4).

Do not confuse **Server & Access** / **Remote access** (inference API key,
"Revoke all remote access") with **SSH**. They are different services.

## 4. Item 1 — Connect to VictorLinux (high priority, small)

Ship a short user guide. The drafted text is [docs/VICTORLINUX.md](../VICTORLINUX.md).
After Don approves implementation, the same steps can be linked from Tools → My PC
(for example a "Connect a VictorLinux PC" hint) and from the in-app Help manual.
Until then, the guide stays in docs only.

What the guide covers:

1. On the PC, open the Control Center and turn SSH on (Arch wording above).
2. Add File Manager's **plain** public key for user `victor` (see Q1).
3. Set the listen address to the PC's LAN or Tailscale IP, not `127.0.0.1`.
4. In the app, add the PC with user `victor`, then verify the host-key
   fingerprint on first connect (`ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`
   on the PC) and pin it.

Effort after approval: small (docs are in this PR; an in-app link is a short follow-up).

## 5. Item 2 — Optional quick links (medium priority, medium size)

After a successful SFTP login, the My PC card or the pane header may show short
cuts to **Models**, **Documents** and **Backups**. These are conveniences, not a
VictorLinux-only mode.

Rules:

- The links work for **any SSH PC**, including plain Ubuntu.
- A link is shown **only** if that path exists and is a directory, checked with
  SFTP `stat` after connect. A missing path, a regular file, or a dangling
  symlink (stat fails) is omitted. There is no error banner for absence; the
  link is simply not there.
- No link is shown before the first successful, pinned login.
- The app never creates these folders just to show a link.

Path list, tried in this order, first hit wins per role:

| Role | Paths to `stat` (relative to the login home unless absolute) |
| --- | --- |
| Models | `victor-storage/models`, `VictorData/models`, `models` |
| Documents | `victor-storage/documents`, `VictorData/documents`, `documents`, `Documents` |
| Backups | `victor-storage/backups`, `VictorData/backups`, `backups`, `Backups` |

`Documents` / `Backups` with a capital letter are included because many ordinary
Linux and desktop homes already use them. There is no special-case detection of
"this is VictorLinux". If none of a role's paths exist, that role has no chip.

Caching and refresh:

- Cache the probe result **per profile**, with the path that won for each role,
  a timestamp, and whether the last probe fully finished.
- Memory cache for the session; a small on-disk cache (no secrets) may keep the
  last successful result so the chips can render quickly on the next open, then
  be confirmed in the background.
- Treat the cache as stale after 5 minutes, on every new login, after a host-key
  or address change, and after a user **Refresh**.
- **Refresh** is a control on the My PC card and/or the pane. Pull-to-refresh
  in the pane also re-runs the probe.
- If a cached path now fails `stat`, hide that chip immediately and re-probe the
  list. Never keep showing a path that is gone (for example a dangling
  `~/victor-storage/models` after the external drive is unplugged).

UI (after approval): dark fleet theme only. Background `#0B0F1A`, accents
`#00E5FF` and `#FF2BD6`, no white surfaces, no light chips. English only.

Effort after approval: medium (probe + cache + a few composables + tests; about
two to three days).

## 6. Item 3 — "Send to PC models folder" for `.gguf` files (medium priority, medium size)

A share/action on a `.gguf` file (from the file browser, the selection bar, or
the share sheet when the file is local) sends it to the PC's models folder over
the existing My PC session.

It works with **any SSH PC**. The destination is the same "Models" path the
quick-link probe would use. If more than one PC is saved, the user picks the PC
first. If that PC has no models folder, the action is disabled or hidden and
explains that the folder was not found. It does **not** silently create the
folder (open question Q5: offer to create it?).

The transfer **reuses** `ResumableTransfer` and `MyPcTransferWorker` unchanged in
spirit:

- write to `<name>.partial`
- resume from offset after a drop
- check size, then SHA-256 on both ends (`sha256sum` on the PC when present)
- rename only after that check
- on a name conflict, use `TransferNames.unique` (`model.gguf`, then
  `model (1).gguf`, …)
- Cancel keeps finished files and removes the unfinished `.partial`
- a changed host key still fails at once and does not retry

Large files (over 4 GiB):

- Phase 1 already uses `Long` sizes and SFTP offsets, so a file bigger than
  4 GiB is not blocked by the copier itself.
- Before starting, if free space or the filesystem limit can be read, warn
  (or refuse) when the file will not fit. VictorLinux already refuses
  FAT32 files ≥ 4 GiB on its own storage tool; File Manager should say so
  up front when it can tell.
- Free space: try the SFTP extension `statvfs@openssh.com` when the server
  advertises it (OpenSSH usually does). If it is missing, do not invent a
  figure and do not block the send; tell the user that free space could not
  be read and go ahead, then surface a clear write error if the disk fills.
  Phase 1 has no `statvfs` call today; this would be new, still SFTP, and
  used only as a warning.

Split GGUF sets (`name-00001-of-0000N.gguf`, …) are an open question (Q6).
Until that is decided, the action applies to a single `.gguf` file. If the
user selected a complete set, a later change can send the whole set as one
job and keep the existing verify-then-rename behaviour per shard.

Theme: same dark neon treatment as other transfer confirmations. No white
dialogs.

Effort after approval: medium (wire the action, destination pick, optional
`statvfs`, tests including >4 GiB sizes in the copier; about two to three
days). The heavy lifting is already in `#62`.

## 7. Host-key pinning stays unchanged

This plan does not relax pinning.

- Format remains `<keytype>:SHA256:<base64 without padding>`.
- A changed key is still a hard stop, including during a "send to models
  folder" job.
- First connect to a VictorLinux PC uses the same compare-and-pin flow as any
  other PC. The user-guide text tells people to compare with
  `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`.
- If a live image regenerates host keys on reboot (Q3), the app will block,
  which is the specified behaviour.

## 8. Phases (only after Don says yes)

| Phase | What | Size |
| --- | --- | --- |
| This PR | Design doc + draft user guide + links from existing docs | docs only |
| VL-A | In-app pointer to the guide, path probe, quick-link chips, cache and refresh | Medium |
| VL-B | "Send to PC models folder" on `.gguf`, `statvfs` warning, missing-folder and name-conflict handling | Medium |

VictorSuite P3 pairing stays on its own track (blocked on VictorSuite PR 8) and
is not required for these extras.

Suggested order: VL-A then VL-B, one pull request each, or one PR if they stay
small. No merge without Don's yes.

## 9. Test plan (for the later feature PRs)

Unit tests (`:core`), against fakes, not a real PC:

- Path probe: show a chip only for a directory `stat`; hide missing, file, and
  failed-stat (dangling link) paths; first-hit order as in the table.
- Cache: stale after timeout, login, address change, and Refresh; hide a path
  that disappears on re-probe.
- "Send to models folder" queues the existing transfer with the probed path;
  name conflict uses `name (1).gguf`; missing models folder does not start a
  job.
- Resume and verify stay covered by the existing `ResumableTransfer` tests
  (fresh copy, offset resume, stale or long `.partial`, hash redo, cancel,
  already-finished, no overwrite, empty file).
- `statvfs`: warn when free space is too small; when the extension is absent,
  do not invent a number; a >4 GiB source against a 4 GiB-minus-one limit is
  refused with a clear reason.
- Host-key mismatch during a send still fails without retry (existing
  connect-plan tests plus one job-level case).

Emulator / JVM sshlib tests (as in `#62`, in-process Apache SSHD where useful):

- Probe and send against a stub home that contains only some of the folders.
- A `.gguf` send that resumes and verifies.

Manual, after a feature PR, on real hardware (not claimed until done):

- Arch VictorLinux: enable SSH from **Server & Access**, add the plain key,
  pin the host key, open the pane, confirm chips match real folders, send a
  small `.gguf`, then a large one if a drive allows it.
- Debian VictorLinux, once Q2/Q4 are answered.
- A plain Ubuntu PC with only `~/models` present (chips and send still work).
- A PC with none of the paths (no chips, no send action).

## 10. Risks

- Pasting File Manager's `restrict …` line into the Arch Control Center fails
  today (Q1). The draft guide already tells people to paste the plain key.
- Live-image reboot may change the host key or drop `authorized_keys` (Q3):
  the app will hard-stop, which can look like a break.
- Dangling `~/victor-storage/…` links when a destination drive is unplugged:
  the probe must treat a failed `stat` as "hide".
- FAT32 and other 4 GiB limits: a silent start would waste time; `statvfs`
  plus a clear error are required when we can read them.
- People may confuse "Revoke all remote access" (gateway API) with turning
  SSH off. The user guide calls this out.
- Large GGUF files over a 32 KB unpipelined SFTP stream will be slow. That is
  an existing phase 2 item, not a blocker for the action, but it should be
  mentioned in the confirmation step.
- Debian SSH and folder paths are not fully confirmed (Q2, Q4). Shipping
  chips that only `stat` known paths still works; the written steps for
  Debian may need a correction from the VictorLinux bot.

## 11. Open questions

For Don:

- **Q5.** If the models folder is missing, should "Send to PC models folder"
  stay hidden, or offer to create one of the standard paths after a confirm?
- **Q6.** Should a complete split GGUF set be sent as one job in the first
  cut, or only single `.gguf` files at first?
- **Q7.** Is the path order in §5 right, or should a later settings field let
  you set custom models/documents/backups folders per PC?

For the VictorLinux bot:

- **Q1.** The Arch Control Center rejects key options, so File Manager's
  `restrict ecdsa-sha2-nistp256 …` line cannot be pasted into **Add key**.
  Should VictorLinux accept a leading `restrict` (and a trailing
  `victorpc:…:filemanager` comment), or should File Manager grow a second
  "Copy plain public key" button for this flow?
- **Q2.** On Debian `main` (`01fdd30`), **Control Center → Remote access**
  has no SSH controls, while the docs say to enable SSH there. What should
  a Debian user actually do today?
- **Q3.** Do SSH host keys and `/home/victor/.ssh/authorized_keys` persist
  across a live reboot when `VICTORDATA` is mounted? If not, File Manager
  will hard-stop on the next connect after a reboot.
- **Q4.** Please confirm the stable, user-visible paths for models,
  documents and backups on Debian (and that Arch is
  `~/victor-storage/{models,documents,backups}`).

## 12. Theme notes

Any UI added later uses the dark fleet theme only: background `#0B0F1A`,
accents `#00E5FF` and `#FF2BD6`. No white or light-grey surfaces, no light
chips, no white dialogs. English only. Connection-blocked and transfer-failed
states stay red warnings, in line with the existing My PC hard stop.
