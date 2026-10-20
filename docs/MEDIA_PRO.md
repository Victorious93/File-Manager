# Media Pro

Media Pro is VictorFiles' built-in player. **Engine: AndroidX Media3 (ExoPlayer), Apache-2.0.**
We chose Media3 over libVLC because Media3 was already in the app and has MediaSession, PiP-friendly
rendering, SRT/SSA/ASS/WebVTT/TTML subtitles, HLS/DASH/RTSP and playback-speed support built in. It
adds no native libraries and no LGPL obligations. Everything here is written from scratch; **no ReVanced
or VLC code**.

Code: `core/.../media/pro/` (pure JVM, unit-tested) and `app/.../media/` (`MediaProEngine`,
`AudioPlaybackService`, `MediaProActivity`, `SmbDataSource`).

## Features

| Feature | How |
|---|---|
| Formats | Media3 platform + software extractors: MP4/M4A/MOV, MKV/WebM, MP3, AAC/ADTS, FLAC, Ogg/Opus/Vorbis, WAV, AMR, FLV, MPEG-TS/PS, AVI*, plus HLS (`.m3u8`), DASH (`.mpd`), RTSP. Codecs depend on the device's decoders (*no FFmpeg extension is bundled, so e.g. AC-3/DTS/WMA work only where the device decodes them). Not truly "VLC-class" for exotic codecs. |
| Subtitles | Sidecars detected automatically (`movie.srt`, `movie.en.ass`, `movie.fr.vtt`, …). **Subs** button adds any external `.srt/.ass/.ssa/.vtt/.ttml` file and keeps your position. |
| Speed | 0.25× … 4× (⋮ → Speed). |
| Gestures | Tap = show/hide controls. Double-tap left/right = −/+10 s. Horizontal swipe = seek (full width = 90 s). Vertical swipe on the left = brightness, on the right = volume. |
| Equalizer | `android.media.audiofx.Equalizer` on the player's audio session: device presets plus per-band sliders. |
| PiP | **PiP** button, or press Home while a video plays. |
| Streams | ⋮ → Open network stream: `http(s)://` (incl. HLS/DASH), `rtsp://`, `smb://[domain;][user[:pass]@]host/share/path` (smbj, positional reads, seekable). Credentials are stripped from the displayed title. |
| Resume | Position saved on pause / every 10 s / on exit, per media. It is not saved in the first 5 s or after 95 %. Keeps the last 500 entries. |
| Background play | On by default (⋮ → Background play). Leaving the player keeps audio going; off = pause when hidden. |
| Queue / shuffle / repeat | Opening an audio file queues that folder's audio. ⋮ → Queue: jump, remove, shuffle, repeat off/all/one. |
| Playlists | ⋮ → Playlists: save the queue, play it, add it to the queue, delete it. Stored in `filesDir/media_playlists.json`. |
| Sleep timer | 5–90 minutes, or end of current track. |
| Lock screen / notification | Media3 `MediaSessionService` (`AudioPlaybackService`) with its default notification provider. |

## Fleet `media.*` ops (Victorious Keyboard)

Registered on the frozen envelope v1 (`docs/FLEET_BRIDGE.md`), replacing the `capability_off`
placeholders. Call them via `remote.command` (`actionRef`) or `macro.step`/`macro.run`.

| Op | kind | args | Result |
|---|---|---|---|
| `media.play` / `media.pause` / `media.toggle` | action | — | state |
| `media.next` / `media.prev` | action | — | state |
| `media.seek` | action | `positionMs` ≥ 0 | state |
| `media.seekBy` | action | `deltaMs` (±) | state |
| `media.volume` | action | `level` 0..100 (player volume, not the system stream) | state |
| `media.state` | read | — | `{playing, positionMs, durationMs, title, source}` |

All are `remoteAllowed:true, requiresConfirm:false`. `source` is the kind only (`local`, `content`,
`http`, `rtsp`, `smb`), never a path or URL. `title` is the media title / file name.
**Idle** (no player, or empty queue) → error `no_session`. This is a media-specific category added
on top of the frozen set. Bad args → `invalid_parameters`.

Callers: `com.victorious.keyboard.debug` with the pinned cert, scoped to `media.*` only.
`ai.droidcommand.app(.debug)` can call them too. External control must be ON (default OFF).
The release `com.victorious.keyboard` stays denied.

## Licences
Media3 (incl. exoplayer-rtsp/hls/dash) and smbj are Apache-2.0. libVLC is **not** used, so there is
no LGPL component.
