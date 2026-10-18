# Phase 12 — Media & viewers, widgets, tiles, share

Built from scratch. No MT Manager / NP Manager code was copied or decompiled. All third-party
code is open source under compatible licences (table below and
`app/src/main/assets/licenses/media3-glance-Apache-2.0.txt`).

> **Stacking:** this branch (`feature/phase12-media`) is stacked on
> `feature/phase11-apk-deep-edit` (PR #15). Its PR targets that branch, not `main`.

## Gallery (fast cached thumbnails)
`ui/MediaViewers.kt` `GalleryScreen` + `media/ThumbnailLoader.kt`.

- Long-press a folder -> **View as gallery**.
- A `LazyVerticalGrid` (adaptive 108 dp cells, keyed by path) shows images and video frames; only
  visible cells decode, so big folders stay smooth.
- Thumbnails are square center-crops at 256 px. Images decode with a power-of-two `inSampleSize`;
  video frames come from `MediaMetadataRetriever`.
- Two-level cache: an in-memory `LruCache` plus a JPEG disk cache under `cacheDir/thumbs`, pruned to
  a byte budget oldest-first. The **cache key** (path + size + mtime + target px) and the **LRU
  byte-budget / eviction order** are pure logic in `:core` (`core/media/ThumbnailCache.kt`) and are
  unit-tested, so replacing a file in place never serves a stale thumbnail.
- Tapping an image opens a full-screen `HorizontalPager`; tapping a video opens the player.

## Video player
`ui/MediaViewers.kt` `VideoPlayerScreen` — Media3 **ExoPlayer** in a `PlayerView` (`AndroidView`).
Auto-plays, released on dispose. Opened from "Play video" or a gallery video tile.

## Audio player + background playback
`media/AudioPlaybackService.kt` (Media3 `MediaSessionService`) + `ui/MediaViewers.kt`
`AudioPlayerScreen`.

- The screen drives the service through a `MediaController`, so playback and the system media
  notification / lock-screen transport controls continue in the background.
- Pauses automatically when headphones are unplugged; stops the service if the task is swiped away
  while idle.
- Platform permissions (declared on our own service): `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS` (Android 13+).

## PDF viewer
`ui/MediaViewers.kt` `PdfViewerScreen` — Android **`PdfRenderer`** (framework, no dependency).
Renders one page at a time fit-to-width; prev/next with a "page / total" label. Page clamping and
fit-width bitmap-size math (with a max-edge cap to avoid OOM) are pure logic in
`core/media/PdfPaging.kt` and unit-tested.
> PdfBox-Android (Apache-2.0) was **not** needed — the platform renderer covers viewing. It remains
> the documented fallback if text extraction / search is added later.

## Font preview
`ui/MediaViewers.kt` `FontPreviewScreen` — `Typeface.createFromFile` on `.ttf`/`.otf`, shown at
several sizes plus an alphabet sample via a native `TextView` ladder.

## Home-screen widget (Glance)
`widget/BookmarksWidget.kt` — a **Glance** widget listing bookmarked "quick folders"; tapping one
opens the app at that folder (`MainActivity` `EXTRA_OPEN_PATH`). Standard `AppWidget` receiver
(`BookmarksWidgetReceiver`) + `res/xml/bookmarks_widget_info.xml`.

## Quick Settings tiles
`tiles/FileManagerTiles.kt` — **Open File Manager** and **Wi-Fi transfer** tiles. The tile
label/subtitle/active model is pure logic in `core/tiles/TileState.kt` and unit-tested.
> QS tiles require the platform **`BIND_QUICK_SETTINGS_TILE`** permission declared on our own tile
> services. That is mandatory for any QS tile on Android and is **not** a fleet `BIND_*` permission.

## Share to File Manager + open-with
- `share/ShareReceiverActivity.kt` accepts `ACTION_SEND` / `ACTION_SEND_MULTIPLE`, asks for a
  destination folder (Storage Access Framework tree), and copies each item in with a sanitised,
  collision-free name. The sanitising (path-traversal stripping) and de-duplication
  (`name (2).ext`) are pure logic in `core/share/ShareIntake.kt` and unit-tested.
- Open-with: `MainActivity` handles external `ACTION_VIEW` on `file://` image/video/audio/PDF URIs
  and opens the matching built-in viewer. (`content://` view is deferred — see below.)

## Fleet / signing (unchanged)
Still `EXTERNAL_NODE`: no DependencyGuard, no companion packages, no shared fleet signing key, no
fleet `BIND_*` permission. The Messenger bridge and File-Manager-only debug signing are untouched.

## Licences

| Component | Licence |
|---|---|
| androidx.media3 (exoplayer, ui, session, common) | Apache-2.0 |
| androidx.glance (appwidget, material3) | Apache-2.0 |
| androidx.documentfile | Apache-2.0 |
| Android `PdfRenderer`, `Typeface`, `MediaMetadataRetriever` | Android framework (no dep) |

## Deferred / untested
- `content://` open-with routing (viewers operate on file paths today).
- On-device verification of players/widget/tiles/share (built and lint/unit-tested on the box only;
  no emulator run here). PDF/font tested via pure page/size/naming logic, not on-device rendering.
- Image zoom in the gallery pager is fit-to-screen only (no pinch-zoom yet).
