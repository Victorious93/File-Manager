# Readable Java viewer (Smali -> Java & DEX reading)

Phase 13 adds a readable-Java decompiler on top of the existing DEX/smali browser.

## What it does
- **Decompile to Java** — any class in a `.dex` or `.apk` can be shown as readable Java
  (toggle **Decompile to Java** / **Show smali** in the class view). Smali stays available
  side-by-side via the same toggle.
- **Search in Java** — grep-style, line-numbered, in the decompiled source.
- **Jump to class / method** — the class list filter jumps to a class; the smali method list
  jumps to a method; in Java use the in-code search.
- **Copy / Export** — copy the class to the clipboard, export a single class as `.java`
  (opens in the text editor), or **Export all .java** for a whole dex/package to a folder.
- **String constants** — list every `const-string` literal in a dex, with a filter.
- **Dex <-> dex compare** — class-list and method-signature diff between two `classesN.dex`.

## Engine
- **jadx** (`io.github.skylot:jadx-core` + `jadx-dex-input`, Apache-2.0). jadx is pure JVM,
  so the decompiler wrapper lives in `:core`
  (`ai.victorious.filemanager.core.javadec`) and is unit-tested without Android.
- **Android compatibility**: jadx (and its guava transitive) touch newer `java.*` APIs, so the
  app module enables **core library desugaring** (`isCoreLibraryDesugaringEnabled = true`,
  `com.android.tools:desugar_jdk_libs`). minSdk stays **26** — no bump required. Debug builds
  are not minified; native multidex (minSdk >= 21) covers the added method count.
- **Size**: jadx-core + jadx-dex-input + guava + gson add roughly ~5-6 MB to the APK (see the
  size delta in the Phase 13 report).

## Off-main-thread, cancellation, memory, caching
`DecompileService` (`:core`):
- Runs on `Dispatchers.Default`; cancellation is honoured at checkpoints so a cancelled request
  (e.g. leaving the screen) never writes to the cache. jadx's `getCode()` is not interruptible
  mid-flight, so cancellation is best-effort between classes.
- **Memory**: jadx runs in-process, so there is no hard JVM heap cap. Instead we (a) refuse
  inputs over 96 MB, (b) decompile one class at a time rather than the whole app, (c) keep a
  single open jadx session, and (d) bound the in-memory result map.
- **Cache**: keyed by `sha256(dexContentHash + className)` -> memory + on-disk `.java` under
  `cacheDir/javacache`, with a 64 MB LRU (oldest-modified) disk budget.

## Export path safety
`JavaExport` maps dotted class names to `pkg/Name.java`, collapses inner classes to their
top-level file, and canonicalises every path to guarantee it stays inside the chosen output
directory (traversal and slash-injection are rejected). Unit-tested in `JavaExportTest`.

## Alternate engines (deferred)
CFR (MIT) and Fernflower (Apache-2.0) are both permissive but would roughly double the embedded
size and need JVM-bytecode input (an extra dex->class conversion step) to run on a dex. jadx reads
dex directly and gives Android-friendly output, so a second engine is deferred until there is a
concrete need; the design keeps `JavaDecompiler` behind a small interface so one can be added later.

## Licenses
See `app/src/main/assets/licenses/jadx-Apache-2.0.txt`. All shipped decompiler code is Apache-2.0
/ MIT / BSD-3-Clause; desugar_jdk_libs is build-time only under GPL-2.0 WITH Classpath-exception.
No MT Manager or NP Manager code is used, referenced, or decompiled.
