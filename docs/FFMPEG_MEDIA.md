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

### Why this PR may not ship natives
On the build box we started `build_ffmpeg.sh` for all four ABIs (vorbis/opus/flac). That
script configures and compiles FFmpeg four times plus the JNI CMake step and typically needs
**tens of minutes to hours** and a full Media3 composite Gradle include. If the timed build
does not finish cleanly before the next stacked phases, this PR still ships:

- volume mapping + UI toggle + unit tests
- LGPL notice asset
- renderers factory hook
- this documentation with exact steps

Re-run the script locally, add the module dependency, and the hook activates without further
UI work.
