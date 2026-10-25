# File Manager — User Manual

This manual explains, in plain language, how to use **File Manager** (`ai.victorious.filemanager`;
debug builds are `ai.victorious.filemanager.debug`). It only describes features that exist in the
app today. Where a picture would help, you will see a marked placeholder such as
`[TODO screenshot: …]` — no real screenshots have been added yet.

Contents:

1. What File Manager is
2. Installing and first-run setup
3. Features, step by step
4. Permissions and why they are needed
5. Fleet integration
6. Troubleshooting and FAQ
7. Privacy and data handling

---

## 1. What File Manager is

File Manager is a full Android file manager with power tools: two panes with tabs, batch file
operations with undo, search, bookmarks, a trash, editors (text, code, hex, diff), archives, APK
tools, a readable Java view of app code, a terminal, root/Shizuku tools, network and cloud drives,
Wi-Fi transfer, media viewers, an encrypted secure folder, an optional AI assistant and plugins.

**It works completely on its own.** You never need any other app installed.

### Its role in the Victorious fleet

Inside the Victorious app fleet, File Manager is an **EXTERNAL_NODE**. That means:

- A controller app (DroidCommand AI, package `ai.droidcommand.app` or `ai.droidcommand.app.debug`)
  *can* talk to it, but File Manager does not depend on the controller. Removing the controller
  disables nothing.
- The connection is a **Messenger bridge**: the controller binds to the File Manager service with
  the action `ai.victorious.filemanager.action.BRIDGE` and exchanges small JSON messages.
- The controller is recognised ("paired") by **package name plus signing certificate**: only the
  allowlisted controller packages are accepted, and when certificate pins are built into the app,
  the caller's signing certificate (SHA-256) must match too.
- It is not a "bound companion": there is no shared signing key, no special `BIND_*` permission
  and no dependency guard.

See section 5 for what the controller can and cannot do.

---

## 2. Installing and first-run setup

### Install

1. Get the APK (for example the debug build `app-debug.apk` from a build or CI artifact).
2. Open it on your phone. If Android asks, allow your browser or file app to **install unknown
   apps**, then tap **Install**.
3. Requirements: Android 8.0 (API 26) or newer.

`[TODO screenshot: system install dialog]`

### First run: storage access

1. Open **File Manager**. If it cannot see your files, a **Storage access needed** banner appears
   at the top.
2. Tap **Grant**.
   - Android 11 and newer: the system *All files access* page opens. Turn the switch on for File
     Manager, then go back.
   - Android 8–10: approve the storage permission prompt.
3. Without this, the app still runs but can only see its own folders.

`[TODO screenshot: Storage access needed banner]`

### Controller pairing (optional)

You only need this if you use DroidCommand AI as a fleet controller.

1. Install the controller (`ai.droidcommand.app`, or `ai.droidcommand.app.debug` for debug).
   Pairing is automatic by package name — there is no code to type. If the File Manager build was
   made with certificate pins, the controller must also be signed with the pinned certificate.
2. In File Manager open **Settings → Fleet** and turn on **Allow external control**. It is **off by
   default**. While it is off, the controller can only see that File Manager is installed and
   disabled.
3. The **About** dialog (the ⓘ icon at the top) shows this device's **node id**, which the
   controller uses to address this app.

`[TODO screenshot: Settings → Fleet → Allow external control]`

### Root setup (optional)

1. Your device must be rooted (for example with Magisk) and provide `su`.
2. Open **Settings → Privilege** and turn on **Root (su / Magisk)**. Root is **off by default**.
3. Approve the root request in your root manager. If `su` is missing or denied, Settings tells you.

### Shizuku setup (optional, no root needed)

1. Install **Shizuku** (`moe.shizuku.privileged.api`) and start it — on most phones via
   *Wireless debugging* (Android 11+), or via root. Sui is also supported.
2. In File Manager open **Settings → Privilege** and turn on **Use Shizuku / Sui** (off by default).
3. A status card shows one of four states with a coloured dot:
   - **Not installed** → button **Get Shizuku**.
   - **Not running** → button **Open Shizuku**; start it there (Wireless debugging or root).
   - **Denied** → button **Grant access**; approve the Shizuku prompt.
   - **Granted** → ready.
4. Tap **Re-check** after changing something in Shizuku.

When both are on, the app uses **Root first, then Shizuku, then normal** access.

`[TODO screenshot: Shizuku status card]`

---

## 3. Features, step by step

The main screen has a navigation bar: **Files, Search, Bookmarks, Apps, Tools, Trash, Settings**.
On wide screens (600 dp and up) it becomes a rail on the left.

### 3.1 One pane or two, and tabs

- The **Files** tab opens with **one** file list. Tap the split-screen button in the top bar (or
  **Settings → Appearance → Two file panes**) to show **two** panes. On wide windows they sit side by
  side; on phones they stack. The choice is remembered.
- With two panes, tap a pane to make it the **focused pane**. Tools and copy/move use it. The pane you
  are not looking at keeps its folder and tabs, so switching back is instant.
- Each pane has tabs: tap **+** to open a new tab, tap a tab to switch, tap its ✕ to close it.
- Tap a folder to open it; tap a file to open it in the matching viewer.
- Use the **create** button to make a **New file** or **New folder** in the current folder.

`[TODO screenshot: dual pane with tabs]`

### 3.2 Selecting and batch operations

1. Long-press an item (or use **Select** in its menu) to start selecting; tap more items to add them.
2. Use the bottom bar:
   - **Copy** / **Move** — with two panes, to the folder open in the *other* pane. With one pane they
     *stage* the selection and show a **Paste here** bar: open the destination folder and tap **Paste
     here**. A copy stays staged so you can paste it in more places; a move is used up. **✕** drops it.
   - **Delete** — moves items to the Trash.
   - **Rename** — one item opens a simple rename; two or more open **Batch rename**.
   - **Zip selected** — creates `name.zip` in the current folder.
   - **Install selected APKs** — installs several APKs one after another.
   - **Share / export** — sends files through the Android share sheet.
   - **Undo** — reverts the last operation.
3. Operations run in a queue, one at a time, with progress.

**Batch rename** (2+ items):

1. Optional **Find** / **Replace with** (tick **Regex** for regular expressions, `$1` works;
   **Match case** for case-sensitive).
2. Optional case change (keep / upper / lower / title).
3. Optional **Template** with `{name}`, `{n}` (counter with **Start**, **Step**, **Pad**) and `{ext}`.
4. Optional **New extension** (blank keeps the old one).
5. Check the live preview. **Rename** is only enabled when no name is invalid or clashing.
   **Undo** reverts the whole batch.

### 3.3 Search

1. Open the **Search** tab and type in **Search this device**.
2. Several words must all match (`beach plan`). `*.pdf` or `.pdf` filters by type. `/photos`
   matches part of the folder path.
3. Results update as you type. The index is rebuilt automatically after file changes.

### 3.4 Bookmarks

1. In **Files**, open a folder and tap the **bookmark** icon in the top bar.
2. Open the **Bookmarks** tab to jump back. Tap the icon again to remove a bookmark.
3. Home-screen widget: add the File Manager **bookmarks widget** from your launcher's widget list;
   tapping a folder opens it in the app.

### 3.5 Trash

- Deleting moves items to the trash instead of erasing them. On shared storage they move into a
  hidden `.victorious-trash` folder on the same drive (fast, no copying).
- Open the **Trash** tab: **Restore** an item, or **Empty trash** to delete everything for good.
- Note: trashed files on shared storage survive an uninstall. Empty the trash first if that matters.

### 3.6 Storage analyzer and duplicates

- **Tools → Storage analyzer** shows a size breakdown of the focused folder.
- **Tools → Find duplicates** scans the focused folder recursively and groups identical files.

### 3.7 Editors

Long-press a file to choose how to open it.

**Text / code editor** (*Open in text editor*, UTF-8 files up to 4 MB):

1. Edit, then tap save. Undo/redo are available; leaving with unsaved changes asks first.
2. Syntax highlighting and find/replace are built in.

**Hex editor** (*Open in hex editor*, files up to 16 MB):

1. Rows show offset, hex and ASCII.
2. Tap a row to overwrite bytes; undo is available; saving writes only changed bytes.

**Diff** (**Tools → Compare two files**):

1. Select exactly two text files in Files.
2. Open **Tools → Compare two files** to see a line-by-line diff.

`[TODO screenshot: code editor]`

### 3.8 Archives

- Open an archive by tapping it, or long-press → **Open as archive**.
- Supported: ZIP/JAR/APK (list, extract, password-protected ZIP), 7z, TAR (plain, `.tar.gz`/`.tgz`,
  `.tar.bz2`, `.tar.xz`), single compressed files (`.gz`, `.bz2`, `.xz`), and RAR (list and extract
  only, including password RAR).
- Extraction goes to the folder in the *other* pane, or to the folder on screen when only one pane is
  shown. Paths that try to escape the folder are
  blocked.
- Create: **Zip selected** in the bottom bar, or **Tools → Create password ZIP** for AES-256.

### 3.9 APK tools

Long-press an `.apk`:

- **APK info** — label, package, versions, SDK levels, signature schemes, permissions; uninstall
  from here.
- **Install APK** — Android shows its own confirmation for every install. The first time, allow
  *Install unknown apps* for File Manager.
- **Extract APK** — unzips into `<name>_extracted/`.
- **Sign APK…** — V1/V2/V3 signing with a per-device debug key (made on first use) or your own
  PKCS12 keystore. **Verify signature** checks it.
- **Uninstall app…**, **Open APK bundle (splits)** (APKS/XAPK), **Share / export**.
- Long-press an extracted folder → **Rebuild APK from folder** (output is unsigned; sign it next).

**APK mod** (long-press → **APK mod**, or **Tools → APK mod** with one APK selected):

1. Edit manifest fields: package name, versionCode/versionName, minSdk/targetSdk, permissions,
   debuggable, exported components.
2. Edit the text of existing strings in `resources.arsc` (adding new strings is not supported).
3. Optionally paste one smali class to replace.
4. Tap **Rebuild + sign + verify**, then optionally **Install**.

**APK surgery / smali** (long-press → **APK surgery** or **Browse DEX → smali**):

1. Pick a dex, filter the class list, open a class.
2. Switch **Method list** / **Whole class smali**, tap **Edit**, save, then **Assemble**.
3. **String constants** lists every string literal in the dex.

**Other APK tools** in Tools: **Compare two APKs**, **Smali tools** (search, string-decryption
hints, apply `mapping.txt`), **Extract installed apps**.

> Patch presets are developed on a separate branch (PR #24) and are not part of this build, so
> they are not covered here.

### 3.10 Java view

1. In a class view (APK surgery / DEX browser), tap **Decompile to Java**. Tap **Show smali** to
   switch back.
2. Search inside the Java text, copy it, or export the class as `.java`.
3. **Export all .java** writes a whole dex to a folder.
4. The AI assistant can explain decompiled Java (see 3.17).

### 3.11 Terminal

1. **Tools → Terminal**.
2. A real terminal (PTY) is used when available, with resize, colours, copy/paste and extra keys
   (Esc, Tab, Ctrl, Alt, arrows). Otherwise a simpler pipe shell is used.
3. The shell is `sh`, or `su` when Root is active. Add or close sessions as needed.

### 3.12 Root, Shizuku and power ops

- With Root or Shizuku active, protected folders (`/data`, `/system`, `Android/data`,
  `Android/obb`) can be browsed, read, written, copied, moved and deleted.
- **Settings → Power ops**: chmod, chown, remount (`/`, `/system`, `/vendor`, `/product`,
  `/system_ext`, `/odm`), `pm install -r` and `pm uninstall`.
  1. Pick the operation and enter the target.
  2. Tap **Run…**.
  3. Read the **Run privileged operation?** dialog and confirm. Nothing runs without this
     confirmation, and each approval is good for that one action only.

### 3.13 App manager

1. Open the **Apps** tab. Filter user / system / all, search and sort.
2. Actions: Open, App info, Uninstall, **Backup** (APKs, optionally an `.apks` zip, and app data
   when Root is active). Backups go to the focused folder.
3. Restore: select a backup folder in Files, then **Tools → Restore app backup**.

### 3.14 Network, cloud and Wi-Fi transfer

**Network connections** (**Tools → Network connections**):

1. Tap add, choose FTP, FTPS, SFTP, SMB or WebDAV, fill host, port, username, password or private
   key, and save.
2. Open a connection: it appears as a tab, and you can copy/move between local and remote.

**Cloud drives** (same screen):

1. Tap **+ Google Drive**, **+ Dropbox** or **+ OneDrive**, then **Sign in** (your browser opens).
   A provider shows **(Not configured)** if the build has no client ID for it.
2. **+ S3 / R2 / MinIO**: enter bucket, region, endpoint, access key and secret
   (tick **Path-style addressing** for MinIO).

**Wi-Fi file transfer** (**Tools → Wi-Fi file transfer**, or the Quick Settings tile):

1. Turn on **Require PIN** if you want one, start the server.
2. On a computer on the same network, open the shown URL or scan the QR code.
3. Download and upload files into the focused folder. Tap **Stop** when done.

### 3.15 Media

- Long-press a folder → **View as gallery** for a fast thumbnail grid.
- **Play video**, **Play audio** (keeps playing in the background with notification controls),
  **View PDF**, **Preview font**, **Preview image**, **Media info**.
- **Share to File Manager**: share files from another app, pick a destination folder.
- Quick Settings tiles: **Open** and **Wi-Fi transfer**.

### 3.16 Secure folder

1. Requires a screen lock. Select files in Files, then **Tools → Secure folder**.
2. Unlock with fingerprint/face or your device PIN.
3. **Move in** encrypts the files (AES-256-GCM) and then deletes the originals.
   **Move out** decrypts into the focused folder.
4. It locks automatically (**Settings → Secure folder**: Immediately / 30 s / 1 min / 5 min) or
   with **Lock now**.
5. Warning: removing the screen lock or changing biometrics can make vault files unreadable. Move
   files out first.

### 3.17 AI assistant (optional)

1. **Settings → AI assistant**: enable it and enter a Base URL (OpenAI-compatible), model and your
   API key. Nothing is bundled; it is off by default.
2. In the code editor, select code (or nothing for the whole file), tap ✨, choose **Explain** or
   **Suggest edit**.
3. A dialog shows where the text goes and how big it is. **Nothing is sent until you tap Send.**
4. For suggestions, review the diff and tap **Accept** or **Reject**.

### 3.18 Plugins and scripts

**Plugins** (**Tools → Plugins**):

1. Select a plugin `.json` in Files, then **Install selected .json** — or **Add samples**
   (Checksums, File info).
2. Plugins install **disabled**. Review the capabilities and turn each on yourself.
3. Select files and tap an action chip; output shows in the log.

**Scripts** (**Tools → Scripts**): save `sh` scripts that run per file (`$FILE`, `$NAME`, `$DIR`)
or once on the selection. Scripts run without root.

### 3.19 Appearance

**Settings → Appearance**: System / Light / Dark / AMOLED, dynamic colour (Android 12+) and accent
colours.

### 3.20 Help

This manual is built into the app: open **Settings → Help & manual**, or tap the **?** icon in the
top bar.

---

## 4. Permissions and why each is needed

These are the permissions declared in the app's manifest.

| Permission | Why |
|---|---|
| `INTERNET` | Network shares, cloud drives, Wi-Fi transfer server, optional AI assistant |
| `ACCESS_NETWORK_STATE` | Check connectivity / find the Wi-Fi address for transfer |
| `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE` (Android 12 and lower only) | Browse and change files on older Android |
| `MANAGE_EXTERNAL_STORAGE` | "All files access" on Android 11+, needed for a real file manager |
| `REQUEST_INSTALL_PACKAGES` | Install APKs (you confirm every install) |
| `REQUEST_DELETE_PACKAGES` | Uninstall apps (you confirm every uninstall) |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Background audio playback |
| `POST_NOTIFICATIONS` | Media playback notification (Android 13+) |
| `QUERY_ALL_PACKAGES` | App manager lists and backs up every installed app |

The app also declares package visibility for the controller (`ai.droidcommand.app`, `.debug`) and
Shizuku. Its Quick Settings tiles use the standard `BIND_QUICK_SETTINGS_TILE`, and the Shizuku
library provider is guarded by `INTERACT_ACROSS_USERS_FULL` — these protect the app's own
components and are not fleet permissions.

---

## 5. Fleet integration

### How the controller connects

The controller binds to `FileManagerBridgeService` with the action
`ai.victorious.filemanager.action.BRIDGE` and sends a JSON request. Supported operations today:

| Operation | What it returns |
|---|---|
| `filemanager.capabilities.get` | What the node supports, whether control is enabled, pinning status |
| `filemanager.status` | App id/version, enabled state |
| `filemanager.list` | A stub that returns an empty list — it never exposes your files |

The bridge reports `live: false`: **it does not give the controller live control of your files.**

### Remote control is OFF by default

**Settings → Fleet → Allow external control** starts off. While off, only the two read-only
status operations answer (so the controller can show "installed but disabled"); everything else is
refused with `external_control_disabled`.

### Safety gates (checked in this order)

1. **Valid request** — malformed requests, wrong protocol version or wrong node id are rejected.
2. **Caller identity** — the caller's identity comes from Android itself (not from the message),
   and must be an allowlisted controller package, with a matching signing certificate if pins
   are built in. Otherwise: `unauthorized_caller`. This applies to every operation.
3. **External control toggle** — see above.
4. **Unknown operations** are refused (`unknown_operation`).

Separately, root/Shizuku changes inside the app always require your confirmation dialog.

---

## 6. Troubleshooting and FAQ

**I can't see my files.** Grant all-files access (banner → **Grant**, or Android Settings → Apps →
File Manager → Permissions / All files access).

**Install APK does nothing / is blocked.** Allow *Install unknown apps* for File Manager; the app
opens that page when needed. Android still asks you to confirm each install.

**Root says su is missing or denied.** Check your root manager granted File Manager, then toggle
Root off and on.

**Shizuku shows Not running.** Open Shizuku and start it (Wireless debugging needs re-starting
after each reboot), then tap **Re-check**.

**The controller says unauthorized or disabled.** Make sure the controller is an allowlisted
package (and signed with the pinned certificate if pins are set), and turn on **Allow external
control**.

**A cloud provider says Not configured.** That build has no client ID for it. Use a build that
includes one, or use S3-compatible storage.

**Large OneDrive/Dropbox uploads fail.** OneDrive simple uploads are limited to about 4 MB and
Dropbox to 150 MB in this version.

**Secure folder files won't decrypt.** The Keystore key may have been invalidated by changing the
screen lock or biometrics. This cannot be recovered.

**Where did my deleted file go?** Look in the **Trash** tab and tap **Restore**.

**Can I add new strings in APK mod?** No, only existing string text can change.

**Does this need DroidCommand AI?** No. Everything works without it.

---

## 7. Privacy and data handling

- **No accounts, no analytics.** The app works offline; it only uses the network for features you
  set up (network shares, cloud, Wi-Fi transfer, AI assistant).
- **Secrets are encrypted.** Network passwords and keys, cloud tokens, S3 keys and AI settings are
  stored in EncryptedSharedPreferences (Android Keystore) and are never logged.
- **AI assistant** is off by default, uses only the endpoint you enter, masks likely secrets before
  sending, and sends nothing until you confirm. Responses are not logged.
- **Wi-Fi transfer** serves only while running, on your local network, with an optional PIN.
- **Secure folder** files are encrypted at rest with a key that never leaves the device's
  Keystore, are stored in app-private storage and excluded from backup.
- **Signing keys**: the per-device debug key stays in app storage; your keystore passwords are only
  held in memory during signing.
- **Fleet bridge** never exposes your files and is off by default.
- **Trash** payloads on shared storage stay until you empty the trash.
- Uninstalling the app removes its private data (settings, secure folder, debug signing key).
