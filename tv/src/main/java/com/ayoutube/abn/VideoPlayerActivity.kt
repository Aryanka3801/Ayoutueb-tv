package com.ayoutube.abn

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.*
import com.ayoutube.abn.ui.theme.AyoutubeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.Locale

class VideoPlayerActivity : ComponentActivity() {

    var player: ExoPlayer? = null

    val showControls = mutableStateOf(true)
    val isPlaying = mutableStateOf(false)
    val isLoading = mutableStateOf(true)
    val hasError = mutableStateOf(false)
    val position = mutableLongStateOf(0L)
    val duration = mutableLongStateOf(1L)

    // Fix #5: detect Android 9 for codec-safe stream selection
    private val isAndroid9OrBelow = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P

    private val hideHandler = Handler(Looper.getMainLooper())

    // Fix #9: guard against post-destroy state mutation from the auto-hide runnable
    private val hideRunnable = Runnable {
        if (!isDestroyed && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            showControls.value = false
        }
    }

    companion object {
        const val EXTRA_URL = "VIDEO_URL"
        const val EXTRA_TITLE = "VIDEO_TITLE"
        fun launch(context: Context, url: String, title: String) {
            context.startActivity(Intent(context, VideoPlayerActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
            })
        }
    }

    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val videoUrl = intent.getStringExtra(EXTRA_URL) ?: ""
        val videoTitle = intent.getStringExtra(EXTRA_TITLE) ?: ""

        player = ExoPlayer.Builder(this).setLooper(Looper.getMainLooper()).build()
        player?.addListener(object : Player.Listener {
            // Fix #7: only mutate state while Activity is alive
            override fun onIsPlayingChanged(playing: Boolean) {
                if (!isDestroyed) isPlaying.value = playing
            }
            // Fix #8: catch ExoPlayer errors gracefully — avoids crash on Android 9
            override fun onPlayerError(error: PlaybackException) {
                error.printStackTrace()
                if (!isDestroyed) { hasError.value = true; isLoading.value = false }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (isDestroyed) return
                when (state) {
                    Player.STATE_READY -> isLoading.value = false
                    Player.STATE_BUFFERING -> isLoading.value = true
                    Player.STATE_ENDED -> showControls.value = true
                    else -> {}
                }
            }
        })

        loadVideo(videoUrl)
        setContent {
            AyoutubeTheme {
                Surface(modifier = Modifier.fillMaxSize(), shape = RectangleShape) {
                    VideoPlayerScreen(activity = this@VideoPlayerActivity, title = videoTitle)
                }
            }
        }
    }

    private fun loadVideo(videoUrl: String) {
        lifecycleScope.launch {
            // Fix #8: bail early if already destroyed (back pressed before launch)
            if (isDestroyed) return@launch
            isLoading.value = true
            hasError.value = false

            withContext(Dispatchers.IO) {
                try {
                    val info = StreamInfo.getInfo(ServiceList.YouTube, videoUrl)

                    // Fix #5: On Android 9 cap at 720p to stay within H.264/AAC codec
                    // support and avoid VP9+Opus MergingMediaSource failures
                    val muxedStream = info.videoStreams
                        .filter { it.content.isNotEmpty() }
                        .let { list ->
                            if (isAndroid9OrBelow)
                                list.filter { it.height <= 720 }.maxByOrNull { it.height }
                                    ?: list.minByOrNull { it.height }
                            else list.maxByOrNull { it.height }
                        }

                    val videoOnlyStream = if (muxedStream == null) {
                        info.videoOnlyStreams
                            .filter { it.content.isNotEmpty() }
                            .let { list ->
                                if (isAndroid9OrBelow)
                                    list.filter { it.height <= 720 }.maxByOrNull { it.height }
                                        ?: list.minByOrNull { it.height }
                                else list.maxByOrNull { it.height }
                            }
                    } else null

                    val audioStream = info.audioStreams
                        .filter { it.content.isNotEmpty() }
                        .maxByOrNull { it.averageBitrate }

                    withContext(Dispatchers.Main) {
                        // Fix #8: double-check — IO took time, Activity may be gone now
                        if (isDestroyed || player == null) return@withContext

                        val httpFactory = DefaultHttpDataSource.Factory()
                            .setConnectTimeoutMs(30000)
                            .setReadTimeoutMs(30000)
                            .setAllowCrossProtocolRedirects(true)
                            .setDefaultRequestProperties(mapOf(
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36"
                            ))

                        // Fix #5: explicit extractors factory avoids codec detection
                        // failures on Android 9's stricter media pipeline
                        val ef = DefaultExtractorsFactory()
                        val videoUrl2 = muxedStream?.content ?: videoOnlyStream?.content

                        when {
                            videoUrl2 != null && audioStream != null && muxedStream == null -> {
                                val vs = ProgressiveMediaSource.Factory(httpFactory, ef)
                                    .createMediaSource(MediaItem.fromUri(videoUrl2))
                                val aus = ProgressiveMediaSource.Factory(httpFactory, ef)
                                    .createMediaSource(MediaItem.fromUri(audioStream.content))
                                player?.setMediaSource(MergingMediaSource(vs, aus))
                            }
                            videoUrl2 != null -> player?.setMediaItem(MediaItem.fromUri(videoUrl2))
                            audioStream != null -> player?.setMediaItem(MediaItem.fromUri(audioStream.content))
                            else -> { hasError.value = true; isLoading.value = false; return@withContext }
                        }
                        player?.prepare()
                        player?.playWhenReady = true
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        if (!isDestroyed) { hasError.value = true; isLoading.value = false }
                    }
                }
            }
        }
    }

    fun togglePlayPause() { player?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun seekForward() { player?.seekTo((player?.currentPosition ?: 0L) + 10_000L) }
    fun seekBackward() { player?.seekTo(((player?.currentPosition ?: 0L) - 10_000L).coerceAtLeast(0L)) }

    fun showControlsTemporarily() {
        showControls.value = true
        hideHandler.removeCallbacks(hideRunnable)
        hideHandler.postDelayed(hideRunnable, 3000L)
    }

    fun updateProgress() {
        if (isDestroyed) return
        player?.let { p ->
            position.longValue = p.currentPosition
            duration.longValue = p.duration.coerceAtLeast(1L)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        showControlsTemporarily()
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlayPause(); true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekForward(); true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBackward(); true }
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onPause() { super.onPause(); player?.pause() }

    override fun onDestroy() {
        super.onDestroy()
        // Fix #9: remove pending callbacks before releasing player
        hideHandler.removeCallbacks(hideRunnable)
        player?.release()
        player = null
    }
}

// Fix #7: Locale.US prevents format crashes in Arabic/Persian locales on Android 9
fun formatTime(ms: Long): String {
    val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%d:%02d", m, sec)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun VideoPlayerScreen(activity: VideoPlayerActivity, title: String) {
    val showControls by activity.showControls
    val isPlaying by activity.isPlaying
    val isLoading by activity.isLoading
    val hasError by activity.hasError
    val position by activity.position
    val duration by activity.duration
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { while (isActive) { activity.updateProgress(); delay(500L) } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { useController = false; this.player = activity.player } },
            update = { it.player = activity.player },
            modifier = Modifier.fillMaxSize()
        )
        if (isLoading) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Loading video...", color = Color.White, style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(title, color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (hasError) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Could not load video", color = Color.Red, style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("Press BACK to return", color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (showControls && !isLoading && !hasError) {
            // Fix #12: try/catch around requestFocus — Android 9 TV Material can throw
            // NullPointerException if ViewTreeLifecycleOwner isn't attached yet
            LaunchedEffect(Unit) { try { focusRequester.requestFocus() } catch (_: Exception) { } }

            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(.5f), Color.Transparent, Color.Black.copy(.5f)))))
            Column(Modifier.align(Alignment.TopStart).padding(horizontal = 48.dp, vertical = 32.dp)) {
                Text(title, color = Color.White, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.fillMaxWidth(.9f))
            }
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Surface(
                    onClick = { activity.togglePlayPause() },
                    shape = ClickableSurfaceDefaults.shape(CircleShape),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                    colors = ClickableSurfaceDefaults.colors(Color.White.copy(.2f), Color.White, focusedContainerColor = Color.White, focusedContentColor = Color.Black),
                    modifier = Modifier.size(86.dp).focusRequester(focusRequester)
                ) {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (isPlaying) "Pause" else "Play", Modifier.size(52.dp))
                    }
                }
            }
            Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 48.dp, vertical = 32.dp)) {
                val progress = if (duration > 0L) position.toFloat() / duration.toFloat() else 0f
                Box(Modifier.fillMaxWidth().height(2.5.dp).background(Color.White.copy(.3f))) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Color.White))
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    Text(formatTime(position), color = Color.White, style = MaterialTheme.typography.labelMedium)
                    Text(formatTime(duration), color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = ClickableSurfaceDefaults.shape(CircleShape), scale = ClickableSurfaceDefaults.scale(1f),
                            colors = ClickableSurfaceDefaults.colors(Color.White.copy(.2f), Color.White, focusedContainerColor = Color.White, focusedContentColor = Color.Black),
                            modifier = Modifier.size(46.dp), onClick = {}) {
                            Box(Modifier.fillMaxSize(), Alignment.Center) { Text("\uD83C\uDF3B", style = MaterialTheme.typography.headlineSmall) }
                        }
                        Spacer(Modifier.width(16.dp))
                        ControlChip("Description"); Spacer(Modifier.width(12.dp)); ControlChip("Subscribe")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.background(Color.White.copy(.12f), CircleShape).padding(horizontal = 4.dp, vertical = 2.dp)) {
                            Row {
                                ControlIconButton(Icons.Default.ThumbUp, "Like")
                                ControlIconButton(Icons.Default.ThumbDown, "Dislike")
                                ControlIconButton(Icons.AutoMirrored.Filled.Chat, "Comments")
                                ControlIconButton(Icons.Default.Bookmark, "Save")
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Box(Modifier.background(Color.White.copy(.12f), CircleShape).padding(horizontal = 4.dp, vertical = 2.dp)) {
                            Row { ControlIconButton(Icons.Default.ClosedCaption, "CC"); ControlIconButton(Icons.Default.Settings, "Settings") }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ControlChip(text: String) {
    Surface(onClick = {}, shape = ClickableSurfaceDefaults.shape(CircleShape), scale = ClickableSurfaceDefaults.scale(1f),
        colors = ClickableSurfaceDefaults.colors(Color.White.copy(.15f), Color.White, focusedContainerColor = Color.White, focusedContentColor = Color.Black),
        modifier = Modifier.padding(end = 4.dp)) {
        Text(text, Modifier.padding(horizontal = 18.dp, vertical = 10.dp), style = MaterialTheme.typography.labelLarge)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ControlIconButton(icon: ImageVector, contentDescription: String) {
    Surface(onClick = {}, shape = ClickableSurfaceDefaults.shape(CircleShape), scale = ClickableSurfaceDefaults.scale(1f),
        colors = ClickableSurfaceDefaults.colors(Color.Transparent, Color.White, focusedContainerColor = Color.White, focusedContentColor = Color.Black),
        modifier = Modifier.size(46.dp)) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Icon(icon, contentDescription, Modifier.size(24.dp)) }
    }
}
