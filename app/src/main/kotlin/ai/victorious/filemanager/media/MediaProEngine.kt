package ai.victorious.filemanager.media

import ai.victorious.filemanager.core.media.pro.EqualizerSettings
import ai.victorious.filemanager.core.media.pro.MediaRemote
import ai.victorious.filemanager.core.media.pro.MediaState
import ai.victorious.filemanager.core.media.pro.PlaybackSpeed
import ai.victorious.filemanager.core.media.pro.Playlist
import ai.victorious.filemanager.core.media.pro.PlaylistCodec
import ai.victorious.filemanager.core.media.pro.ResumeStore
import ai.victorious.filemanager.core.media.pro.SleepTimer
import ai.victorious.filemanager.core.media.pro.StreamSupport
import ai.victorious.filemanager.core.media.pro.SubtitleSupport
import ai.victorious.filemanager.core.media.pro.VolumeMapping
import android.content.Context
import android.media.AudioManager
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

/**
 * Media Pro: one process-wide Media3 ExoPlayer shared by the MediaSession service (background
 * play, lock-screen / notification controls), the player UI and the media.* fleet bridge ops.
 * Written from scratch (no ReVanced / VLC code). All calls must be on the main thread.
 */
@UnstableApi
object MediaProEngine : MediaRemote {
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    var player: ExoPlayer? = null
        private set
    private var equalizer: Equalizer? = null
    private val resume = ResumeStore()
    val sleep = SleepTimer(System::currentTimeMillis)
    private val _tick = MutableStateFlow(0L)
    /** Bumps every second while a player exists (UI refresh, sleep timer state). */
    val tick: StateFlow<Long> = _tick
    var backgroundPlay = true
        private set
    /** When true, media.volume also sets STREAM_MUSIC (system media volume). */
    var controlSystemVolume = false
        private set

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        backgroundPlay = prefs.getBoolean("background_play", true)
        controlSystemVolume = prefs.getBoolean("control_system_volume", false)
        runCatching {
            val o = JSONObject(prefs.getString("resume", "{}")!!)
            resume.load(o.keys().asSequence().associateWith { o.getLong(it) })
        }
    }

    fun updateBackgroundPlay(on: Boolean) {
        backgroundPlay = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("background_play", on).apply()
    }

    fun updateControlSystemVolume(on: Boolean) {
        controlSystemVolume = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("control_system_volume", on).apply()
    }

    /** Builds the player (called by the MediaSession service). */
    fun createPlayer(context: Context): ExoPlayer {
        init(context)
        player?.let { return it }
        val p = ExoPlayer.Builder(context)
            .setRenderersFactory(FfmpegRenderers.factory(context))
            .setMediaSourceFactory(DefaultMediaSourceFactory(RoutingDataSourceFactory(context)))
            .setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .build()
        p.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && sleep.shouldStop(trackEnded = true)) p.pause()
                mediaItem?.let { applyResume(p, it) }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) { p.currentMediaItem?.let { resume.clear(it.mediaId) }; sleep.shouldStop(trackEnded = true) }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) saveResume() }
        })
        player = p
        ticker()
        return p
    }

    fun releasePlayer() {
        saveResume()
        runCatching { equalizer?.release() }; equalizer = null
        player?.release(); player = null
    }

    private fun ticker() {
        main.postDelayed(object : Runnable {
            override fun run() {
                val p = player ?: return
                if (sleep.shouldStop()) p.pause()
                _tick.value = System.currentTimeMillis()
                if (p.isPlaying && (_tick.value / 1000) % 10 == 0L) saveResume()
                main.postDelayed(this, 1000)
            }
        }, 1000)
    }

    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController>? = null

    /**
     * Binds the session service via a MediaController (which creates the shared player) and
     * calls [onReady] on the main thread once [player] exists.
     */
    fun connect(context: Context, onReady: (ExoPlayer) -> Unit) {
        init(context)
        player?.let { onReady(it); return }
        val token = androidx.media3.session.SessionToken(app, android.content.ComponentName(app, AudioPlaybackService::class.java))
        val f = controllerFuture ?: androidx.media3.session.MediaController.Builder(app, token).buildAsync().also { controllerFuture = it }
        f.addListener({ player?.let(onReady) }, ContextCompat.getMainExecutor(app))
    }

    fun item(uri: String, title: String, subtitles: List<Pair<Uri, String>> = emptyList()): MediaItem {
        val subs = subtitles.mapNotNull { (u, name) ->
            val mime = SubtitleSupport.mimeFor(name) ?: return@mapNotNull null
            MediaItem.SubtitleConfiguration.Builder(u).setMimeType(mime).setLanguage(SubtitleSupport.language(name))
                .setLabel(name).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
        }
        val parsed = if (uri.startsWith("/")) Uri.fromFile(File(uri)) else Uri.parse(uri)
        return MediaItem.Builder().setUri(parsed).setMediaId(uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .setSubtitleConfigurations(subs).build()
    }

    /** Local file + automatic sidecar subtitles (movie.srt / movie.en.ass …). */
    fun localItem(file: File): MediaItem {
        val siblings = file.parentFile?.list()?.toList().orEmpty()
        val subs = SubtitleSupport.sidecars(file.name, siblings).map { Uri.fromFile(File(file.parentFile, it)) to it }
        return item(file.absolutePath, file.name, subs)
    }

    fun playItems(items: List<MediaItem>, start: Int = 0) {
        val p = player ?: return
        p.setMediaItems(items, start.coerceIn(0, (items.size - 1).coerceAtLeast(0)), C.TIME_UNSET)
        p.prepare()
        p.currentMediaItem?.let { applyResume(p, it) }
        p.play()
    }

    /** Adds an external subtitle to the current item, keeping position. */
    fun addExternalSubtitle(uri: Uri, displayName: String): Boolean {
        val p = player ?: return false
        val cur = p.currentMediaItem ?: return false
        val mime = SubtitleSupport.mimeFor(displayName) ?: return false
        val sub = MediaItem.SubtitleConfiguration.Builder(uri).setMimeType(mime).setLabel(displayName)
            .setLanguage(SubtitleSupport.language(displayName)).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
        val next = cur.buildUpon().setSubtitleConfigurations(cur.localConfiguration?.subtitleConfigurations.orEmpty() + sub).build()
        val pos = p.currentPosition
        p.replaceMediaItem(p.currentMediaItemIndex, next)
        p.seekTo(pos)
        return true
    }

    fun setSpeed(speed: Float) { player?.playbackParameters = PlaybackParameters(PlaybackSpeed.clamp(speed)) }

    private fun applyResume(p: Player, item: MediaItem) {
        val at = resume.resumeAt(item.mediaId) ?: return
        if (p.currentMediaItem?.mediaId == item.mediaId) p.seekTo(at)
    }

    fun saveResume() {
        val p = player ?: return
        val id = p.currentMediaItem?.mediaId ?: return
        resume.save(id, p.currentPosition, p.duration.takeIf { it > 0 } ?: 0)
        val o = JSONObject(); resume.snapshot().forEach { (k, v) -> o.put(k, v) }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("resume", o.toString()).apply()
    }

    // ---- equalizer ----
    fun equalizer(): Equalizer? {
        val p = player ?: return null
        if (equalizer == null) equalizer = runCatching { Equalizer(0, p.audioSessionId) }.getOrNull()
        return equalizer
    }

    fun applyEqualizer(s: EqualizerSettings) {
        val eq = equalizer() ?: return
        val range = eq.bandLevelRange
        val c = s.clampedTo(range[0].toInt(), range[1].toInt(), eq.numberOfBands.toInt())
        c.bandLevels.forEachIndexed { i, l -> eq.setBandLevel(i.toShort(), l.toShort()) }
        eq.enabled = s.enabled
    }

    // ---- playlists ----
    private fun playlistFile() = File(app.filesDir, "media_playlists.json")
    fun playlists(): List<Playlist> = runCatching { PlaylistCodec.decode(playlistFile().readText()) }.getOrDefault(emptyList())
    fun savePlaylists(lists: List<Playlist>) { playlistFile().writeText(PlaylistCodec.encode(lists)) }

    // ---- MediaRemote (fleet media.* ops) ----
    private fun live(): ExoPlayer? = player?.takeIf { it.mediaItemCount > 0 }

    override fun state(): MediaState? {
        val p = live() ?: return null
        val item = p.currentMediaItem
        return MediaState(
            playing = p.isPlaying,
            positionMs = p.currentPosition.coerceAtLeast(0),
            durationMs = p.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0,
            // Title only (file name / metadata), never a path or URL.
            title = item?.mediaMetadata?.title?.toString()?.substringAfterLast('/')?.take(120) ?: "",
            source = item?.mediaId?.let(StreamSupport::label) ?: "unknown",
        )
    }

    private inline fun on(f: (ExoPlayer) -> Unit): Boolean { val p = live() ?: return false; f(p); return true }
    override fun play() = on { it.play() }
    override fun pause() = on { it.pause() }
    override fun toggle() = on { if (it.isPlaying) it.pause() else it.play() }
    override fun next() = on { it.seekToNext() }
    override fun prev() = on { it.seekToPrevious() }
    override fun seekTo(positionMs: Long) = on { p ->
        val d = p.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        p.seekTo(if (d != null) positionMs.coerceAtMost(d) else positionMs)
    }
    override fun seekBy(deltaMs: Long) = on { p -> seekTo((p.currentPosition + deltaMs).coerceAtLeast(0)) }
    override fun setVolume(level: Int) = on { p ->
        val v = level.coerceIn(0, 100)
        p.volume = v / 100f
        if (controlSystemVolume) {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, VolumeMapping.streamIndex(v, max), 0)
        }
    }

    private const val PREFS = "media_pro"
}
