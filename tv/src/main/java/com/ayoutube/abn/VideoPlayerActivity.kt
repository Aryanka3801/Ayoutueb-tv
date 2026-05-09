package com.ayoutube.abn

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
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

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable {
        showControls.value = false
    }

    companion object {
        const val EXTRA_URL = "VIDEO_URL"
        const val EXTRA_TITLE = "VIDEO_TITLE"

        fun launch(context: Context, url: String, title: String) {
            val intent = Intent(context, VideoPlayerActivity::class.java)
            intent.putExtra(EXTRA_URL, url)
            intent.putExtra(EXTRA_TITLE, title)
            context.startActivity(intent)
        }
    }

    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val videoUrl = intent.getStringExtra(EXTRA_URL) ?: ""
        val videoTitle = intent.getStringExtra(EXTRA_TITLE) ?: ""

        // Explicit Looper required for Android 9 stability
        player = ExoPlayer.Builder(this)
            .setLooper(Looper.getMainLooper())
            .build()

        player?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying.value = playing
            }

            // Catch ExoPlayer errors on Android 9 instead of crashing
            override fun onPlayerError(error: PlaybackException) {
                error.printStackTrace()
                hasError.value = true
                isLoading.value = false
            }

            // Update loading state when player is actually ready
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    isLoading.value = false
                } else if (playbackState == Player.STATE_BUFFERING) {
                    isLoading.value = true
                }
            }
        })

        loadVideo(videoUrl)

        setContent {
            AyoutubeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape
                ) {
                    VideoPlayerScreen(
                        activity = this@VideoPlayerActivity,
                        title = videoTitle
                    )
                }
            }
        }
    }

    private fun loadVideo(videoUrl: String) {
        lifecycleScope.launch {
            isLoading.value = true
            hasError.value = false

            withContext(Dispatchers.IO) {
                try {
                    val info = StreamInfo.getInfo(ServiceList.YouTube, videoUrl)

                    val muxedStream = info.videoStreams
                        .filter { it.content.isNotEmpty() }
                        .maxByOrNull { it.height }

                    val videoOnlyStream = if (muxedStream == null) {
                        info.videoOnlyStreams
                            .filter { it.content.isNotEmpty() }
                            .maxByOrNull { it.height }
                    } else null

                    val audioStream = info.audioStreams
                        .filter { it.content.isNotEmpty() }
                        .maxByOrNull { it.averageBitrate }

                    withContext(Dispatchers.Main) {
                        // HTTP factory with User-Agent and increased timeouts for Android 9
                        val httpFactory = DefaultHttpDataSource.Factory()
                            .setConnectTimeoutMs(30000)
                            .setReadTimeoutMs(30000)
                            .setAllowCrossProtocolRedirects(true)
                            .setDefaultRequestProperties(
                                mapOf(
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                                )
                            )

                        // Explicit extractor factory avoids codec detection issues on Android 9
                        val extractorsFactory = DefaultExtractorsFactory()

                        val videoStreamUrl = muxedStream?.content ?: videoOnlyStream?.content
                        if (videoStreamUrl != null && audioStream != null && muxedStream == null) {
                            val videoSource = ProgressiveMediaSource.Factory(httpFactory, extractorsFactory)
                                .createMediaSource(MediaItem.fromUri(videoStreamUrl))
                            val audioSource = ProgressiveMediaSource.Factory(httpFactory, extractorsFactory)
                                .createMediaSource(MediaItem.fromUri(audioStream.content))
                            val mergedSource = MergingMediaSource(videoSource, audioSource)
                            player?.setMediaSource(mergedSource)
                        } else if (videoStreamUrl != null) {
                            player?.setMediaItem(MediaItem.fromUri(videoStreamUrl))
                        } else if (audioStream != null) {
                            player?.setMediaItem(MediaItem.fromUri(audioStream.content))
                        } else {
                            hasError.value = true
                            isLoading.value = false
                            return@withContext
                        }
                        player?.prepare()
                        player?.playWhenReady = true
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        hasError.value = true
                        isLoading.value = false
                    }
                }
            }
        }
    }

    fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    fun seekForward() {
        val current = player?.currentPosition ?: 0L
        player?.seekTo(current + 10_000L)
    }

    fun seekBackward() {
        val current = player?.currentPosition ?: 0L
        player?.seekTo((current - 10_000L).coerceAtLeast(0L))
    }

    fun showControlsTemporarily() {
        showControls.value = true
        hideHandler.removeCallbacks(hideRunnable)
        hideHandler.postDelayed(hideRunnable, 3000L)
    }

    fun updateProgress() {
        player?.let { p ->
            position.longValue = p.currentPosition
            duration.longValue = p.duration.coerceAtLeast(1L)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        showControlsTemporarily()
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                togglePlayPause()
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                seekForward()
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                seekBackward()
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                finish()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        hideHandler.removeCallbacks(hideRunnable)
        player?.release()
        player = null
    }
}

// Locale.US prevents crashes on Arabic/Persian/other locales on Android 9
fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val hours = totalSec / 3600
    val mins = (totalSec % 3600) / 60
    val secs = totalSec % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, mins, secs)
    } else {
        String.format(Locale.US, "%d:%02d", mins, secs)
    }
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

    LaunchedEffect(Unit) {
        while (isActive) {
            activity.updateProgress()
            delay(500L)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    this.player = activity.player
                }
            },
            update = { playerView ->
                playerView.player = activity.player
            },
            modifier = Modifier.fillMaxSize()
        )

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Loading video...",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = title,
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        if (hasError) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Could not load video",
                        color = Color.Red,
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Press BACK to return",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        if (showControls && !isLoading && !hasError) {
            LaunchedEffect(Unit) {
                try {
                    focusRequester.requestFocus()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.5f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.5f)
                            )
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(horizontal = 48.dp, vertical = 32.dp)
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.fillMaxWidth(0.9f)
                )
            }

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    onClick = { activity.togglePlayPause() },
                    shape = ClickableSurfaceDefaults.shape(CircleShape),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.White.copy(alpha = 0.2f),
                        contentColor = Color.White,
                        focusedContainerColor = Color.White,
                        focusedContentColor = Color.Black
                    ),
                    modifier = Modifier
                        .size(86.dp)
                        .focusRequester(focusRequester)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(52.dp)
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 48.dp, vertical = 32.dp)
            ) {
                val progress = if (duration > 0L) (position.toFloat() / duration.toFloat()) else 0f
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.5.dp)
                        .background(Color.White.copy(alpha = 0.3f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .background(Color.White)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatTime(position),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        text = formatTime(duration),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = ClickableSurfaceDefaults.shape(CircleShape),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = Color.White.copy(alpha = 0.2f),
                                contentColor = Color.White,
                                focusedContainerColor = Color.White,
                                focusedContentColor = Color.Black
                            ),
                            modifier = Modifier.size(46.dp),
                            onClick = {}
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Text("\uD83C\uDF3B", style = MaterialTheme.typography.headlineSmall)
                            }
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        ControlChip(text = "Description")
                        Spacer(modifier = Modifier.width(12.dp))
                        ControlChip(text = "Subscribe")
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .background(Color.White.copy(alpha = 0.12f), CircleShape)
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Row {
                                ControlIconButton(Icons.Default.ThumbUp, "Like")
                                ControlIconButton(Icons.Default.ThumbDown, "Dislike")
                                ControlIconButton(Icons.AutoMirrored.Filled.Chat, "Comments")
                                ControlIconButton(Icons.Default.Bookmark, "Save")
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Box(
                            modifier = Modifier
                                .background(Color.White.copy(alpha = 0.12f), CircleShape)
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Row {
                                ControlIconButton(Icons.Default.ClosedCaption, "CC")
                                ControlIconButton(Icons.Default.Settings, "Settings")
                            }
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
    Surface(
        onClick = { },
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.15f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black
        ),
        modifier = Modifier.padding(end = 4.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ControlIconButton(icon: ImageVector, contentDescription: String) {
    Surface(
        onClick = { },
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black
        ),
        modifier = Modifier.size(46.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
