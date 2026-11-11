# Archives

| Format | List | Extract | Create | Library (licence) |
|---|---|---|---|---|
| ZIP / JAR / APK | ✓ | ✓ (AES/ZipCrypto password via zip4j) | ✓ (plain + AES-256, any level) | java.util.zip, zip4j (Apache-2.0) |
| 7z | ✓ | ✓ (7-Zip AES-256 password) | ✓ (LZMA2 or store, optional AES-256 password) | Apache Commons Compress (Apache-2.0) + XZ for Java (public domain) |
| TAR / TAR.GZ / TAR.BZ2 / TAR.XZ (also `.tgz`, `.tbz2`, `.txz`) | ✓ | ✓ | ✓ (`.tar`, `.tar.gz`, `.tar.bz2`, `.tar.xz`) | Apache Commons Compress (Apache-2.0) + XZ for Java (public domain) |
| Single file `.gz` / `.bz2` / `.xz` (e.g. `app.log.gz`) | ✓ (one entry, size unknown) | ✓ | – | Apache Commons Compress + XZ for Java |
| RAR 1.5 – 7 (RAR4, RAR5, solid) | ✓ | ✓ (password prompt) | **never** | junrar 8.1.2 (UnRAR licence) |

## Creating archives (Tools → Create archive)

`core/.../archive/ArchiveWriter.kt`, written from scratch on java.util.zip and Commons Compress.

- Sources are files and folders; each is stored under its own name, folders recursively, empty folders kept.
- Names always use `/`; TAR uses POSIX (PAX) headers so long paths and files over 8 GB work.
- The output (and its `.part` temp file) is never added to itself, even when it lands inside a source folder.
- Duplicate entry names (two sources called `a.txt`) keep the first.
- Written to `.<name>.part` and renamed at the end, so a failure never leaves a half archive with the real name.
- Levels: Store / Fast / Normal / Best. XZ and 7z LZMA2 presets are capped at 6 (~94 MB encoder memory);
  presets 7–9 need 186–674 MB and would crash many phones.
- Passwords (`create(..., password)`): **ZIP** uses WinZip AES-256 through zip4j's `ZipOutputStream`, entry by
  entry, so names, empty folders and the four levels behave like the plain ZIP path (folder entries are not
  encrypted; they carry no data). **7z** uses 7-Zip's own AES-256 + SHA-256 coder from Commons Compress
  (`SevenZOutputFile(file, password)`), applied after LZMA2/copy. Both encrypt contents only; names stay
  readable (Commons Compress cannot write encrypted 7z headers). TAR formats refuse a password
  (`IllegalArgumentException`); an empty password means "no password". The caller wipes the `CharArray`.
- **Tools → Create password ZIP** still exists as a one-tap AES-256 ZIP.
- Tests: `PasswordArchiveTest` (7z and ZIP password round trips at every level, wrong / missing password,
  no partial file after a wrong password, saved-password verification, TAR refusal, empty password),
  `ArchiveWriterTest` (every format round-trips through `Archives.list`/`extract`, levels, self-inclusion,
  duplicates, unique names, replace + bad input).

Compressed formats are recognised by content, not by extension: a `.gz`/`.bz2`/`.xz` is a tarball only if
the decompressed stream starts like one (or the name says `.tar.*`/`.tgz`/`.tbz2`/`.txz`), otherwise it is a
single compressed file named after the archive without its suffix. Multi-member streams (pigz, bgzip) are read to
the end. A truncated or corrupt stream fails the extraction and does not leave a half-written file behind.

## Password 7z (reading)

- `Archives.isSevenZPasswordProtected` reads one byte of the first file (the AES coder only asks for a password
  once data is read; the first file starts a block, so this is cheap even for big solid archives). Header-encrypted
  7z (7-Zip `-mhe`) fails to open at all and is reported as needing a password.
- `Archives.verifySevenZPassword` decodes the smallest file fully (CRC checked) and discards it. The viewer uses it to
  try saved passwords without writing anything.
- `Archives.extractSevenZ(file, dest, password)` maps a wrong password (AES gives garbage, so LZMA2 or the CRC check
  fails) to `WrongPasswordException`, and a missing one to `ArchivePasswordException`. Both are subclasses of
  `ArchivePasswordException`, as is `Archives.RarPasswordException`.

Every extractor goes through `Archives.safeTarget`, so `../` and absolute entry names
cannot escape the destination folder (zip-slip). A RAR fixture with `..\` names
(`parent-dir.rar`) is part of the unit tests.

## Searching inside an archive (engine in `:core`, no screen yet)

`ArchiveSearch` looks inside ZIP/JAR/APK, 7z, TAR family, single `.gz`/`.bz2`/`.xz` and RAR without
extracting to disk. It matches entry **names** and/or the **text lines** of entries (plain or regex,
case-insensitive by default), optionally limited to name globs such as `*.xml`.

Limits, because archives can lie about their size: an entry is read through a hard byte cap
(default 4 MB), one search decompresses at most 256 MB in total, and results stop at 500. Binary
entries are skipped, nested archives are not opened, and encrypted entries are counted in
`skippedEncrypted` instead of aborting the search. 7z and RAR take a password. The result says
whether it was cut short (`truncated`).

## Listing cache

`Archives.listCached` keeps the last 8 archive listings in memory (at most 200,000 entries in
total). An entry is reused only while the archive's path, length and modification time are
unchanged; editing a ZIP through the app also clears it. Failed listings are never cached. The
archive viewer uses it, so reopening an archive or showing it in the second pane does not re-read it.

## RAR and the UnRAR licence: compliance notes

junrar is a Java port of RARLAB's UnRAR source and is distributed under the **UnRAR
licence** (not an OSI licence). The full text ships in the APK at
`assets/licenses/junrar-UnRAR-LICENSE.txt` and in the source tree at
`app/src/main/assets/licenses/junrar-UnRAR-LICENSE.txt`.

How this app complies:

1. **Use: handling RAR archives only.** Clause 2 allows the sources to be used "in any
   software to handle RAR archives without limitations free of charge". This app only
   lists and extracts. It never creates or modifies RAR archives, and nothing tries to
   re-create the RAR compression algorithm.
2. **Unmodified dependency.** junrar is pulled from Maven Central as a binary
   (`com.github.junrar:junrar:8.1.2`). Its sources are not modified or vendored. The
   "clearly stated in the documentation and source comments" condition applies to
   distributing *modified* sources. We state it anyway: **the junrar/UnRAR code may not be
   used to develop a RAR (WinRAR) compatible archiver.** The same statement is in:
   - the header comment of `core/.../archive/Archives.kt`,
   - `gradle/libs.versions.toml`, `core/build.gradle.kts`,
   - the in-app About dialog.
3. **Redistribution** (clause 3) is allowed inside other software packages.
4. **No warranty** (clause 4) is reproduced in the bundled licence file.
5. **Test fixtures** in `core/src/test/resources/rar/` are copied unmodified from junrar's
   test resources (see the README there).

If the owner prefers OSI-only dependencies, removing RAR is a single change: drop
`implementation(libs.junrar)` and the `ArchiveFormat.RAR` branches in `Archives.kt`.
RAR files are then reported as unsupported again.
