package ai.victorious.victorfiles.ui

import ai.victorious.victorfiles.FileManagerApplication
import ai.victorious.victorfiles.core.media.PdfPaging
import ai.victorious.victorfiles.core.viewer.FileKind
import ai.victorious.victorfiles.core.viewer.FileKinds
import ai.victorious.victorfiles.media.AudioPlaybackService
import android.content.ComponentName
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.NavigateBefore
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaScaffold(title: String, onClose: () -> Unit, content: @Composable (Modifier) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Close") }
                },
            )
        },
    ) { padding -> content(Modifier.padding(padding).fillMaxSize()) }
}

// ---------------------------------------------------------------------------------------------
// Gallery (lazy grid of cached thumbnails for images + video frames)
// ---------------------------------------------------------------------------------------------

@Composable
fun GalleryScreen(target: File, onClose: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as FileManagerApplication
    val folder = remember(target) { if (target.isDirectory) target else target.parentFile ?: target }

    val media by produceState<List<File>?>(initialValue = null, folder) {
        value = withContext(Dispatchers.IO) {
            folder.listFiles()
                ?.filter { it.isFile && FileKinds.hasThumbnail(it.name) }
                ?.sortedBy { it.name.lowercase() }
                ?: emptyList()
        }
    }

    var open by remember { mutableStateOf<File?>(null) }
    BackHandler(enabled = open != null) { open = null }

    val current = open
    if (current != null) {
        when (FileKinds.byName(current.name)) {
            FileKind.VIDEO -> VideoPlayerScreen(current, onClose = { open = null })
            else -> ImagePagerScreen(
                images = media.orEmpty().filter { FileKinds.byName(it.name) == FileKind.IMAGE },
                start = current,
                onClose = { open = null },
            )
        }
        return
    }

    MediaScaffold("Gallery · ${folder.name}", onClose) { mod ->
        val list = media
        when {
            list == null -> Box(mod, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Box(mod, contentAlignment = Alignment.Center) {
                Text("No images or videos in this folder", Modifier.padding(24.dp))
            }
            else -> LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 108.dp), modifier = mod) {
                items(list, key = { it.absolutePath }) { file ->
                    GalleryCell(file, isVideo = FileKinds.byName(file.name) == FileKind.VIDEO) { open = file }
                }
            }
        }
    }
}

@Composable
private fun GalleryCell(file: File, isVideo: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as FileManagerApplication
    val targetPx = 256
    val bitmap by produceState<Bitmap?>(initialValue = null, file.absolutePath) {
        value = runCatching { app.thumbnails.load(file, targetPx) }.getOrNull()
    }
    Box(
        Modifier
            .padding(2.dp)
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(bmp.asImageBitmap(), contentDescription = file.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            CircularProgressIndicator(Modifier.size(24.dp))
        }
        if (isVideo) {
            Icon(
                Icons.Outlined.PlayCircle,
                contentDescription = "Video",
                tint = Color.White,
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

@Composable
private fun ImagePagerScreen(images: List<File>, start: File, onClose: () -> Unit) {
    if (images.isEmpty()) { onClose(); return }
    val startIndex = images.indexOfFirst { it.absolutePath == start.absolutePath }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = startIndex) { images.size }
    MediaScaffold(images.getOrNull(pager.currentPage)?.name ?: "Image", onClose) { mod ->
        HorizontalPager(state = pager, modifier = mod.background(Color.Black)) { page ->
            val file = images[page]
            val bmp by produceState<Bitmap?>(initialValue = null, file.absolutePath) {
                value = withContext(Dispatchers.IO) {
                    runCatching {
                        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
                        android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
                    }.getOrNull()
                }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val b = bmp
                if (b != null) Image(b.asImageBitmap(), contentDescription = file.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else CircularProgressIndicator()
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Video player (Media3 ExoPlayer in a PlayerView)
// ---------------------------------------------------------------------------------------------

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(file: File, onClose: () -> Unit) {
    val context = LocalContext.current
    val player = remember {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }
    MediaScaffold(file.name, onClose) { mod ->
        Box(mod.background(Color.Black), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Audio player (controls a background MediaSessionService via MediaController)
// ---------------------------------------------------------------------------------------------

@Composable
fun AudioPlayerScreen(file: File, onClose: () -> Unit) {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }

    DisposableEffect(file.absolutePath) {
        val token = SessionToken(context, ComponentName(context, AudioPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull()
            controller = c
            if (c != null) {
                c.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                c.prepare()
                c.play()
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            controller?.release()
            MediaController.releaseFuture(future)
        }
    }

    var isPlaying by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }

    LaunchedEffect(controller) {
        while (true) {
            controller?.let {
                isPlaying = it.isPlaying
                position = it.currentPosition.coerceAtLeast(0)
                duration = it.duration.coerceAtLeast(0)
            }
            delay(500)
        }
    }

    MediaScaffold(file.name, onClose) { mod ->
        Column(mod.padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.PlayCircle, contentDescription = null, modifier = Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary)
            Text(file.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 16.dp))
            val total = if (duration > 0) duration else 1
            Slider(
                value = position.toFloat().coerceIn(0f, total.toFloat()),
                onValueChange = { controller?.seekTo(it.toLong()) },
                valueRange = 0f..total.toFloat(),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatMs(position), style = MaterialTheme.typography.bodySmall)
                Text(if (duration > 0) formatMs(duration) else "--:--", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = {
                val c = controller ?: return@IconButton
                if (c.isPlaying) c.pause() else c.play()
            }, modifier = Modifier.size(72.dp).padding(top = 8.dp)) {
                Icon(
                    if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(56.dp),
                )
            }
            Text("Playback continues in the background with notification controls.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

// ---------------------------------------------------------------------------------------------
// PDF viewer (Android PdfRenderer)
// ---------------------------------------------------------------------------------------------

@Composable
fun PdfViewerScreen(file: File, onClose: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    var page by remember { mutableIntStateOf(0) }
    var pageCount by remember { mutableIntStateOf(0) }
    var containerWidth by remember { mutableIntStateOf(0) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(file.absolutePath, page, containerWidth) {
        if (containerWidth <= 0) return@LaunchedEffect
        val result = withContext(Dispatchers.IO) { renderPdfPage(file, page, containerWidth) }
        result.fold(
            onSuccess = { (bmp, count, clampedPage) ->
                bitmap = bmp
                pageCount = count
                if (clampedPage != page) page = clampedPage
            },
            onFailure = { error = it.message ?: "Cannot open PDF" },
        )
    }

    val err = error
    if (err != null) {
        MediaScaffold(file.name, onClose) { mod -> Box(mod, contentAlignment = Alignment.Center) { Text(err, Modifier.padding(24.dp)) } }
        return
    }

    MediaScaffold(file.name, onClose) { mod ->
        Column(mod) {
            Box(
                Modifier.weight(1f).fillMaxWidth().background(Color(0xFF202020)),
                contentAlignment = Alignment.Center,
            ) {
                // Capture width in px for the fit-width render.
                AndroidView(
                    factory = { ctx -> android.widget.FrameLayout(ctx) },
                    modifier = Modifier.fillMaxSize(),
                    update = { if (it.width > 0 && it.width != containerWidth) containerWidth = it.width },
                )
                val bmp = bitmap
                if (bmp != null) {
                    Image(bmp.asImageBitmap(), contentDescription = PdfPaging.label(page, pageCount), modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                } else {
                    CircularProgressIndicator()
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { page = PdfPaging.clampPage(page - 1, pageCount) }, enabled = page > 0) {
                    Icon(Icons.AutoMirrored.Outlined.NavigateBefore, contentDescription = "Previous page")
                }
                Text(PdfPaging.label(page, pageCount), style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { page = PdfPaging.clampPage(page + 1, pageCount) }, enabled = page < pageCount - 1) {
                    Icon(Icons.AutoMirrored.Outlined.NavigateNext, contentDescription = "Next page")
                }
            }
        }
    }
}

private data class PdfRenderResult(val bitmap: Bitmap, val pageCount: Int, val page: Int)

private fun renderPdfPage(file: File, requestedPage: Int, targetWidthPx: Int): Result<PdfRenderResult> {
    var pfd: ParcelFileDescriptor? = null
    var renderer: PdfRenderer? = null
    return try {
        pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        renderer = PdfRenderer(pfd)
        val count = renderer.pageCount
        val idx = PdfPaging.clampPage(requestedPage, count)
        renderer.openPage(idx).use { pdfPage ->
            val size = PdfPaging.fitWidth(pdfPage.width, pdfPage.height, targetWidthPx)
            val bmp = Bitmap.createBitmap(size.widthPx, size.heightPx, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.WHITE)
            pdfPage.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            Result.success(PdfRenderResult(bmp, count, idx))
        }
    } catch (t: Throwable) {
        Result.failure(t)
    } finally {
        runCatching { renderer?.close() }
        runCatching { pfd?.close() }
    }
}

// ---------------------------------------------------------------------------------------------
// Font preview (ttf/otf)
// ---------------------------------------------------------------------------------------------

@SuppressLint("SetTextI18n")
@Composable
fun FontPreviewScreen(file: File, onClose: () -> Unit, onMessage: (String) -> Unit) {
    val typeface = remember(file.absolutePath) {
        runCatching { android.graphics.Typeface.createFromFile(file) }.getOrNull()
    }
    MediaScaffold(file.name, onClose) { mod ->
        if (typeface == null) {
            Box(mod, contentAlignment = Alignment.Center) { Text("Could not load this font", Modifier.padding(24.dp)) }
            return@MediaScaffold
        }
        AndroidView(
            modifier = mod,
            factory = { ctx ->
                ScrollView(ctx).apply {
                    val col = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(32, 32, 32, 32)
                    }
                    val pangram = "The quick brown fox jumps over the lazy dog"
                    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ\nabcdefghijklmnopqrstuvwxyz\n0123456789  !?@#&*()"
                    for (sizeSp in intArrayOf(14, 18, 24, 32, 48)) {
                        col.addView(TextView(ctx).apply {
                            text = "${sizeSp}sp  ·  $pangram"
                            setTypeface(typeface)
                            textSize = sizeSp.toFloat()
                            setPadding(0, 16, 0, 16)
                        })
                    }
                    col.addView(TextView(ctx).apply {
                        text = alphabet
                        setTypeface(typeface)
                        textSize = 22f
                        setPadding(0, 24, 0, 0)
                    })
                    addView(col)
                }
            },
        )
    }
}
