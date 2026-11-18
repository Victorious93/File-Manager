# File Manager — User Manual

This manual explains, in plain language, how to use **File Manager** (`ai.victorious.filemanager`;
debug builds are `ai.victorious.filemanager.debug`). It only describes features that exist in the
app today. Where a picture would help, you will see a marked placeholder such as
`[TODO screenshot: …]` — no real screenshots have been added yet.

In the app, type in the search box to find any word, and tap **Contents** (top right) to jump to a
chapter. If a word is new to you, look it up in the glossary (chapter 9). Chapter 8 explains what
each tool does.

Contents:

1. What File Manager is
2. Installing and first-run setup
3. Features, step by step
4. Permissions and why they are needed
5. Fleet integration
6. Troubleshooting and FAQ
7. Privacy and data handling
8. Tool reference (every entry in the Tools tab)
9. Glossary

---

## 1. What File Manager is

File Manager is a full Android file manager with power tools: two panes with tabs, batch file
operations with undo, search, bookmarks, a trash, editors (text, code, hex, diff), archives, APK
tools, a readable Java view of app code, a terminal, root/Shizuku tools, network and cloud drives,
Wi-Fi transfer, media viewers, an encrypted secure folder and plugins.

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

The main screen has a navigation bar: **Home, Files, Search, Apps, Tools, Trash, Settings**. On wide
screens (600 dp and up) it becomes a rail on the left and also shows **Bookmarks** and **Notes**. On a
phone, open Bookmarks and the Notes vault from the tiles on **Home**.

### 3.1 One pane or two, and tabs

- The **Files** tab opens with **one** file list. Tap the split-screen button in the top bar (or
  **Settings → Appearance → Two file panes**) to show **two** panes. On wide windows they sit side by
  side; on phones they stack. The choice is remembered.
- With two panes, tap a pane to make it the **focused pane**. Tools and copy/move use it. The pane you
  are not looking at keeps its folder and tabs, so switching back is instant.
- Each pane has tabs: tap **+** to open a new tab, tap a tab to switch, tap its ✕ to close it.
- Tap a folder to open it; tap a file to open it in the matching viewer.
- Use the **create** button to make a **New file** or **New folder** in the current folder.
- The **sort and view** button in the top bar offers **Name**, **Size**, **Modified** and **Type**, then
  **Ascending / Descending**, **Folders first** and **Show hidden**. This only changes how the list is
  shown; it never touches your files. The select-all icon selects everything in the folder.
- **Home** is a dashboard with a search box, a card for each storage volume (internal storage, SD card
  and the read-only system partition) showing how full it is, with an **Analyze** button, **Jump to**
  chips for common folders, **Quick tools** tiles (Terminal, Wi-Fi transfer, Network, Notes vault,
  Analyzer, Duplicates, Recycle bin, Bookmarks), your bookmarks, and **Recent files** from Download,
  DCIM, Documents, Pictures, Movies and Music. The app opens on Files; turn on
  **Settings → Appearance → Start on Home** to open on Home instead.

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

**Other file actions** (long-press a file or folder):

- **Properties / checksums** shows the path, modified time and size. For a folder it shows the number of
  files and folders and the total size; for a file it shows its **MD5, SHA-1 and SHA-256** checksums.
  Paste a published checksum into **Paste a checksum to compare** to see **Match** or **No match**.
- **Permissions** (shown when the app can read the file's permissions): type a mode such as
  `755` or `rwxr-xr-x` and tap **Apply mode**. For a folder, tick **Apply to contents** to change
  everything inside too.
- **Split file…** cuts a large file into numbered parts next to it (`name.001`, `name.002`, …). Type the
  part size as `50` (megabytes), `50mb`, `1.5 GB`, `700k` or `512b`. At most 999 parts are made, and it
  refuses if a part name already exists or the size is not smaller than the file.
- **Join parts** (shown on a `.001`, `.002`, … file) puts the parts back together as the original name,
  next to the parts. It refuses if a part is missing in the middle, or if a file with that name already
  exists, so it never produces a silently truncated file.

### 3.3 Search

1. Open the **Search** tab and type in **Search this device**.
2. Several words must all match (`beach plan`). `*.pdf` or `.pdf` filters by type. `/photos`
   matches part of the folder path.
3. Add filters to the same box and combine them freely:
   - `type:image`, `type:video`, `type:audio`, `type:doc`, `type:archive`, `type:apk` or `type:folder`
   - `size>10mb` or `size<500k` (units b, k, m, g; files only)
   - `newer:7d` or `older:1y` (units m, h, d, w, y). With `newer:` the freshest files come first.
   - A filter the app cannot read (for example `size>abc`) is treated as an ordinary search word.
4. The chips under the box (**Images, Videos, Audio, Documents, Archives, APKs, Recent, Large**) add or
   swap these filters with one tap. **Recent** is `newer:7d` and **Large** is `size>100mb`.
5. Tick **File contents** to search the text *inside* files in the focused folder instead of file names.
   Tick **Regex** to use a regular expression, and type patterns such as `*.smali, *.xml` in **Only files**
   to read only those files.
6. Results update as you type. The name index is rebuilt automatically after file changes.

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

- **Tools → Storage analyzer** scans the focused folder once, with a live count of files, folders
  and bytes (tap **Cancel** or Back to stop). Then:
  - **Folders** shows the biggest items first with a coloured bar. Tap a folder to go inside; this
    is instant because the first scan already sized every sub-folder. Back goes up a level.
  - **Largest files** lists the 100 biggest files anywhere under the folder. Tap the folder icon to
    open the file's folder, or the bin icon to move it to the trash.
  - **By type** shows how much space images, videos, audio, documents, archives, APKs and other
    files take.
  - Folders the app is not allowed to read (for example `Android/data` on newer Android) are skipped
    and counted in the summary line. Tap the refresh icon to scan again.
- **Tools → Find duplicates** scans the focused folder recursively and groups identical files. It
  shows its progress (listing, quick check, full check) and stops if you leave the screen.
  - Only files with the same size are read. Big files are first compared by their first and last
    16 KB, and only files that still match are read in full, on up to 4 threads.
  - Chips at the top: a minimum size (Any, 100 KB, 1 MB, 10 MB) and **Include hidden folders**
    (off by default, so `.thumbnails` and app caches are skipped).
  - Pick which copy to keep in each group: **Keep shortest path** (default), **Keep newest** or
    **Keep oldest**. Everything else in the group is ticked. **Trash N** moves the ticked files to the
    trash, so you can restore them.
  - A second scan in the same session reuses the results for files that have not changed, so it is
    much faster.
- From **Home**, the **Storage analyzer** and **Duplicates** tiles scan your whole internal storage,
  and the **Analyze** button on a storage card scans that drive.
- **Tools → Compare two folders** shows which files differ between two folders (see 3.7).

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
3. Turn on **Side by side**: changed lines are paired up and the exact words that changed are
   highlighted, so a one-number edit in a long line is easy to spot.

**Folder compare** (**Tools → Compare two folders**):

1. Either select exactly two folders, or open one folder in each pane with nothing selected.
2. The list shows files that are **changed**, **only left**, **only right**, or a **file vs folder**
   clash, with sizes and which side is newer. Filters: Differences, All, Only left, Only right.
3. **Exact** (default) checks file contents byte-for-byte (SHA-256). Tap it to switch to **Quick**,
   which only compares size and modified time: much faster on big folders, but it can miss an edit
   that kept the same size and time.
4. Tap a changed text file to open its line and word diff.
5. **Copy only-left → right** (or the reverse) copies the missing files and folders across. It asks
   first and never overwrites anything that already exists.

`[TODO screenshot: code editor]`

### 3.8 Archives

- Open an archive by tapping it, or long-press → **Open as archive**.
- Supported: ZIP/JAR/APK (list, extract, password-protected ZIP), 7z (including password-protected
  7z), TAR (plain, `.tar.gz`/`.tgz`,
  `.tar.bz2`, `.tar.xz`), single compressed files (`.gz`, `.bz2`, `.xz`), and RAR (list and extract
  only, including password RAR).
- Extraction goes to the folder in the *other* pane, or to the folder on screen when only one pane is
  shown. Paths that try to escape the folder are
  blocked.
- Create: **Zip selected** in the bottom bar for a quick ZIP, **Tools → Create password ZIP** for
  AES-256, or **Tools → Create archive** to pick a format:
  1. Select files and/or folders in Files (folders are added with everything inside, empty folders
     included).
  2. Open **Tools → Create archive**, type a name, choose **ZIP**, **7z**, **TAR**, **TAR.GZ**,
     **TAR.BZ2** or **TAR.XZ**, and a compression level (Store, Fast, Normal, Best).
  3. Optional, for **7z** and **ZIP** only: tick **Password protect (AES-256)** and type the
     password twice (**Show password** reveals it). File contents are encrypted with AES-256; file
     and folder *names* stay visible, as with 7-Zip's default for ZIP. A lost password cannot be
     recovered. TAR formats have no password option.
  4. Tap **Create**. The archive appears in the focused folder; if the name is taken you get
     `name (2).tar.gz` instead of overwriting.
  RAR is never created (licence). 7z and XZ use a memory-capped setting so large folders do not
  crash the phone; Best is slower but smaller.
- Opening a password 7z: tap it, then tap **Extract**. The app first tries passwords you saved
  earlier (without writing anything); if none fits it asks you, with **Remember** to save the one
  that worked. A wrong password shows "Wrong password" and leaves no half-written file. 7z files made
  with 7-Zip's "Encrypt file names" option can't be listed until you extract them with the password.

### 3.9 APK tools

Long-press an `.apk`:

- **APK info** — label, package, versions, SDK levels, signature schemes, permissions; uninstall
  from here.
- **Install APK** — Android shows its own confirmation for every install. The first time, allow
  *Install unknown apps* for File Manager.
- **Extract APK** — unzips into `<name>_extracted/`.
- **Sign APK…** — signing with a per-device debug key (made on first use) or your own PKCS12 keystore
  (`.p12` / `.jks`). You tick the signature schemes (V1, V2, V3, V4). **Verify signature** checks it.
- **Uninstall app…**, **Open APK bundle (splits)** (APKS/XAPK), **Share / export**.
- Long-press an extracted folder → **Rebuild APK from folder** (output is unsigned; sign it next).

**APK mod** (long-press → **APK mod**, or **Tools → APK mod** with one APK selected):

1. Edit manifest fields: package name, versionCode/versionName, minSdk/targetSdk, permissions,
   debuggable, exported components.
2. Edit existing strings in `resources.arsc`, or use **DEX / ARSC tools → Add strings** to create new ones (all locales) and optional DEX constants, then rebuild + sign.
3. Optionally paste one smali class to replace.
4. Tap **Rebuild + sign + verify**, then optionally **Install**.

**APK surgery / smali** (long-press → **APK surgery** or **Browse DEX → smali**):

1. Pick a dex, filter the class list, open a class.
2. Switch **Method list** / **Whole class smali**, tap **Edit**, save, then **Assemble**.
3. **String constants** lists every string literal in the dex.

**DEX / ARSC tools** (long-press APK → DEX / ARSC tools):

1. **Repair** — fix DEX magic, checksum (Adler-32), SHA-1 signature, file size; validate the map list; see what was fixed.
2. **Merge/Split** — merge `classes*.dex` with a duplicate-class winner (first / last / larger), rebalance under the 64k method limit, or split by class count.
3. **Decrypt** — preview Base64 / XOR / hex literals (apply writes a new DEX).
4. **Emulate** — find likely string-decryption methods and run a **restricted** interpreter (no reflection, IO, or network; timeouts). Opt-in write-back replaces calls with `const-string`.
5. **Add strings** — new ARSC string resources (all configs) and/or DEX constants → rebuild + sign.

**Other APK tools** in Tools: **Compare two APKs**, **Smali tools** (search, string-decryption
hints, apply `mapping.txt`), **Extract installed apps**.

**APK patch presets** (long-press → **APK patch presets (gated)**, or **Tools → APK patch presets** with
one APK selected): one-tap manifest flags and pattern-based edits, available only after you confirm that
you own the app or may legally change it. See 8.6 for what it offers and what it writes.

### 3.10 Java view

1. In a class view (APK surgery / DEX browser), tap **Decompile to Java**. Tap **Show smali** to
   switch back.
2. Search inside the Java text, copy it, or export the class as `.java`.
3. **Export all .java** writes a whole dex to a folder.

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
3. Restore: select a backup folder in Files, then **Tools → Restore app backup**. Encrypted backups
   ask for their passphrase first.
4. **Encrypted backups:** in the Backup dialog tick **Encrypt with a passphrase** and type it twice
   (at least 8 characters). Without the passphrase the backup cannot be restored, so keep it safe.

### 3.14 Network, cloud and Wi-Fi transfer

**Network connections** (**Tools → Network connections**):

1. Tap add, choose FTP, FTPS, SFTP, SMB or WebDAV, fill host, port, username, password or private
   key, and save.
   - **SFTP private key:** choose *Private key*, tap **Pick key file** (or paste the key), and
     enter the passphrase if the key has one. OpenSSH (`id_ed25519`, `id_rsa`, `id_ecdsa`) and
     PEM (`BEGIN RSA/EC PRIVATE KEY`, PKCS#8) keys work. The app checks the key before it lets
     you save and shows its type and fingerprint. A wrong passphrase or a public key (`.pub`)
     is refused with a clear message.
   - The key and passphrase are stored encrypted with a key held in the Android Keystore,
     never in plain files or backups.
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
- **Media library** (Tools → Media library): a YouTube Music-style home for the music and videos on
  your phone. See 3.24.

### 3.16 Secure folder

1. Requires a screen lock. Select files in Files, then **Tools → Secure folder**.
2. Unlock with fingerprint/face or your device PIN.
3. **Move in** encrypts the files (AES-256-GCM) and then deletes the originals.
   **Move out** decrypts into the focused folder.
4. It locks automatically (**Settings → Secure folder**: Immediately / 30 s / 1 min / 5 min) or
   with **Lock now**.
5. Warning: removing the screen lock or changing biometrics can make vault files unreadable. Move
   files out first.

### 3.17 AI: DroidCommand AI only

File Manager has **no AI model of its own and no place to enter an endpoint or API key**, and it never
contacts an AI service itself. The only AI it works with is **DroidCommand AI**, the fleet hub app, in two
separate ways:

1. **Ask Droidcommand AI (in the text / code editor).** The sparkle icon in the editor's top bar opens a
   dialog with **Explain** and **Suggest edit** (with an optional instruction). When you tap one, File
   Manager hands the file you are editing (at most its first 60,000 characters) to the DroidCommand AI app
   on your phone and shows what comes back.
   - A suggested edit appears as red and green changes. Tick the ones you want and tap **Apply**, or tap
     **Reject**. Applied edits go into the editor's undo history and are not saved until you save.
   - If DroidCommand AI is not installed the dialog offers **Get Droidcommand AI**. If the installed
     version cannot do code assist yet it says **Update Droidcommand AI**.
   - What DroidCommand AI does with the text (for example which model or service it uses) is decided by
     that app, not by File Manager.
2. **The bridge.** DroidCommand AI can also connect *to* File Manager through the local fleet bridge:
   - **Settings → Fleet → Allow external control** is **off** by default. Turn it on only if you want
     DroidCommand AI to be able to reach this app.
   - Only DroidCommand AI's packages (`ai.droidcommand.app` and its `.debug` build) are accepted as
     callers. Any other app is refused with `unauthorized_caller`.
   - Today the bridge only answers read-only status and capabilities; it does not expose your files.
     Details: `docs/FLEET_BRIDGE.md`.

### 3.18 Plugins and scripts

**Plugins** (**Tools → Plugins**):

1. Select a plugin `.json` in Files, then **Install selected .json** — or **Add samples**
   (Checksums, File info).
2. Plugins install **disabled**. Review the capabilities and turn each on yourself.
3. Select files and tap an action chip; output shows in the log.

**Scripts** (**Tools → Scripts**): save `sh` scripts that run per file (`$FILE`, `$NAME`, `$DIR`)
or once on the selection. Scripts run without root.

### 3.19 Appearance

**Settings → Appearance**: System / Light / Dark / AMOLED (the mode), plus a **Theme**: Fleet neon,
Classic, Cyber, Terminal, Ocean, Ember, Rose, Graphite and more. The app starts in **Dark + Fleet neon**
(near-black #0B0F1A with cyan and magenta), so no screen is white unless you choose **Light** yourself.
If you never changed the look before, updating moves you to Dark + Fleet neon once; a theme you picked
on purpose is kept. The video player, media library, Wi-Fi transfer, network and tool screens always
use the dark fleet colours. Every theme has its own light and dark look, so the
mode and the theme are chosen separately. **Accent** colours recolour the Classic theme only; the
others bring their own colours. **Dynamic colour** (Android 12+) takes colours from your wallpaper
and overrides the theme while it is on.

### 3.20 Help

This manual is built into the app: open **Settings → Help & manual** or **Tools → Help & manual**.

- Type in the search field to find any word; tap a result to jump to it.
- Tap **Contents** to see the chapter list and jump to a chapter.
- Links to other sections jump inside the manual; web links open in your browser only when you
  tap them.

### 3.21 Notes vault

The **Notes** tab is an Obsidian-style markdown vault (details in `docs/NOTES.md`).

1. Pick any folder (or accept the suggested app `Notes/` folder).
2. Create notes, use `[[Other note]]` links and `#tags`.
3. Split view shows a live preview. Backlinks and outgoing links appear under the editor.
   The preview understands tables (`| a | b |` with a `|---|---|` line), task lists (`- [ ] todo`,
   tap the checkbox to tick it in the note; this also works in notes saved on Windows, and keeps
   their line endings), nested lists, `~~strikethrough~~`, `==highlight==`,
   and web links (`[text](https://…)` or `<https://…>`, which open in your browser). Only `http`,
   `https` and `mailto` links are tappable.
4. **Quick switch** jumps to a note by title; **Daily** opens today’s note; **Graph** shows how notes link.
5. Search looks through note text (and the file index when available).

Everything stays on your device as normal `.md` files.

Use **Pick folder (Storage Access Framework)** to grant a vault directory. The graph view is a
draggable force-directed layout (pinch to zoom, drag nodes).

### 3.22 App Manager Pro

The **Apps** tab is App Manager Pro (details in `docs/APP_MANAGER.md`).

- Browse installed apps, multi-select for **Disable / Enable / Force-stop / Freeze**.
- Open an app for its components, permissions, AppOps and trackers. **Block** a component or
  **Block tracker components** in one tap.
- **Trackers** tab lists user apps that match a built-in signature list of advertising / analytics
  SDKs (class-name prefixes we maintain ourselves).
- **Logcat** with filters and export; **Usage** (needs the system "Usage access" permission);
  **Running** processes; **Profiles** of saved actions; **Backup** for APK (and data with root).

Actions that change an app always ask you to **Confirm** first, and the confirm box lists the exact
commands. Root or Shizuku is required for block / freeze / permission / AppOps changes.

- **Install… with options** works for a single `.apk`, an `.apks` / `.xapk` bundle, or a folder of
  split APKs. Type the path, pick the options, tap **Review**, then **Confirm**.
- **Logcat → Live** keeps the log scrolling as new lines arrive. Type a package name to follow just
  that app (Root/Shizuku). Turn **Live** off to stop.
- **Debloat** tab: tap **Import list…** and pick a text file with one package name per line. Choose
  Freeze, Disable or Uninstall for this user, check the list, then **Review & apply**. **Undo this list**
  reverses it. No list comes with the app, and important system apps, this app, your home screen and
  your keyboard are always skipped.

Profiles tab: edit steps, reorder, and optional schedule (WorkManager). Schedules are off until
you set once/daily/weekly. Apply still asks for confirmation.

### 3.23 Media Pro (video & music player)

Opening a video or song plays it in **Media Pro** (see `docs/MEDIA_PRO.md` for details).

- **Controls:** tap the screen to show/hide them. Double-tap the left or right side to jump 10 seconds.
  Swipe sideways to seek. Swipe up/down on the left side for brightness, on the right for volume.
- **Subtitles:** subtitle files with the same name as the video (`movie.srt`, `movie.en.ass`) load
  automatically. Tap **Subs** to pick any other subtitle file.
- **PiP:** tap **PiP**, or press Home while a video plays, to keep it in a small floating window.
- **⋮ menu:** playback speed, sleep timer, equalizer, queue (shuffle / repeat), playlists, open a
  network stream (web link, `rtsp://` camera, or `smb://` network share), and **Background play**
  (keep listening after you leave the player; on by default).
- **Music:** opening a song queues every song in that folder. Save the queue as a playlist from ⋮ → Playlists.
- **Resume:** videos and songs continue where you stopped.
- **Lock screen / notification:** play, pause and skip from the notification or lock screen.
- **Chapters:** if a video, audiobook or song has chapters inside it (MP4/M4B, MKV/WebM, MP3) or a
  `name.chapters.txt` file next to it, the chapter name shows above the controls and **⇤ Chapter /
  Chapter ⇥** jump between chapters. ⋮ → Chapters lists them.
- **Lyrics:** put a `.lrc` file with the same name next to a song (or use an MP3 with lyrics inside);
  the current line shows while it plays. ⋮ → Lyrics shows them all.
- **⋮ → Playback settings:** remember speed, subtitles on/off, chapter buttons, and a top quality for
  network streams. Shuffle and repeat are remembered too.
- **Victorious Keyboard:** with **Allow external control** turned on, the Victorious Keyboard (debug
  build) can play, pause, skip, seek, change the volume and read what's playing. It cannot do
  anything else in File Manager.

In the player **Options** sheet you can let `media.volume` also change the system media
stream (`STREAM_MUSIC`). Optional FFmpeg audio decoding (LGPL) is documented in
`docs/FFMPEG_MEDIA.md` — only packaged when natives were built and the Media3 decoder
module is on the classpath.

### 3.24 Media library and YouTube links

Open **Tools → Media library** (details in `docs/YOUTUBE_MEDIA.md`).

1. **Home** shows Continue watching & listening, Recently played, Favourites and Recently added, plus
   **Shuffle all songs** and **Play videos**.
2. **Songs**, **Videos**, **Artists**, **Albums** and **Folders** tabs. Tap an artist, album or folder,
   then **Play all**, **Shuffle** or **Add to playlist**.
3. Type in the search box to find any song or video by title, artist, album or file name.
4. Tap ☆ to favourite a row, **+** to add it to a playlist. **Playlists** tab plays, shuffles or deletes them.
5. Nothing showing? Give the app storage access, then tap **Rescan**.

**YouTube links tab:** paste a youtube.com, youtu.be or music.youtube.com link and tap **Open**. It
opens in the official YouTube Music or YouTube app (or your browser). **Save** keeps it in the list;
**Import from file** reads links from a text, `.url` or `.webloc` file. File Manager does not
download, play, or change YouTube content, and it does not block ads.

### 3.25 APK deep edit: DEX repair, merge, strings and translation

Under APK tools (see `docs/MT_NP_FINISH.md`):

- **Repair DEX** fixes checksums and size after manual edits.
- **Merge / split** `classes*.dex` files.
- **String decrypt** previews XOR/Base64-looking strings; you choose the method before anything changes.
- **Edit strings** in DEX or resources; **Translation mode** compares locales, exports CSV/XML, and
  can build a prompt that you copy into any AI assistant you choose. You paste the reply back and approve it
  before anything is applied; File Manager does not contact the assistant itself.
- **Add a new language** (Translation tab): pick a chip or type a code such as `eo` or `pt-BR`,
  tap **Fill with default strings**, replace the text after each tab with your translation, then
  tap **Add language and build APK**. You get `<app>-<code>.apk`, signed with the File-Manager
  debug key. Strings you leave out show in the default language. A language the APK already has
  is refused; edit it with Apply instead.

### 3.26 Folder sync

Tools → **Folder sync**: for each side choose **Local** or any saved connection (SFTP, FTP, SMB,
WebDAV or a cloud drive) and type the folder path. Choose one-way or two-way and a conflict
policy, preview with dry-run, then run. Local deletes go to the trash.

**Scheduled sync:** set *Every N hours*, optionally *Wi-Fi only* and *Only while charging*, then
tap **Save + schedule**. The job runs in the background with Android's WorkManager when those
conditions are met (remote jobs always wait for a network). Failed network runs retry with
back-off, and each job in the list shows its last result. Tap **Remove** to stop a schedule.

### 3.27 My PC (your PC over SFTP)

Tools → **My PC** opens your own Ubuntu PC in either pane over SFTP. Full guide: `docs/MYPC.md`.

1. Tap **+** and enter a name, your PC user, the PC's addresses (LAN first, then a Tailscale
   `*.ts.net` name for away from home), the port, and optionally its host key
   (`ssh-ed25519:SHA256:…`, from `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` on the PC).
2. Tap **Key**, **Copy line**, and add that line to `~/.ssh/authorized_keys` on the PC. The key is
   made by and kept inside this phone's Keystore; only the public half is shown.
3. Tap **Open in pane**. If you did not type the host key, compare the one shown with the PC and tap
   **It matches, pin it**.

**Host key changed?** The app blocks the connection with a red warning and never offers to connect
anyway. If you reinstalled the PC yourself, remove it and add it again.

**Copy / Move** between a phone folder and a PC folder runs in the background with a progress
notification. It writes `name.partial`, resumes after a drop, checks size and SHA-256, and only then
uses the real name. Move deletes the source only after that check.

### 3.28 Server mode (FTP / SFTP / web)

Tools → Wi-Fi file transfer / Server mode: start a **LAN-only** FTP, SFTP, or web file manager
for the focused folder. Off by default; password or PIN shown with a QR code. Stop when done.

### 3.29 Photo tools

Tools → **Photo tools** (select images first): EXIF view/edit/strip, batch rename with a
preview, and batch resize/convert/compress.

---

## 4. Permissions and why each is needed

These are the permissions declared in the app's manifest.

- `INTERNET` — Network shares, cloud drives, Wi-Fi transfer server
- `ACCESS_NETWORK_STATE` — Check connectivity / find the Wi-Fi address for transfer
- `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE` (Android 12 and lower only) — Browse and change files on older Android
- `MANAGE_EXTERNAL_STORAGE` — "All files access" on Android 11+, needed for a real file manager
- `REQUEST_INSTALL_PACKAGES` — Install APKs (you confirm every install)
- `REQUEST_DELETE_PACKAGES` — Uninstall apps (you confirm every uninstall)
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` — Background audio playback
- `POST_NOTIFICATIONS` — Media playback notification (Android 13+)
- `QUERY_ALL_PACKAGES` — App manager lists and backs up every installed app

The app also declares package visibility for the controller (`ai.droidcommand.app`, `.debug`) and
Shizuku. Its Quick Settings tiles use the standard `BIND_QUICK_SETTINGS_TILE`, and the Shizuku
library provider is guarded by `INTERACT_ACROSS_USERS_FULL` — these protect the app's own
components and are not fleet permissions.

---

## 5. Fleet integration

### How the controller connects

The controller binds to `FileManagerBridgeService` with the action
`ai.victorious.filemanager.action.BRIDGE` and sends a JSON request. Supported operations today:

- `filemanager.capabilities.get` — What the node supports, whether control is enabled, pinning status
- `filemanager.status` — App id/version, enabled state
- `filemanager.list` — A stub that returns an empty list — it never exposes your files

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

### Fleet remote-control envelope v1 (protocol 1.1)

Newer controllers use the frozen fleet envelope (full detail in `docs/FLEET_BRIDGE.md`):

- `capabilities.get` — Lists every operation with its kind (read / action / destructive), whether it may be called remotely, and whether it needs your confirmation
- `node.status.get` — A small health snapshot: "alive", whether external control is on, and the Shizuku state. **It never includes file names, paths or file contents.**
- `remote.command`, `macro.step`, `macro.run` — Run one advertised operation, one macro step, or a short macro (with a "dry run" that only checks, never executes)

What protects you:

- **Who is calling is decided by Android** (the caller's package and signing certificate), never by
  anything written inside the message — including SMS trigger information.
- **External control stays off until you turn it on.**
- Every request carries a one-time code and a timestamp. Old requests (older than their time
  limit, or more than 2 minutes off from your phone's clock) are refused, and a repeated request
  is refused as a replay.
- **Anything destructive always needs your confirmation on this device.** A remote request is never
  approved automatically. When one arrives you get a notification and a full-screen prompt showing
  which app is asking, the operation, the action and a short summary of its settings (passwords,
  tokens and file paths are hidden). Tap **Approve** or **Deny**. If you do nothing, it is denied
  when the request's time limit runs out (at most 5 minutes). Each answer applies to that one
  request only. Right now File Manager has no remote destructive operation at all.
- **Victorious Keyboard** (debug build, matching certificate only) may use media controls only.
  Those controls are not built into File Manager yet, so they answer "capability off". The release
  keyboard is not allowed yet.

**Settings backup (`config.export` / `config.import`).** File Manager can describe its settings
(preferences and bookmarks only — never your files, passwords, tokens or cloud logins, and never
bookmarks pointing into the secure folder) in the fleet backup format. Remote controllers **cannot**
call these: both are local-only by default. Importing always needs your confirmation on the device,
offers a "dry run" preview, lets you choose skip / overwrite / merge-by-id, and always turns external
control **off** afterwards.

**Settings → Backup & restore**

1. **Export to file** — choose where to save `victorfiles-settings.json`. The file is encrypted with
   a key stored in this phone's secure keystore, so it can be restored on **this phone (this
   install)** only. Uninstalling File Manager destroys the key.
2. **Import from file** — pick the file. You first see a **preview** (nothing is changed yet) of how
   many preferences and bookmarks would be added, overwritten or skipped.
3. Choose a mode: **skip** (keep what you have, ignore the file's version of existing items),
   **overwrite** (the file wins) or **merge-by-id** (keep yours, add anything new).
4. Tap **Confirm import**. External control is switched off afterwards; turn it back on yourself if
   you want it.

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

**Can I add new strings in APK mod?** Yes — **DEX / ARSC tools → Add strings** (Phase 16) adds new `string/<name>` resources in every config and optional DEX constants, then rebuilds and signs. The older APK-mod string list still edits existing text only. Compact ARSC tables are not supported.

**Does this need DroidCommand AI?** No. Everything works without it.

**My PC says "Connection blocked".** The PC answered with a different SSH host key than the one
pinned. Nothing was sent. If you reinstalled the PC, remove it in Tools → My PC and add it again.

**My PC says the key was refused.** Add the line from **Key** to `~/.ssh/authorized_keys` for that
user on the PC (one line), then try again.

**A tool says "Select exactly 2 files" (or 1 APK).** Some Tools need a specific selection. Go back to
**Files**, select what the message asks for, then open the tool again (see 8.1).

**A tool works on the wrong folder.** Tools use the **focused pane**. With two panes, tap the pane you want,
then open the tool.

**Android/data looks empty, or the analyzer skips it.** Recent Android versions block normal apps from it,
even with all-files access. Turn on Root or Shizuku (Settings → Privilege) to browse it (see 3.12).

**My colour tags or file snapshots disappeared.** They are kept only in the app's private storage, so
uninstalling the app or clearing its data removes them. The settings backup holds preferences and
bookmarks only, not tags or snapshots.

**A modded or patched app will not install over the original.** Re-signing gives the app a different
signature, and Android refuses to update an app with a different signature. Uninstall the original first;
this removes its data.

**A tool is missing from the Command palette.** The palette is a shortcut list. The compare tools, APK tools,
Translate strings.xml, archive tools and Restore app backup are only in the Tools tab (see 8.2).

---

## 7. Privacy and data handling

- **No accounts, no analytics.** The app works offline; it only uses the network for features you
  set up (network shares, cloud, Wi-Fi transfer).
- **Secrets are encrypted.** Network passwords and keys, cloud tokens and S3 keys are
  stored in EncryptedSharedPreferences (Android Keystore) and are never logged.
- **No AI inside the app.** File Manager has no AI key and never contacts an AI service itself. If you tap
  **Ask Droidcommand AI** in the editor, the text you are editing is handed to the DroidCommand AI app on
  your phone; what that app does with it is up to that app. DroidCommand AI can also connect to File
  Manager through the fleet bridge (off by default). In translation mode you copy a prompt out yourself.
- **Wi-Fi transfer** serves only while running, on your local network, with an optional PIN.
- **Secure folder** files are encrypted at rest with a key that never leaves the device's
  Keystore, are stored in app-private storage and excluded from backup.
- **Signing keys**: the per-device debug key stays in app storage; your keystore passwords are only
  held in memory during signing.
- **Fleet bridge** never exposes your files and is off by default.
- **Colour tags and file snapshots** are stored only in the app's private storage and are removed when you
  uninstall the app.
- **Trash** payloads on shared storage stay until you empty the trash.
- Uninstalling the app removes its private data (settings, secure folder, debug signing key).

---

## 8. Tool reference

This chapter explains every entry in the **Tools** tab: what the tool is for, how to open it, what you
must select first, and whether it changes your files. Chapter 3 has the step-by-step instructions for
the bigger tools; links in each entry point there. Words you do not know are in the glossary
(chapter 9).

### 8.1 How the Tools tab works

- **Tools act on the focused pane.** The first line of the tab shows the folder they will use. With two
  panes, tap the pane you want first.
- **Many tools need a selection.** Select files or folders in **Files** first, then open **Tools**. The
  entry shows how many items are selected, for example "(2 selected)". If the selection is not right,
  a short message tells you what to pick, such as "Select exactly 2 files in Files, then come back".
- **Every entry below has a "Changes files" line.** *No* means the tool only reads or shows
  information. *Yes* tells you exactly what it writes and where.
- **Lost?** Open the **Command palette** and type a few letters of the tool's name.
- Some tools can also be opened from the long-press menu on a file (see 3.7 to 3.10) or from **Home**.

### 8.2 Find and organise

#### Command palette

A fast way to open a tool: type a few letters instead of scrolling.

- **Open it:** Tools → Command palette (the first entry).
- **Needs:** nothing.
- **Changes files:** No. It only opens other tools.
- **How it works:** it matches what you type against each tool's name and its keywords (type `hash`
  to find Utilities, `vault` to find Secure folder, `mirror` to find Folder sync). Tools you used
  recently are listed first and marked **Recent**. Tap a result to close the palette and open the tool.
- **Good to know:** it is a shortcut list, not the whole Tools tab. The compare tools, the APK tools,
  Translate strings.xml, Create archive, Create password ZIP and Restore app backup are not in it; open
  those from the Tools tab. Tools that need a selection use what was selected when you opened the palette.

#### Colour tags & smart collections

Label files with colours, find them again by colour, and run ready-made searches.

- **Open it:** Tools → Colour tags & smart collections.
- **Needs:** files selected if you want to tag them. Browsing and smart collections need no selection.
- **Changes files:** No. Tags are kept in the app's private storage, not on your files.
- **Tag files:**
  1. Select files in Files, then open the tool.
  2. Tap a colour dot to add that tag. A ✓ appears when every selected file has it. Tap the dot again
     to remove the tag from all selected files. There are seven colours: Red, Orange, Yellow, Green,
     Blue, Purple and Grey, and a file can carry several.
- **Browse by colour:** tap a colour chip to list every tagged file. Tap a result to open it in Files.
  Tags of files that no longer exist are forgotten when you browse.
- **Smart collections** are saved searches that look inside the focused folder (the tool may show
  "Scanning…" while it builds the file index). Five come with the app:
  - **Recent documents**: PDF, Word, OpenDocument text, text, Markdown, Excel and PowerPoint files
    changed in the last 7 days.
  - **Large videos**: video files over 500 MB.
  - **APKs**: `.apk`, `.apks` and `.xapk` files.
  - **Screenshots this month**: files with "screenshot" in the name changed in the last 31 days.
  - **Tagged red**: everything you tagged Red.
- **Good to know:** this version has no editor for your own smart collections; the five above are the
  only ones. At most 200 matches are listed.

#### File version snapshots

Keep earlier copies of a file and go back to one if an edit goes wrong.

- **Open it:** Tools → File version snapshots.
- **Needs:** a file selected in Files (the first selected file is used; folders are ignored). Files you
  already snapshotted are listed under **Tracked files**, so you can pick one without selecting it.
- **Changes files:** only when you tap **Restore**, which replaces the file with the snapshot. Restoring
  first takes a snapshot of the current content, so you can undo it.
- **Buttons:**
  - **Take snapshot now** saves the file as it is. If nothing changed since the last snapshot you are
    told so and nothing is stored.
  - **Diff vs now** shows a line-by-line difference from that snapshot to the current file. It works on
    text files up to 2 MB.
  - **Restore** and **Delete** act on that one snapshot.
- **Good to know:** snapshots are only taken when you tap the button; nothing is saved automatically.
  They are private copies in app storage, up to 20 per file, with identical versions stored once. They use
  phone space and are removed when the app is uninstalled.

### 8.3 Compare

#### Compare two files

A line-by-line (and word-by-word) difference between two text files. Full steps in 3.7.

- **Open it:** select exactly 2 files in Files, then Tools → Compare two files.
- **Changes files:** No.

#### Compare two folders

Shows which files differ between two folders. Full steps in 3.7.

- **Open it:** select exactly 2 folders, or open one folder in each pane with nothing selected, then
  Tools → Compare two folders.
- **Changes files:** only if you tap **Copy only-left → right** (or the reverse). It asks first and never
  overwrites a file that already exists.
- **Good to know:** **Exact** mode compares contents (SHA-256) and is the safe default. **Quick** only
  compares size and modified time and can miss an edit that kept both the same.

#### Compare two APKs

Shows what changed between two versions of an app: manifest, permissions, DEX classes and resources.

- **Open it:** select exactly 2 `.apk` files in Files, then Tools → Compare two APKs.
- **Changes files:** only if you tap **Save report**, which writes
  `apk-compare-<first>-vs-<second>.txt` into the focused folder.

### 8.4 Storage

#### Storage analyzer

Scans a folder once and shows where the space went. Full steps in 3.6.

- **Open it:** Tools → Storage analyzer (focused folder), or the Analyzer tile or **Analyze** button on Home.
- **Changes files:** only if you tap the bin icon on a file in **Largest files**, which moves it to the trash.
- **Good to know:** folders Android does not let the app read (for example `Android/data` on recent
  versions) are skipped and counted in the summary line.

#### Find duplicates

Finds files with identical contents. Full steps in 3.6.

- **Open it:** Tools → Find duplicates (focused folder), or the Duplicates tile on Home.
- **Changes files:** only when you tap **Trash N**, which moves the ticked copies to the trash (you can
  restore them). Each group keeps one copy by default (shortest path, newest or oldest, as you choose).
- **Good to know:** a file is only called a duplicate after its full contents match, not just its name.

### 8.5 Archives

#### Create archive

Packs the selected files and folders into one archive. Full steps in 3.8.

- **Open it:** select files and folders, then Tools → Create archive.
- **Formats:** ZIP, 7z, TAR, TAR.GZ, TAR.BZ2, TAR.XZ. Levels: Store, Fast, Normal, Best.
- **Changes files:** it adds a new archive to the focused folder. It never overwrites; a taken name becomes
  `name (2).tar.gz`. Your originals are not touched.
- **Good to know:** password protection (AES-256) is available for ZIP and 7z only. File names stay
  visible. A lost password cannot be recovered. RAR files can be opened but never created.

#### Create password ZIP

A quick AES-256 ZIP from the selection, asking only for a password.

- **Open it:** select files, then Tools → Create password ZIP.
- **Changes files:** it adds a new ZIP to the focused folder. Your originals are not touched.
- **Good to know:** to also choose another format or a compression level, use **Create archive** instead.

### 8.6 APK and app tools

These tools edit app packages. Only change apps you own or are allowed to change. Editing and re-signing
an app gives it a new signature. Android refuses to update an app with a different signature, so you usually
have to uninstall the original first, which removes its data.

#### APK mod

Edits an APK's manifest, strings and a smali class, then rebuilds, signs and verifies it. Full steps in 3.9.

- **Open it:** select exactly 1 `.apk`, then Tools → APK mod (or long-press the APK → APK mod).
- **Changes files:** it writes `<name>_mod_signed.apk` into the focused folder; the original stays as it is.
  Installing is a separate, optional step and Android asks you to confirm.

#### APK patch presets

One-tap, static edits to an APK, guarded by an ownership confirmation.

- **Open it:** select exactly 1 `.apk`, then Tools → APK patch presets (or long-press the APK → APK patch
  presets (gated)).
- **The gate:** before anything is applied you must confirm that you own the app or are legally allowed
  to change it. The confirmation is remembered per package name **and** signing certificate. Use
  **Revoke** to take it back. There is no way around the gate.
- **What it offers:**
  - **Manifest flags (one-tap):** debuggable, allowBackup, usesCleartextTraffic and extractNativeLibs.
  - **Signature / license check (pattern scan):** **Scan for candidates** lists methods that look like a
    signature or licence check, and why. You choose, one by one, whether to leave each alone or make it
    always return true or false. No list of apps ships with the tool.
  - **Developer aids:** remove `Toast.show()` calls in one class, or add a `Log.d` line (tag and message you
    type) at the start of one method.
- **Steps:** authorise, choose presets, tap **Preview** to see the exact changes as a diff, then **Apply +
  sign**. The result is saved as `<name>_patched.apk` in the focused folder, signed with this device's debug
  key. **Undo** restores the original; **Install** hands it to Android, which asks you to confirm.
- **Not included:** "Trust user CAs" is shown but cannot be applied in this version; use the
  usesCleartextTraffic flag if you only need plain HTTP.
- **Changes files:** it writes `<name>_patched.apk`. It does no runtime hooking.

#### DEX / ARSC tools

Repair, merge, split, decrypt and edit the compiled code (DEX) and compiled resources (ARSC) inside an
APK. Details in 3.9 ("DEX / ARSC tools") and 3.25.

- **Open it:** select one `.apk`, `.dex` or `resources.arsc`, then Tools → DEX / ARSC tools.
- **Changes files:** it writes new files into the focused folder and keeps the original. Examples:
  `<name>-repaired-classes.dex`, `<name>-merged.dex`, `<name>-decrypted.dex`, a `<name>-split` or
  `<name>-rebalanced` folder of DEX files, and `<name>-added-strings.apk`.
- **Good to know:** the string-decryption emulator runs a restricted interpreter with no reflection, no
  file access, no network and a time limit.

#### Smali tools

Search an app's code and make sense of obfuscated names.

- **Open it:** select one `.apk` or `.dex` (and optionally its `mapping.txt`), then Tools → Smali tools.
- **What it does:** search classes, methods and strings; hint at string-decryption routines; apply a
  `mapping.txt` so shortened names show their original names.
- **Changes files:** searching only reads. **Export smali** (or **Export deobfuscated smali** when a
  `mapping.txt` is selected) writes the class files into a new folder in the focused folder.

#### Translate strings.xml

A side-by-side editor for translating an app's text.

- **Open it:** in Files select the app's decoded `res/values/strings.xml` (and optionally the target
  `values-xx/strings.xml`, or a `.csv` to import), then Tools → Translate strings.xml.
- **Steps:**
  1. Each row shows the string name and its source text with a box beside it for your translation.
  2. If there is no target file yet, type the target locale (such as `es` or `pt-rBR`) and tap **Set**.
  3. Use the **Only untranslated** chip to see what is left. The counter shows how many are done.
  4. Tap **Save target** to write `values-<locale>/strings.xml`, **Export CSV** to write
     `strings-<locale>.csv` into the focused folder, or **Import** to load translations from the selected CSV.
- **Changes files:** it writes the target `strings.xml` or the CSV only when you tap those buttons.
- **Good to know:** strings marked `translatable="false"` are skipped, markup such as `<xliff:g>` is kept,
  and plurals and arrays are left alone. Translating compiled resources inside an APK is a different
  feature (3.25).

#### Extract installed apps

Copies the APK files of apps installed on the phone into a folder.

- **Open it:** Tools → Extract installed apps.
- **Changes files:** it adds the copies (the base APK and any split APKs) to the focused folder.
- **Good to know:** for a proper backup that can be restored, including optional encryption, use the
  **Backup** action in the **Apps** tab instead.

#### Restore app backup

Installs apps back from a backup made in the Apps tab. Steps in 3.13.

- **Open it:** select exactly one backup folder (it contains `manifest.json`), then Tools → Restore app backup.
- **Changes files:** it installs apps; Android asks you to confirm. An encrypted backup asks for its
  passphrase first. Restoring app data needs Root.

### 8.7 Network, sharing and transfer

#### Network connections

Connects to FTP, FTPS, SFTP, SMB and WebDAV servers, and to cloud drives and S3 storage. Steps in 3.14.

- **Open it:** Tools → Network connections (or the Network tile on Home).
- **Changes files:** yes, on whatever side you copy, move or delete.
- **Good to know:** passwords, keys and tokens are stored encrypted with a key kept in the Android Keystore.

#### Wi-Fi file transfer

Turns the phone into a small web server so a computer on the same network can download and upload files.

- **Open it:** Tools → Wi-Fi file transfer, the Wi-Fi transfer tile on Home, or the Quick Settings tile.
- **Changes files:** yes, uploads from the computer land in the focused folder.
- **Good to know:** it only runs while you keep it started, only on your local network, and can ask for a
  PIN. Tap **Stop** when done. Server mode (FTP / SFTP / web) is described in 3.28.

#### Share Wi-Fi as QR

Makes a QR code that lets another phone join a Wi-Fi network by scanning it with its camera.

- **Open it:** Tools → Share Wi-Fi as QR.
- **Steps:** type the network name (SSID), choose the security (**WPA**, **WEP**, **SAE** or **Open**),
  type the password, and tick **Hidden network** if it does not broadcast its name. The code appears and
  updates as you type.
- **Changes files:** No. Nothing is stored or sent anywhere; the code is drawn on screen only.
- **Good to know:** the password is in the code, so anyone who scans it can join. Close the screen when
  you are done. **Show password** reveals what you typed.

#### Folder sync

Keeps two folders in step, on this phone or on a connection you saved. Steps in 3.26.

- **Open it:** Tools → Folder sync.
- **Changes files:** yes, on the side(s) it syncs. Always run a **dry run** first. Local deletes go to the trash.

#### My PC

Opens your own computer over SFTP in a pane. Steps in 3.27.

- **Open it:** Tools → My PC.
- **Changes files:** yes, when you copy or move between phone and PC. A copy is written as `name.partial`,
  checked, and only then renamed, so a dropped connection never leaves a half file under the real name.

### 8.8 Security

#### Secure folder

An encrypted vault that opens only with your fingerprint, face or screen lock. Steps in 3.16.

- **Open it:** select files in Files, then Tools → Secure folder.
- **Changes files:** yes. **Move in** encrypts the files and then deletes the originals; **Move out**
  decrypts them into the focused folder.
- **Good to know:** changing your screen lock or biometrics can make vault files unreadable for good.
  Move files out first.

### 8.9 Developer and utility tools

#### Terminal

A command line on the phone. Steps in 3.11.

- **Open it:** Tools → Terminal, or the Terminal tile on Home.
- **Changes files:** whatever you type can. It runs as a normal shell, or as root if Root is on.

#### Scripts

Save small `sh` scripts and run them on the selected files. Steps in 3.18.

- **Open it:** Tools → Scripts.
- **Changes files:** whatever your script does. It runs without root.
- **Good to know:** a script can run once for each file (`$FILE`, `$NAME` and `$DIR` are filled in) or once on the whole selection.

#### Plugins

Adds extra actions from small JSON plugin files. Steps in 3.18.

- **Open it:** Tools → Plugins.
- **Changes files:** depends on the plugin. Plugins install **disabled**; read what each one declares it
  can do and switch it on yourself.

#### Utilities

A small toolbox of everyday helpers in five tabs.

- **Open it:** Tools → Utilities (select a file first if you want to hash it).
- **Changes files:** No. Everything is calculated on screen.
- **Hash:** MD5, SHA-1, SHA-256 and SHA-512 of text you type, or of the selected file (**Hash file**).
  Paste a published checksum into **Expected hash** and a ✓ match appears beside the algorithm that fits.
- **Base64 / Hex:** encode or decode text (standard or URL-safe Base64, and hexadecimal).
- **Timestamp:** turn a Unix time (seconds or milliseconds) or a date into seconds, milliseconds, UTC and
  local time, or tap **Now**.
- **Color:** type `#RGB`, `#RRGGBB`, `#AARRGGBB` or `rgb()` / `rgba()`, adjust R, G, B and A sliders, and
  read the value as hex, RGB, HSV and an Android colour integer.
- **Regex:** test a regular expression against sample text with the **Ignore case**, **Multiline** and
  **Dot all** options. It lists each match and its groups, and can show the result of a replacement
  (`$1` for a group).

#### Photo tools

Edit and shrink photos. Select images first. More in 3.29.

- **Open it:** select image files in Files, then Tools → Photo tools. With nothing selected you are told to select images.
- **What it does:** view, set or remove EXIF data (**Set tag**, **Strip EXIF**), rename a batch with a pattern
  and start index (**Apply rename**), and resize, convert or compress a batch (**Run batch**) with a maximum
  width and height and a JPEG/WebP quality from 1 to 100.
- **Changes files:** yes, when you tap the action buttons. Check the preview first.
- **Good to know:** removing EXIF strips information such as the camera, time and, if present, the
  location, which is useful before you share a photo.

### 8.10 Media and help

#### Media library

A home for the music and videos on your phone. Steps in 3.24.

- **Open it:** Tools → Media library.
- **Changes files:** No. It lists media and builds playlists in the app. YouTube links are handed to the
  official YouTube apps or your browser; File Manager does not download or alter them.

#### Help & manual

This manual, searchable and available offline.

- **Open it:** Tools → Help & manual, or Settings → Help & manual.
- **Changes files:** No.

### 8.11 Tools that are not in the Tools tab

Some tools live elsewhere because they work on one file or one setting:

- **Text, code and hex editors, image / video / audio / PDF / font viewers, Media info**: long-press a file
  (3.7, 3.15).
- **Archive browser**: tap an archive (3.8).
- **APK info, sign, verify, APK surgery, smali browser, Java view**: long-press an APK or class (3.9, 3.10).
- **Properties / checksums, permissions (chmod), Split file, Join parts**: long-press a file (3.2).
- **Storage tiles, Bookmarks, Recycle bin, Notes vault**: Home (3.1).
- **Power ops (chmod, chown, remount, install, uninstall)**: Settings → Power ops (3.12).
- **App Manager Pro, Debloat, Logcat**: the Apps tab (3.22).
- **Backup & restore of settings**: Settings → Backup & restore (chapter 5).

---

## 9. Glossary

Plain-language meanings of the words you meet in File Manager, in alphabetical order. Where a word means
something specific in this app, that is said. Type any term in the manual search box to find where it is used.

### 9.1 A to F

- **AES-256**: a strong, widely used way of scrambling data so only someone with the password or key can read it. Used for the secure folder, password ZIP and 7z, encrypted app backups and the settings export.
- **All files access**: the Android 11+ permission (`MANAGE_EXTERNAL_STORAGE`) that lets a file manager see all shared storage, not only its own folders. Without it File Manager only sees its own folders.
- **AMOLED**: the appearance mode named "AMOLED black", which uses a black background; on AMOLED screens black pixels use very little power.
- **APK**: an Android app package, the file you install. Ends in `.apk`.
- **APKS / XAPK (bundle)**: a bundle of one base APK plus extra "split" APKs (for example for one screen density or language) that Android installs together.
- **AppOps**: Android's per-app switches for sensitive actions. The Apps tab can show them; changing them needs Root or Shizuku.
- **ARSC / resources.arsc**: the compiled table of an app's strings, colours and other resources, stored inside the APK.
- **Backlink**: in the Notes vault, a list of the notes that link to the note you are reading.
- **Base64**: a way of writing binary data as plain text letters and digits. It is encoding, not encryption.
- **Batch rename**: renaming many files at once with find/replace, case changes, counters and a template, with a preview before anything changes.
- **Bookmark**: a saved shortcut to a folder.
- **Bridge (fleet bridge)**: the small Android Messenger connection through which the DroidCommand AI controller can talk to File Manager. Off until you turn on **Allow external control**.
- **Checksum / hash**: a short fingerprint of a file's contents (MD5, SHA-1, SHA-256, SHA-512). If two files have the same SHA-256, they are identical. Used to check a download or find duplicates.
- **Cleartext traffic**: network traffic sent as plain HTTP, without encryption. Android apps can forbid it in their manifest.
- **Cloud drive**: online storage such as Google Drive, Dropbox or OneDrive that you sign in to from File Manager.
- **Colour tag**: one of seven coloured labels (Red, Orange, Yellow, Green, Blue, Purple, Grey) you can put on any file to find it later.
- **Confirm gate / confirmation dialog**: the box that lists exactly what a privileged action will do. Nothing runs until you approve, and each approval covers that one action only.
- **Controller**: in the Victorious fleet, the hub app (DroidCommand AI) that can send requests to File Manager through the bridge.
- **Debloat**: removing, disabling or freezing pre-installed apps you do not want, from a list you import.
- **Debug key**: a signing key File Manager creates on first use on this device, used to sign APKs you rebuild. Debug-signed apps are meant for testing, not for publishing.
- **DEX**: the compiled code of an Android app, stored in `classes.dex` files (and `classes2.dex` and so on) inside the APK.
- **Diff**: a view of exactly which lines (and words) differ between two versions of a text.
- **Dry run**: a preview that shows what an operation would do without doing it. Folder sync and the fleet's macros have one.
- **Duplicate**: a file whose full contents are identical to another file, whatever its name.
- **EXIF**: information stored inside a photo, such as the camera, the time it was taken and sometimes the location.
- **External control**: the setting (**Settings → Fleet → Allow external control**) that lets the controller use the bridge. Off by default.
- **EXTERNAL_NODE**: the role File Manager has in the Victorious fleet: it can be reached by the controller but never depends on it.
- **FFmpeg**: an open-source media library. File Manager can use it to decode extra audio formats when it was built with it.
- **Fingerprint (key or certificate)**: a short checksum that identifies a key or certificate, shown so you can compare it with the real one.
- **Focused pane**: the pane you tapped last. Tools, paste and extraction use its folder.
- **Foreground notification**: the notification Android requires while an app keeps doing work in the background, such as playing music.
- **FTP / FTPS**: file transfer protocols. FTPS adds encryption. Plain FTP sends everything, including your password, unencrypted.

### 9.2 G to O

- **Glob**: a simple filename pattern such as `*.pdf`, where `*` stands for any text.
- **Hex editor**: an editor that shows a file as raw bytes (offset, hexadecimal values and text) so you can change single bytes.
- **Host key**: the identity an SSH or SFTP server presents. File Manager remembers ("pins") it and blocks the connection if it ever changes.
- **Index (search index)**: a list of file names and details the app builds in the background so Search can answer as you type. It is rebuilt after files change.
- **Java view**: a readable Java version of an app's compiled code, made by **Decompile to Java**. It is an approximation, not the original source.
- **Keystore (Android Keystore)**: secure storage built into the phone that holds encryption keys designed so that the keys themselves are hard to copy out. File Manager keeps your saved passwords and tokens protected with it.
- **Keystore file (.p12 / .jks)**: a file holding your own APK signing key. Different from the Android Keystore above.
- **LAN**: your local network, for example the devices on your home Wi-Fi.
- **Logcat**: Android's running system log. The Apps tab can show it, filter it and export it.
- **Manifest (AndroidManifest.xml)**: the file in every APK that declares the app's name, version, permissions and screens.
- **mapping.txt**: a file made when an app's names were shortened ("obfuscated") that maps the short names back to the originals. Smali tools can apply it.
- **Merge-by-id**: a settings-import mode that keeps your current items and adds only the ones that are new.
- **Messenger**: the Android feature the bridge uses to exchange short messages between two apps.
- **Node id**: this device's name in the fleet, shown in the About dialog.
- **Notes vault**: a folder of Markdown notes that File Manager can edit, link together and search.
- **OBB**: an expansion file some large apps and games keep in `Android/obb`.
- **Obfuscation**: deliberately scrambling an app's names so its code is harder to read. R8 and ProGuard do this.
- **One-way / two-way sync**: one-way copies changes from a source to a target only; two-way carries changes in both directions and needs a rule for conflicts.

### 9.3 P to Z

- **Package name**: an app's unique id, such as `ai.victorious.filemanager`.
- **Pane**: one file list. File Manager can show one or two, each with its own tabs.
- **Partial file (`.partial`)**: the temporary name a file has while it is still being copied. It gets its real name only once the copy is checked.
- **Patch preset**: a ready-made, one-tap edit to an APK. Needs an ownership confirmation first.
- **PEM / PKCS#8 / OpenSSH key**: the common file formats for private keys. File Manager reads all three for SFTP.
- **PIN (Wi-Fi transfer)**: an optional code a computer must enter before it may use your phone's transfer page.
- **PiP (picture-in-picture)**: a small floating video window that keeps playing while you use other apps.
- **Plugin**: a small add-on, described by a JSON file, that adds actions. Plugins install disabled.
- **Privilege backend**: how the app gets extra access. The order is Root first, then Shizuku, then normal access.
- **PTY**: a "pseudo-terminal", the kind of connection that lets a terminal show colours and full-screen programs.
- **Regex (regular expression)**: a compact pattern language for matching text, such as `\d{4}` for four digits.
- **Remount**: switching a protected system area between read-only and writable. Needs Root or Shizuku, and is a power operation.
- **Replay protection**: the fleet bridge refuses a request it has already seen, so an old message cannot be sent again.
- **Root**: full administrator access to the phone (for example through Magisk). Optional and off by default.
- **S3 / R2 / MinIO**: object storage services (Amazon S3 and compatible ones). You connect with a bucket, region, endpoint, access key and secret.
- **SAF (Storage Access Framework)**: Android's standard folder picker, which lets an app use a folder you choose without all-files access.
- **Scripts**: small `sh` command files you save in File Manager and run on files.
- **Secure folder**: the encrypted vault, locked with your fingerprint, face or screen lock.
- **SFTP / SSH**: encrypted file transfer over a secure shell connection. Used for network connections and My PC.
- **Shizuku / Sui**: an app that lends other apps extra permissions without root, usually started through Wireless debugging. Sui is the Magisk-based equivalent.
- **Signing / signature scheme (V1 to V4)**: an APK must be signed so Android trusts it. V1 to V4 are generations of the signature format; newer Android versions prefer V2 and V3.
- **Signing certificate**: the identity behind an APK's signature. An update must carry the same signing certificate as the installed app.
- **Smali**: a readable text form of an app's compiled code. You can edit it and assemble it back.
- **Smart collection**: a saved search that updates itself, for example "Large videos".
- **SMB**: the network file sharing protocol used by Windows and many NAS drives.
- **Snapshot**: a private saved copy of one file at one moment, so you can compare or go back.
- **Split file / Join parts**: cutting a big file into numbered parts (`.001`, `.002`, ...) and joining them again.
- **Staged paste**: with one pane, Copy or Move keeps the selection ready and shows **Paste here**.
- **strings.xml**: the file that holds an app's text. Translating an app means providing one per language.
- **su**: the command that gives a shell root rights. The terminal uses it when Root is on.
- **Tab**: a separate folder view inside a pane.
- **Tailscale**: a private network service. My PC can use a `*.ts.net` name from it when you are away from home.
- **Trash**: where deleted files go first so you can restore them. On shared storage it is a hidden `.victorious-trash` folder on the same drive.
- **Undo**: reverts the last operation, such as a copy, move, delete or batch rename.
- **Vault**: another word for the secure folder, or for a notes folder in the Notes vault.
- **WebDAV**: file access over HTTP, offered by many cloud and NAS services.
- **Wireless debugging**: an Android developer option that lets a helper such as Shizuku start without a computer (Android 11+). It must be started again after each reboot.
- **WorkManager**: Android's scheduler for background jobs. Scheduled folder sync uses it, so the exact time can vary.
- **XOR**: a simple way of scrambling bytes, sometimes used to hide text inside apps. The DEX decrypt preview can undo it.
