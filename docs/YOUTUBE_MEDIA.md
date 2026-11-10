# Media library and YouTube links

This pass adds a YouTube / YouTube Music-*style* home for the music and videos that are **already on
the phone**, and a safe way to open YouTube links. Everything was written from scratch for this app.
No code, assets or data from YouTube, YouTube Music, ReVanced, MT Manager or NP Manager were used.

## Two separate things

| | What it does | What it never does |
|---|---|---|
| **Local media library** (Tools → Media library) | Lists your own audio and video files and plays them in the existing Media Pro player | Touch the network |
| **YouTube links** (Media library → YouTube links tab) | Cleans a youtube.com / youtu.be / music.youtube.com link and opens it in the **official** YouTube or YouTube Music app, or the browser if they are not installed | Download, stream, embed, scrape, block ads, spoof, or patch anything |

## Local media library

Open **Tools → Media library** (or type "media" in the command palette). It always uses the dark
fleet look.

- **Home:** Shuffle all songs, Play videos, **Continue watching & listening** (files you stopped
  part-way, with a progress bar and time left), **Recently played**, **Favourites** and **Recently added**.
- **Songs / Videos:** sort by title, artist, recently added or length; Play all or Shuffle.
- **Artists / Albums / Folders:** grouped from the tags Android already indexed (MediaStore). Files
  with no tags are named from the file name (`01 - Artist - Song.mp3` works). Accents are ignored
  when grouping, so "Beyoncé" and "Beyonce" are one artist.
- **Search:** every word must match the title, artist, album or file name; accents and case are ignored.
- **Favourites:** tap ☆ on any row. **+** adds the row (or a whole artist/album/folder) to a playlist.
- **Playlists:** the same playlists the player uses (`filesDir/media_playlists.json`).
- **Rescan** re-reads the index. If MediaStore has nothing, the app walks Music, Movies, Download,
  DCIM, Podcasts and Audiobooks (skipping hidden folders and folders with `.nomedia`).

Stored in app-private SharedPreferences (`media_library`): favourites, play history (local file paths
only, newest 200), saved links and player settings. Stream URLs are never added to the history.

## Player additions (Media Pro)

- **Chapters**, only when they are already in the file or next to it:
  - MP4 / M4A / M4B / MOV: Nero `chpl` chapters (moov/udta/chpl).
  - MKV / MKA / WebM: Matroska `Chapters` (first edition).
  - MP3: ID3v2.3 / 2.4 `CHAP` frames.
  - Sidecar text: `name.chapters.txt`, `name.chapters`, `name_chapters.txt` (OGM `CHAPTER01=` style) or
    `name.ffmetadata` (`;FFMETADATA1`).
  - The current chapter name shows above the controls; **⇤ Chapter / Chapter ⇥** jump between them
    (more than 3 s into a chapter, ⇤ restarts it). ⋮ → Chapters lists them all.
  - There is **no** online sponsor-segment database or any other network lookup.
- **Lyrics:** a `.lrc` file with the same name as the song (synced lines, `[offset:]` supported) or
  lyrics embedded in an MP3 (ID3 `USLT`). The current line shows on the song screen; ⋮ → Lyrics lists all.
- **Playback settings (remembered):** speed, subtitles on/off, chapter buttons, and a **max quality**
  cap (Auto, 2160p … 360p) for adaptive network streams (HLS/DASH). Shuffle and repeat are remembered too.
  Local files always play at their own quality.
- The player now always uses the dark fleet colours.

Already there before (unchanged): background play, PiP, speed 0.25×–4×, sleep timer, queue,
shuffle/repeat, playlists, subtitles, equalizer, resume, SMB/HLS/DASH/RTSP, Dolby/DTS via FFmpeg.

## YouTube / YouTube Music links

- Paste a link and tap **Open**, or **Save** it to the list. **Import from file** pulls every YouTube
  link out of a text, `.url` (Windows shortcut) or `.webloc` (Mac) file.
- Recognised: `youtube.com/watch`, `youtu.be/…`, `/shorts/`, `/live/`, `/embed/`, playlists,
  channels (`/@handle`, `/channel/`, `/c/`, `/user/`), and `music.youtube.com` songs, playlists and pages.
- Tracking parameters such as `si=` and `feature=` are removed. Only `v`, `list` and the start time `t` are kept.
- Look-alike hosts (`youtube.com.evil.example`), links with a user name/password, and non-http(s)
  schemes are rejected.
- Opening order: YouTube Music links → the YouTube Music app, then the YouTube app (same video), then
  the browser. YouTube links → the YouTube app, then the browser. This is a normal Android `ACTION_VIEW`
  intent; the official app does all playing.

## Legally skipped (on purpose)

| Idea | Why it is not here |
|---|---|
| Downloading YouTube videos or audio | Against YouTube's Terms of Service and often copyright |
| Playing YouTube streams inside this app, background play of YouTube content, or picture-in-picture for YouTube | Needs unofficial stream extraction; circumvents YouTube Premium features |
| Ad blocking / SponsorBlock-style skipping for YouTube | Changes YouTube's own service; SponsorBlock also needs a network database |
| ReVanced-style patching of the YouTube / YouTube Music APKs, client spoofing, microG | Modifies and redistributes Google's apps; off limits |
| Scraping YouTube pages for titles or thumbnails | Not allowed by YouTube's terms; the app stores only the link and your label |
| QuickTime chapter *tracks* (`tref/chap`) in MP4 | Rare outside Apple tools; Nero `chpl` covers the common case. May come later |

## Code

- `core/media/library/MediaLibrary.kt`: items, grouping, search, favourites, history, continue
  watching, remembered player settings.
- `core/media/library/YouTubeLinks.kt`: link parsing, cleaning, saved links.
- `core/media/library/Chapters.kt`: chapter parsers (MP4, Matroska, ID3, OGM, FFmetadata), chapter
  navigation, LRC lyrics.
- `app/media/MediaLibraryRepo.kt`: MediaStore scan, storage, official-app handoff.
- `app/ui/MediaLibraryScreen.kt`: the library UI. `app/media/MediaProActivity.kt`, `MediaProEngine.kt`: player changes.
- Tests: `MediaLibraryTest`, `YouTubeLinksTest`, `ChaptersTest` in `:core`.
