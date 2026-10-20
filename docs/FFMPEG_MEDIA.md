# Media3 FFmpeg decoder + system volume

## media.volume → STREAM_MUSIC (done)
Options → **media.volume → system STREAM_MUSIC**. When enabled, `MediaProEngine.setVolume`
sets both ExoPlayer volume and `AudioManager.STREAM_MUSIC` via `VolumeMapping`.
Off by default. Gesture volume on the player surface already adjusted STREAM_MUSIC.

## FFmpeg extension (LGPL)

Media3 does **not** publish `decoder_ffmpeg` to Maven (ExoPlayer issue 2781). You must:

1. Clone [androidx/media](https://github.com/androidx/media) (same major as our Media3, here **1.4.x**).
2. Set `ENABLED_DECODERS=(vorbis opus flac)` — **LGPL-safe only** (no GPL-only codecs).
3. `git clone` FFmpeg **release/6.0** into `libraries/decoder_ffmpeg/src/main/jni/ffmpeg`.
4. Run `build_ffmpeg.sh` with NDK (tested path: r26), `HOST_PLATFORM=linux-x86_64`,
   `ANDROID_ABI` ≤ app `minSdk` (26).
5. Include the module via `includeBuild` / composite or copy, depend on `:lib-decoder-ffmpeg`.
6. Ship `assets/licenses/FFMPEG_LGPL_NOTICE.txt` (already in the app). Dynamic linking via
   the JNI wrapper — do not statically merge GPL code into the APK.

`FfmpegRenderers.factory()` already sets `EXTENSION_RENDERER_MODE_ON` when
`androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer` is on the classpath; otherwise OFF.

### Status on this branch
`build_ffmpeg.sh` completed on the box (NDK 26.1, ABI level 21, decoders: vorbis/opus/flac,
LGPL 2.1). Natives live under `:ffmpeg-decoder` (`android-libs` for armeabi-v7a, arm64-v8a,
x86, x86_64). Full FFmpeg `.c` sources were **not** vendored — only headers + static libs +
Media3 Apache-2.0 Java/JNI wrapper. App depends on `:ffmpeg-decoder`; `FfmpegRenderers` enables
`EXTENSION_RENDERER_MODE_ON` when the class loads.

Also shipped: volume mapping + UI toggle + unit tests + LGPL notice asset.


Verified on this branch: `:ffmpeg-decoder:assembleDebug` produces `libffmpegJNI.so` for armeabi-v7a, arm64-v8a, x86, x86_64.
