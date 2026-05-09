package com.ayoutube.abn

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.search.SearchExtractor

class YouTubeViewModel : ViewModel() {
    private val _videoItems = MutableStateFlow<List<VideoItem>>(emptyList())
    val videoItems: StateFlow<List<VideoItem>> = _videoItems

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    init {
        fetchTrendingVideos()
    }

    fun fetchTrendingVideos() {
        search("trending")
    }

    fun search(query: String) {
        _searchQuery.value = query
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            try {
                val service = ServiceList.YouTube
                
                // Using search for "trending" as it's often more reliable than the kiosk when YouTube changes its layout
                val extractor = service.getSearchExtractor(query)
                extractor.fetchPage()
                
                val items = extractor.initialPage.items
                    .filterIsInstance<StreamInfoItem>()
                    .map { item ->
                        VideoItem(
                            id = item.url?.substringAfter("v=") ?: "",
                            title = item.name ?: "No Title",
                            url = item.url ?: "",
                            thumbnailUrl = item.thumbnails.firstOrNull()?.url ?: "",
                            uploaderName = item.uploaderName ?: "Unknown"
                        )
                    }
                _videoItems.value = items
            } catch (e: Exception) {
                e.printStackTrace()
                // Fallback or retry logic could go here
            } finally {
                _isLoading.value = false
            }
        }
    }
}
