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
- SSH turned on, with this phone's public key added for the user **`victor`**.

Files always travel over **SFTP** (the same My PC session). VictorLinux's
inference gateway has no file routes, so you cannot send models or documents
through that API.

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

**Disable** stops SSH and keeps the keys. **Remove all keys & disable** stops
SSH and deletes the keys.

Do not confuse this with **Revoke all remote access** on the same tab. That
button is for the inference API key, not for SSH.

### Debian edition

VictorLinux's docs say to enable SSH in **Control Center → Remote access**
with your public key, still key-only, user `victor`. On the copy of Debian
VictorLinux that this draft was checked against, that tab only showed
inference-API controls, so the exact taps may differ. **To confirm with
VictorLinux.** Until that is confirmed, you can also add a plain public key
to `/home/victor/.ssh/authorized_keys` on the PC (one line, permissions
`600`) if you have a supported way to turn `ssh` on for an address the phone
can reach.

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

**Important:** the Arch Control Center **Add key** field accepts only a *plain*
public key. The first word must be the key type, for example
`ecdsa-sha2-nistp256` or `ssh-ed25519`. It rejects a line that starts with
`restrict`.

Do this:

- For the Control Center, paste only the plain key: the type, the `AAAA…`
  blob, and optionally the `victorpc:…:filemanager` comment. Leave off
  `restrict`.
- If you add the key by editing `~/.ssh/authorized_keys` yourself, you may
  keep the full **Copy line** text, including `restrict`. That is the tighter
  option (no port or agent forwarding for this key). SFTP still works.

## 3. Pin the host key on first connect

1. In File Manager tap **Open in pane**.
2. If you did not type the host key when adding the PC, the app shows the key
   the PC presented and waits. Compare it with
   `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` on the PC.
3. Tap **It matches, pin it** only if they match. Nothing is sent before you
   confirm.

If the PC later answers with a different key, the app **blocks the connection**
with a red warning. There is no "connect anyway". If you reinstalled the PC,
or a live image generated a new host key after a reboot, remove the PC in
My PC and add it again.

## 4. Browse and copy, as usual

The pane works like any other My PC: list, rename, delete, new folder, and
Copy or Move between the phone and the PC. Transfers write `<name>.partial`,
resume if the link drops, check size and SHA-256, and only then take the real
name. See [My PC](MYPC.md) for the details.

## Optional extras (not built yet)

These are described in the [design doc](design/VICTORLINUX_INTEGRATION.md) and
wait for Don's approval:

- Quick links to Models, Documents and Backups, shown only when those folders
  exist (including `~/victor-storage/…` on the Arch edition). They also appear
  on an ordinary SSH PC that happens to have the same folder names.
- **Send to PC models folder** for `.gguf` files, using the same resumable,
  verified transfer.

## If something goes wrong

| What you see | What to try |
| --- | --- |
| The phone cannot connect at all | Confirm SSH is running, the listen address is the LAN or Tailscale IP (not `127.0.0.1`), and the phone can ping that address. |
| "The key was refused" | The plain public key is not in `victor`'s `authorized_keys`, or you pasted the `restrict …` line into the Arch **Add key** field. Add a plain key and try again. |
| "Connection blocked" | The host key changed. If you expected that, remove the PC and add it again. If you did not, do not override it. |
| No Models / Documents / Backups links | Those extras are not in the app yet. You can still open the folder by hand if it exists (`~/victor-storage/models` on Arch, or `~/VictorData/documents` on Debian). |

## Privacy

My PC still stores no password and no private key. The login key stays in the
phone's Android Keystore. The public key is not a secret. Profiles only keep
the name, user, hosts, port and pinned host keys.
