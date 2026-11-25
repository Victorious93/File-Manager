# Connect File Manager to a VictorLinux PC (draft)

**This is a draft user guide for a planned extra.** It is not in the app yet, and
it is not a promise that the later features have been built. My PC itself already
works with any OpenSSH PC; this page only explains how to use that existing pane
with VictorLinux.

Full design, including optional quick links and "Send to PC models folder":
[docs/design/VICTORLINUX_INTEGRATION.md](design/VICTORLINUX_INTEGRATION.md).
The general My PC guide is [docs/MYPC.md](MYPC.md).

File Manager and VictorLinux stay standalone. You do not need one to use the
other. Nothing here is required for ordinary Ubuntu or other Linux PCs.

## What you need

- File Manager with My PC (phase 1, from pull request #62).
- A PC booted into VictorLinux (Arch or Debian edition), on the same Wi-Fi, or
  both sides connected with the official Tailscale app for use away from home.
- SSH turned on, with this phone's **plain** public key added for the user
  **`victor`**.

Files always travel over **SFTP** (the same My PC session). VictorLinux's
inference gateway is HTTP-only on `127.0.0.1:8080` by default and has no file
routes, so you cannot send models or documents through that API.

SSH does **not** survive a reboot. After every boot you must turn it on again,
add the phone's key again, and re-pin the new host key. See [After a reboot](#after-a-reboot).

## 1. Turn SSH on (VictorLinux)

SSH is off until you enable it. Password logins are not supported.

### Arch edition

1. On the PC, open the **Control Center**.
2. Open the **Server & Access** tab.
3. In the card **SSH (off by default)**:
   1. Paste a **plain** public key into **Public key (ssh-ed25519 …)** (see
      step 2 below).
   2. Tap **Add key**. The status should show a key count of at least one.
   3. Set the listen address to the PC's LAN address or its Tailscale address
      (`100.x`), **not** the default `127.0.0.1`. A loopback address only
      accepts connections from the PC itself, so the phone cannot reach it.
   4. Tap **Enable**. The card should read **SSH RUNNING on** that address,
      "key authentication only".
4. The PC prints a host-key fingerprint when SSH starts. You can also show it
   with:

   ```sh
   ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub
   ```

**Disable** stops SSH and keeps the keys (until the next reboot, when they are
lost anyway). **Remove all keys & disable** stops SSH and deletes the keys.

Do not confuse this with **Revoke all remote access** on the same tab. That
button is for the inference API key, not for SSH.

### Debian edition

Debian's Control Center has **no SSH controls**. The **Remote access** tab is
only for the inference API.

1. Save this phone's **plain** public key (see step 2) to a file owned by
   `victor`, for example `/home/victor/filemanager.pub`. One line, no
   `restrict` prefix.
2. On the PC, turn SSH on and install that key, listening on the address the
   phone will use (LAN or Tailscale IP, not `127.0.0.1`):

   ```sh
   victor-priv ssh-enable /home/victor/filemanager.pub 192.168.1.20
   ```

   Replace the path and the IP with yours.
3. Show the host-key fingerprint:

   ```sh
   ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub
   ```

**Revoke ALL** on **Control Center → Remote access** turns SSH off and removes
the keys. It also revokes the inference API key.

## 2. Let the phone log in

In File Manager:

1. Open **Tools → My PC** → **+**.
2. **Name:** anything, for example *VictorLinux*.
3. **PC user:** `victor` (not your usual Ubuntu name, unless this is a different
   PC).
4. **Hosts:** the same address SSH is listening on, LAN first. For example
   `192.168.1.20, pc.tail1234.ts.net`.
5. **SSH port:** `22`, unless you changed it.
6. Tap **Save**, then **Key**.

The app shows a line like:

```
restrict ecdsa-sha2-nistp256 AAAAE2VjZHNh… victorpc:<deviceId>:filemanager
```

**Important:** use a **plain** public key with VictorLinux. The first word must
be the key type, for example `ecdsa-sha2-nistp256` or `ssh-ed25519`. The Arch
Control Center and Debian's `victor-priv ssh-enable` reject a line that starts
with options such as `restrict`. That is by design.

Paste only the plain key: the type, the `AAAA…` blob, and optionally the
`victorpc:…:filemanager` comment. Leave off `restrict`.

## 3. Pin the host key on first connect

1. In File Manager tap **Open in pane**.
2. If you did not type the host key when adding the PC, the app shows the key
   the PC presented and waits. Compare it with
   `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` on the PC.
3. Tap **It matches, pin it** only if they match. Nothing is sent before you
   confirm.

If the PC later answers with a different key, the app **blocks the connection**.
There is no "connect anyway", and the new key is never pinned automatically.
See [After a reboot](#after-a-reboot) if you just rebooted VictorLinux.

## 4. Browse and copy, as usual

The pane works like any other My PC: list, rename, delete, new folder, and
Copy or Move between the phone and the PC. Transfers write `<name>.partial`,
resume if the link drops, check size and SHA-256, and only then take the real
name. See [My PC](MYPC.md) for the details.

On VictorLinux you will usually find:

- **Debian:** `/run/victor/data/models/` and `/run/victor/data/documents/`
  (also as `/run/victor/root/…` and `~/VictorData/…`). There is no backups
  folder.
- **Arch:** `~/victor-data` (the data root `/mnt/victordata/victorlinux`, or
  legacy `tori/`), and, if you set them, `~/victor-storage/models`,
  `~/victor-storage/documents` and `~/victor-storage/backups`.

## After a reboot

On both editions, a reboot wipes SSH state on the live root:

- SSH is off (Arch does not start it at boot).
- The phone's line in `authorized_keys` is gone.
- The next time you enable SSH, the PC gets a **new** host key.

Do this every time you reboot the VictorLinux PC:

1. On the PC, turn SSH on again and add the phone's **plain** public key
   again (Arch: Control Center steps above; Debian:
   `victor-priv ssh-enable KEYFILE BIND`), using a LAN or Tailscale listen
   address.
2. On the PC, run `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` and
   keep that fingerprint visible.
3. On the phone, open the PC again. File Manager will block the connection
   and show a screen along the lines of **"This PC's identity changed, which
   is expected after a VictorLinux reboot."** It lists the old (pinned)
   fingerprint and the new one. Nothing is sent, and the pin is not updated
   on its own.
4. Compare the new fingerprint with the one on the PC. Only if they match,
   tap **"It matches, pin the new key"** (or the same wording the app
   ships). If they do not match, leave it blocked.

Re-pinning alone is not enough: if you skip step 1, the PC will still refuse
the phone's login key.

## Optional extras (not built yet)

These are described in the [design doc](design/VICTORLINUX_INTEGRATION.md) and
wait for Don's approval:

- Quick links to Models, Documents and Backups, shown only when those folders
  exist after an SFTP check of the candidate paths above (and ordinary names
  such as `~/models`). They also appear on an ordinary SSH PC that happens to
  have the same folder names. A missing path, including Debian's lack of a
  backups folder, simply hides that link.
- **Send to PC models folder** for `.gguf` files, using the same resumable,
  verified transfer.
- The identity-changed screen and explicit re-pin button described above.

## If something goes wrong

| What you see | What to try |
| --- | --- |
| The phone cannot connect at all | Confirm SSH is running, the listen address is the LAN or Tailscale IP (not `127.0.0.1`), and the phone can ping that address. After a reboot, SSH is off until you enable it again. |
| "The key was refused" | The plain public key is not in `victor`'s `authorized_keys` (it is wiped on every reboot), or you pasted the `restrict …` line into **Add key** or `KEYFILE`. Add a plain key and try again. |
| "This PC's identity changed" / "Connection blocked" | After a VictorLinux reboot this is expected. Re-enable SSH, add the plain key again, compare the old and new fingerprints on the screen with the PC, and re-pin only if they match. If you did not reboot the PC and the fingerprint does not match, do not re-pin. |
| No Models / Documents / Backups links | Those extras are not in the app yet. You can still open the folder by hand if it exists (Debian: `/run/victor/data/…` or `~/VictorData/…`; Arch: `~/victor-data` or `~/victor-storage/…`). |

## Privacy

My PC still stores no password and no private key. The login key stays in the
phone's Android Keystore. The public key is not a secret. Profiles only keep
the name, user, hosts, port and pinned host keys.
