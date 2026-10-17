# Archives

| Format | List | Extract | Create | Library (licence) |
|---|---|---|---|---|
| ZIP / JAR / APK | ✓ | ✓ (AES/ZipCrypto password via zip4j) | ✓ (plain + AES-256) | java.util.zip, zip4j (Apache-2.0) |
| 7z | ✓ | ✓ | – | Apache Commons Compress (Apache-2.0) + XZ for Java (public domain) |
| TAR / TAR.GZ | ✓ | ✓ | – | Apache Commons Compress (Apache-2.0) |
| RAR 1.5 – 7 (RAR4, RAR5, solid) | ✓ | ✓ (password prompt) | **never** | junrar 8.1.2 (UnRAR licence) |

Every extractor goes through `Archives.safeTarget`, so `../` and absolute entry names
cannot escape the destination folder (zip-slip). A RAR fixture with `..\` names
(`parent-dir.rar`) is part of the unit tests.

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
