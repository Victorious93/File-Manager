# Phase 18: notes, media and polish

## A. New features (not on MT Manager's or NP Manager's public feature lists)
We checked only the public feature lists and store pages. We did not copy or decompile either app.
Folder compare was dropped because both apps already list it: MT Manager since 2022, and NP Manager as file/folder compare.

| Feature | What it does | Why |
|---|---|---|
| Command palette | Type a few letters to fuzzy-search every tool and action. Recent picks show first. | Neither list has a launcher like this. It makes the app quicker to use one-handed. |
| Colour tags + smart collections | Give files one of 7 colour labels. Saved "smart" views filter by name, type, size, age, tag or folder. 5 presets are included. | Neither list offers colour tags or saved searches. |
| File snapshots | Save versions of a file, see a diff against the current file, and restore a version. The current file is saved first, so a restore can be undone. Up to 20 versions per file, 50 MB cap, and identical copies are skipped. | A local undo history for config and text files. Neither list has this. |
| Wi-Fi QR share | Makes a standard `WIFI:` QR code so another phone can join a network. | Neither list has this. It reuses zxing, which we already ship. |

## B. Bug fixes and polish
- Opening a note no longer saves the previous note on the UI thread. Saves and opens run in the background and show an error message if they fail.
- Notes search used to read every note on each keystroke on the UI thread. It now waits 250 ms after typing stops and runs in the background. A "no matches" message was added.
- The graph view read every note on the UI thread just to count tags. That now runs in the background.
- Creating a note from a link or the New dialog, creating the daily note, and setting up a new vault folder no longer touch the disk on the UI thread. Failures show a message instead of crashing.
- Opening a vault that can't be read now shows a message instead of crashing.
- The empty note list now says "No notes yet".
- Backlinks, outgoing links, tag rows and note-list rows are now at least 48dp tall, which is easier to tap and meets accessibility touch-target size. Colour tag dots have screen-reader labels.
- The Wi-Fi transfer QR code is built in one pixel copy instead of one call per pixel, and now encodes UTF-8 correctly.
- Snapshots: fixed a dedupe hash mismatch, and fixed a restore that could delete the very version being restored.
- Lint and all unit tests pass. See the PR for the numbers.

## C. Obsidian-style notes
- **Rename updates links.** Renaming a note rewrites every `[[link]]` to it, keeping `#heading`, `|alias` and `![[embed]]` forms. The app reports how many links changed.
- **Unlinked mentions.** Lists other notes that mention this note's title as plain text. Code, existing links and frontmatter are skipped. Tap **Link** to turn a mention into a link.
- **Tags pane.** Nested tags such as `#project/alpha` show as a tree, and tapping a tag lists its notes.
- **Outline and task count** in the side panel.
- **Local graph.** Shows only this note's neighbours, up to 2 hops away.

## D. Media
WMA, plus bare DTS and TrueHD streams, play through the bundled FFmpeg audio decoder (LGPL). See FFMPEG_MEDIA.md.

## Limitations
- Colour tags don't follow files that are renamed or moved outside this app. Tags on deleted files are cleaned up.
- In SAF (picked-folder) vaults, a note can only be renamed within its own folder.
- Snapshots are stored privately inside the app. Uninstalling the app deletes them.
- Wi-Fi QR shares only details you type in. Android doesn't let apps read saved Wi-Fi passwords.

## Licences
No new dependencies. zxing core (Apache-2.0) was already used. The FFmpeg decoder is LGPL-2.1, and the decoders are built without GPL or nonfree parts. DTS and TrueHD may be covered by patents in some countries.
