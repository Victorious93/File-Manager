package ai.victorious.filemanager.media

import ai.victorious.filemanager.FileManagerApplication
import ai.victorious.filemanager.core.media.pro.EqualizerSettings
import ai.victorious.filemanager.core.media.pro.GestureMath
import ai.victorious.filemanager.core.media.pro.MediaItemRef
import ai.victorious.filemanager.core.media.pro.PlaybackSpeed
import ai.victorious.filemanager.core.media.pro.Playlist
import ai.victorious.filemanager.core.media.pro.StreamSupport
import ai.victorious.filemanager.ui.theme.VictoriousTheme
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import java.util.UUID

/**
 * Media Pro player (Media3). Video with own Compose controls + gestures, PiP, subtitles (sidecar
 * and external), speed, equalizer, sleep timer, background play, network streams (http/https,
 * HLS/DASH, rtsp, smb) and resume; audio with queue, shuffle/repeat and playlists.
 */
@UnstableApi
class MediaProActivity : ComponentActivity() {
    private var isPip by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        val folder = intent.getBooleanExtra(EXTRA_FOLDER_QUEUE, false)
        setContent {
            val prefs by (application as FileManagerApplication).themeSettings.state.collectAsState()
            VictoriousTheme(prefs) {
                var player by remember { mutableStateOf<ExoPlayer?>(null) }
                LaunchedEffect(Unit) {
                    MediaProEngine.connect(this@MediaProActivity) { p ->
                        player = p
                        if (path != null) {
                            val f = File(path)
                            val items = if (folder) siblingsOf(f) else listOf(f)
                            MediaProEngine.playItems(items.map(MediaProEngine::localItem), items.indexOf(f).coerceAtLeast(0))
                        }
                    }
                }
                val p = player
                if (p == null) Box(Modifier.fillMaxSize().background(Color.Black)) else MediaProScreen(p, isPip, onClose = { finish() }, onPip = ::enterPip)
            }
        }
    }

    private fun siblingsOf(f: File): List<File> {
        val ext = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma", "mka", "mid", "ac3", "eac3", "ec3", "dts", "thd")
        val isAudio = f.extension.lowercase() in ext
        if (!isAudio) return listOf(f)
        return f.parentFile?.listFiles()?.filter { it.isFile && it.extension.lowercase() in ext }?.sortedBy { it.name.lowercase() } ?: listOf(f)
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= 26) {
            runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val p = MediaProEngine.player
        if (p != null && p.isPlaying && p.videoSize.width > 0) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isPip = isInPictureInPictureMode
    }

    override fun onStop() {
        super.onStop()
        val p = MediaProEngine.player ?: return
        // Background play (YouTube-style): keep going as audio; otherwise pause when hidden.
        if (!isPip && !MediaProEngine.backgroundPlay) p.pause()
        MediaProEngine.saveResume()
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_FOLDER_QUEUE = "folder_queue"

        fun open(context: Context, file: File?, folderQueue: Boolean = false) {
            context.startActivity(
                Intent(context, MediaProActivity::class.java).apply {
                    file?.let { putExtra(EXTRA_PATH, it.absolutePath) }
                    putExtra(EXTRA_FOLDER_QUEUE, folderQueue)
                    if (context !is ComponentActivity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    }
}

private fun fmt(ms: Long): String {
    if (ms < 0 || ms == C.TIME_UNSET) return "--:--"
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

private enum class Sheet { NONE, SPEED, SLEEP, EQ, QUEUE, STREAM, PLAYLISTS, MORE }

@UnstableApi
@Composable
private fun MediaProScreen(player: ExoPlayer, pip: Boolean, onClose: () -> Unit, onPip: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as ComponentActivity
    val tick by MediaProEngine.tick.collectAsState()
    var showControls by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf(Sheet.NONE) }
    var gestureHint by remember { mutableStateOf<String?>(null) }
    var dragSeek by remember { mutableStateOf<Long?>(null) }
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var brightness by remember { mutableFloatStateOf(activity.window.attributes.screenBrightness.takeIf { it >= 0 } ?: 0.5f) }

    val subPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: uri.lastPathSegment ?: "subtitle.srt"
            if (!MediaProEngine.addExternalSubtitle(uri, name)) gestureHint = "Unsupported subtitle (use .srt .ass .ssa .vtt .ttml)"
        }
    }

    @Suppress("UNUSED_VARIABLE") val t = tick
    val isVideo = player.videoSize.width > 0 || player.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO }
    val title = player.currentMediaItem?.mediaMetadata?.title?.toString() ?: ""

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { showControls = !showControls },
                    onDoubleTap = { o ->
                        val d = if (GestureMath.isLeftHalf(o.x, size.width.toFloat())) -10_000L else 10_000L
                        MediaProEngine.seekBy(d); gestureHint = if (d < 0) "−10 s" else "+10 s"
                    },
                )
            }
            .pointerInput(Unit) {
                var startX = 0f; var startPos = 0L; var horizontal: Boolean? = null; var left = false
                var startVol = 0f; var startBright = 0f
                detectDragGestures(
                    onDragStart = { o ->
                        startX = o.x; startPos = player.currentPosition; horizontal = null
                        left = GestureMath.isLeftHalf(o.x, size.width.toFloat())
                        startVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        startBright = brightness
                    },
                    onDragEnd = { dragSeek?.let { player.seekTo(it) }; dragSeek = null; gestureHint = null },
                    onDragCancel = { dragSeek = null; gestureHint = null },
                ) { change, drag ->
                    change.consume()
                    if (horizontal == null) horizontal = kotlin.math.abs(drag.x) > kotlin.math.abs(drag.y)
                    if (horizontal == true) {
                        val dx = (change.position.x - startX) / size.width
                        val target = GestureMath.seekTarget(startPos, player.duration.coerceAtLeast(0), dx)
                        dragSeek = target; gestureHint = "Seek ${fmt(target)}"
                    } else {
                        val acc = drag.y / size.height
                        if (left) {
                            startBright = GestureMath.level(startBright, acc); brightness = startBright
                            activity.window.attributes = activity.window.attributes.apply { screenBrightness = brightness }
                            gestureHint = "Brightness ${(brightness * 100).toInt()}%"
                        } else {
                            startVol = GestureMath.level(startVol, acc)
                            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (startVol * max).toInt(), 0)
                            gestureHint = "Volume ${(startVol * 100).toInt()}%"
                        }
                    }
                }
            },
    ) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { useController = false; this.player = player } },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )
        if (!isVideo) {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${player.currentMediaItemIndex + 1} / ${player.mediaItemCount}", color = Color.LightGray)
            }
        }
        gestureHint?.let {
            Text(it, color = Color.White, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Center).background(Color(0x99000000)).padding(12.dp))
        }
        if (showControls && !pip) {
            Controls(player, title, onClose, onPip, onSubs = { subPicker.launch(arrayOf("*/*")) }, onSheet = { sheet = it })
        }
    }

    when (sheet) {
        Sheet.NONE -> Unit
        Sheet.SPEED -> PickDialog("Playback speed", { sheet = Sheet.NONE }) {
            PlaybackSpeed.PRESETS.forEach { s ->
                Row(Modifier.fillMaxWidth().clickable { MediaProEngine.setSpeed(s); sheet = Sheet.NONE }.padding(10.dp)) {
                    Text((if (player.playbackParameters.speed == s) "● " else "") + "${s}×")
                }
            }
        }
        Sheet.SLEEP -> PickDialog("Sleep timer", { sheet = Sheet.NONE }) {
            val left = MediaProEngine.sleep.remainingMs()
            Text(if (MediaProEngine.sleep.endOfTrack) "Stops at end of track" else left?.let { "Stops in ${fmt(it)}" } ?: "Off")
            listOf(5, 15, 30, 45, 60, 90).forEach { m ->
                Row(Modifier.fillMaxWidth().clickable { MediaProEngine.sleep.start(m * 60_000L); sheet = Sheet.NONE }.padding(10.dp)) { Text("$m minutes") }
            }
            Row(Modifier.fillMaxWidth().clickable { MediaProEngine.sleep.startEndOfTrack(); sheet = Sheet.NONE }.padding(10.dp)) { Text("End of current track") }
            Row(Modifier.fillMaxWidth().clickable { MediaProEngine.sleep.cancel(); sheet = Sheet.NONE }.padding(10.dp)) { Text("Turn off") }
        }
        Sheet.EQ -> EqualizerDialog { sheet = Sheet.NONE }
        Sheet.QUEUE -> PickDialog("Queue", { sheet = Sheet.NONE }) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(player.shuffleModeEnabled, { player.shuffleModeEnabled = !player.shuffleModeEnabled }, { Text("Shuffle") })
                val label = when (player.repeatMode) { Player.REPEAT_MODE_ONE -> "Repeat one"; Player.REPEAT_MODE_ALL -> "Repeat all"; else -> "Repeat off" }
                FilterChip(player.repeatMode != Player.REPEAT_MODE_OFF, {
                    player.repeatMode = when (player.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
                }, { Text(label) })
            }
            LazyColumn(Modifier.padding(top = 8.dp)) {
                itemsIndexed(List(player.mediaItemCount) { player.getMediaItemAt(it) }) { i, item ->
                    Row(Modifier.fillMaxWidth().clickable { player.seekTo(i, C.TIME_UNSET); player.play() }.padding(8.dp)) {
                        Text((if (i == player.currentMediaItemIndex) "▶ " else "") + (item.mediaMetadata.title ?: "Item ${i + 1}"),
                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("✕", Modifier.clickable { player.removeMediaItem(i) }.padding(horizontal = 8.dp))
                    }
                }
            }
        }
        Sheet.PLAYLISTS -> PlaylistsDialog(player) { sheet = Sheet.NONE }
        Sheet.STREAM -> StreamDialog { sheet = Sheet.NONE }
        Sheet.MORE -> PickDialog("Options", { sheet = Sheet.NONE }) {
            var bg by remember { mutableStateOf(MediaProEngine.backgroundPlay) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Background play", Modifier.weight(1f)); Switch(bg, { bg = it; MediaProEngine.updateBackgroundPlay(it) })
            }
            var sysVol by remember { mutableStateOf(MediaProEngine.controlSystemVolume) }
            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("media.volume → system STREAM_MUSIC", Modifier.weight(1f)); Switch(sysVol, { sysVol = it; MediaProEngine.updateControlSystemVolume(it) })

            }
            listOf("Speed" to Sheet.SPEED, "Sleep timer" to Sheet.SLEEP, "Equalizer" to Sheet.EQ, "Queue / shuffle" to Sheet.QUEUE,
                "Playlists" to Sheet.PLAYLISTS, "Open network stream" to Sheet.STREAM).forEach { (l, s) ->
                Row(Modifier.fillMaxWidth().clickable { sheet = s }.padding(10.dp)) { Text(l) }
            }
        }
    }
    LaunchedEffect(gestureHint) { if (gestureHint != null && dragSeek == null) { kotlinx.coroutines.delay(1200); gestureHint = null } }
}

@UnstableApi
@Composable
private fun Controls(player: ExoPlayer, title: String, onClose: () -> Unit, onPip: () -> Unit, onSubs: () -> Unit, onSheet: (Sheet) -> Unit) {
    Column(Modifier.fillMaxSize().background(Color(0x66000000)).padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("✕", color = Color.White) }
            Text(title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(onClick = onSubs) { Text("Subs", color = Color.White) }
            TextButton(onClick = onPip) { Text("PiP", color = Color.White) }
            TextButton(onClick = { onSheet(Sheet.MORE) }) { Text("⋮", color = Color.White) }
        }
        Column {
            val dur = player.duration.takeIf { it > 0 } ?: 1L
            Slider(player.currentPosition.toFloat().coerceIn(0f, dur.toFloat()), { player.seekTo(it.toLong()) }, valueRange = 0f..dur.toFloat())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmt(player.currentPosition), color = Color.White); Text(fmt(player.duration), color = Color.White)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = { player.seekToPrevious() }) { Text("⏮", color = Color.White, style = MaterialTheme.typography.headlineSmall) }
                TextButton(onClick = { MediaProEngine.seekBy(-10_000) }) { Text("−10", color = Color.White) }
                TextButton(onClick = { MediaProEngine.toggle() }) {
                    Text(if (player.isPlaying) "⏸" else "▶", color = Color.White, style = MaterialTheme.typography.headlineMedium)
                }
                TextButton(onClick = { MediaProEngine.seekBy(10_000) }) { Text("+10", color = Color.White) }
                TextButton(onClick = { player.seekToNext() }) { Text("⏭", color = Color.White, style = MaterialTheme.typography.headlineSmall) }
            }
        }
    }
}

@Composable
private fun PickDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { content() } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@UnstableApi
@Composable
private fun EqualizerDialog(onDismiss: () -> Unit) {
    val eq = remember { MediaProEngine.equalizer() }
    PickDialog("Equalizer", onDismiss) {
        if (eq == null) { Text("Equalizer is not available on this device."); return@PickDialog }
        var enabled by remember { mutableStateOf(eq.enabled) }
        val bands = eq.numberOfBands.toInt()
        val range = eq.bandLevelRange
        val levels = remember { List(bands) { mutableIntStateOf(eq.getBandLevel(it.toShort()).toInt()) } }
        fun apply() = MediaProEngine.applyEqualizer(EqualizerSettings(enabled, levels.map { it.intValue }))
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Enabled", Modifier.weight(1f)); Switch(enabled, { enabled = it; apply() }) }
        if (eq.numberOfPresets > 0) {
            Text("Presets", style = MaterialTheme.typography.labelLarge)
            for (i in 0 until eq.numberOfPresets) {
                Row(Modifier.fillMaxWidth().clickable {
                    eq.usePreset(i.toShort()); levels.forEachIndexed { b, s -> s.intValue = eq.getBandLevel(b.toShort()).toInt() }
                }.padding(6.dp)) { Text(eq.getPresetName(i.toShort())) }
            }
        }
        levels.forEachIndexed { b, s ->
            Text("${eq.getCenterFreq(b.toShort()) / 1000} Hz: ${s.intValue / 100} dB", style = MaterialTheme.typography.bodySmall)
            Slider(s.intValue.toFloat(), { s.intValue = it.toInt(); apply() }, valueRange = range[0].toFloat()..range[1].toFloat())
        }
    }
}

@UnstableApi
@Composable
private fun StreamDialog(onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    val kind = StreamSupport.kind(url)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open network stream") },
        text = {
            Column {
                OutlinedTextField(url, { url = it }, label = { Text("http(s)://, rtsp://, smb://host/share/file") }, singleLine = true)
                Text(if (url.isBlank()) "HLS (.m3u8) and DASH (.mpd) work over http(s)." else kind?.name ?: "Unsupported scheme",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = kind != null && kind.name in setOf("HTTP", "RTSP", "SMB"), onClick = {
                MediaProEngine.playItems(listOf(MediaProEngine.item(url.trim(), StreamSupport.redact(url.trim()))))
                onDismiss()
            }) { Text("Play") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@UnstableApi
@Composable
private fun PlaylistsDialog(player: ExoPlayer, onDismiss: () -> Unit) {
    var lists by remember { mutableStateOf(MediaProEngine.playlists()) }
    var name by remember { mutableStateOf("") }
    PickDialog("Playlists", onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(name, { name = it }, label = { Text("Save queue as…") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(enabled = name.isNotBlank() && player.mediaItemCount > 0, onClick = {
                val items = List(player.mediaItemCount) { player.getMediaItemAt(it) }.map {
                    MediaItemRef(UUID.randomUUID().toString(), it.mediaId, it.mediaMetadata.title?.toString() ?: it.mediaId)
                }
                lists = lists + Playlist(UUID.randomUUID().toString(), name.trim(), items)
                MediaProEngine.savePlaylists(lists); name = ""
            }) { Text("Save") }
        }
        if (lists.isEmpty()) Text("No playlists yet.")
        lists.forEach { pl ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${pl.name} (${pl.items.size})", Modifier.weight(1f).clickable {
                    MediaProEngine.playItems(pl.items.map { MediaProEngine.item(it.uri, it.title) }); onDismiss()
                })
                TextButton(onClick = { player.addMediaItems(pl.items.map { MediaProEngine.item(it.uri, it.title) }) }) { Text("Queue") }
                TextButton(onClick = { lists = lists - pl; MediaProEngine.savePlaylists(lists) }) { Text("Delete") }
            }
        }
    }
}
