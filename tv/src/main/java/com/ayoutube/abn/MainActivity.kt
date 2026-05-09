package com.ayoutube.abn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Card
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.ayoutube.abn.ui.theme.AyoutubeTheme

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AyoutubeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape
                ) {
                    YouTubeTvMainScreen()
                }
            }
        }
    }
}

@Composable
fun YouTubeTvMainScreen(viewModel: YouTubeViewModel = viewModel()) {
    var isSearchActive by remember { mutableStateOf(false) }
    
    Row(modifier = Modifier.fillMaxSize()) {
        SideNavigationRail(
            onSearchClick = { isSearchActive = !isSearchActive },
            onHomeClick = { 
                isSearchActive = false
                viewModel.fetchTrendingVideos()
            }
        )
        YouTubeTvHomeScreen(viewModel = viewModel, isSearchActive = isSearchActive)
    }
}

@Composable
fun SideNavigationRail(onSearchClick: () -> Unit, onHomeClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(80.dp)
            .background(Color(0xFF0F0F0F))
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        NavigationIcon(Icons.Default.Search, onClick = onSearchClick)
        Spacer(modifier = Modifier.weight(1f))
        NavigationIcon(Icons.Default.Home, isSelected = true, onClick = onHomeClick)
        NavigationIcon(Icons.Default.List)
        NavigationIcon(Icons.Default.Person)
        Spacer(modifier = Modifier.weight(1f))
        NavigationIcon(Icons.Default.Settings)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NavigationIcon(icon: ImageVector, isSelected: Boolean = false, onClick: () -> Unit = {}) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .padding(vertical = 12.dp)
            .width(48.dp)
            .aspectRatio(1f),
        shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) Color.White.copy(alpha = 0.1f) else Color.Transparent,
            focusedContainerColor = Color.White.copy(alpha = 0.2f),
            pressedContainerColor = Color.White.copy(alpha = 0.3f)
        )
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) Color.White else Color.Gray
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun YouTubeTvHomeScreen(viewModel: YouTubeViewModel, isSearchActive: Boolean) {
    val videos by viewModel.videoItems.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val currentQuery by viewModel.searchQuery.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(Color.Black).padding(start = 24.dp, top = 24.dp, end = 24.dp)) {
        if (isSearchActive) {
            SearchBar(
                initialValue = if (currentQuery == "trending") "" else currentQuery,
                onSearch = { query ->
                    viewModel.search(query)
                }
            )
            Spacer(modifier = Modifier.padding(12.dp))
        } else {
            Text(
                text = if (currentQuery == "trending") "Home" else "Results for: $currentQuery",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(bottom = 24.dp)
            )
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Loading videos...", color = Color.White)
            }
        } else if (videos.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No videos found", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.padding(8.dp))
                    Text("Check your internet connection or try a different search.", color = Color.Gray)
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(videos) { video ->
                    VideoCard(video = video)
                }
            }
        }
    }
}

@Composable
fun SearchBar(initialValue: String, onSearch: (String) -> Unit) {
    var text by remember { mutableStateOf(initialValue) }

    TextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.DarkGray, CircleShape),
        placeholder = { Text("Search", color = Color.Gray) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch(text) }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White
        )
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun VideoCard(video: VideoItem) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Card(
        onClick = { VideoPlayerActivity.launch(context, video.url, video.title) },
        modifier = Modifier
            .padding(8.dp)
            .fillMaxWidth()
    ) {
        Column {
            AsyncImage(
                model = video.thumbnailUrl,
                contentDescription = video.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                contentScale = ContentScale.Crop
            )
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = video.uploaderName,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    maxLines = 1
                )
            }
        }
    }
}
