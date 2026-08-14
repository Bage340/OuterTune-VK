package com.dd3boh.outertune.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.models.ItemsPage
import com.dd3boh.outertune.providers.search.SearchPayload
import com.dd3boh.outertune.providers.search.UnifiedSearchRepository
import com.dd3boh.outertune.providers.search.UnifiedSearchResult
import com.dd3boh.outertune.providers.search.UnifiedSearchState
import com.dd3boh.outertune.providers.search.UnifiedSearchPhase
import com.dd3boh.outertune.providers.vk.UnsupportedVkMusicProvider
import com.dd3boh.outertune.utils.reportException
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.YouTube.SearchFilter.Companion.FILTER_SONG
import com.zionhuang.innertube.pages.SearchSummaryPage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnlineSearchViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val query = savedStateHandle.get<String>("query")!!
    val filter = MutableStateFlow<YouTube.SearchFilter?>(null)
    var summaryPage by mutableStateOf<SearchSummaryPage?>(null)
    val viewStateMap = mutableStateMapOf<String, ItemsPage?>()
    private val providerSearchStateMap = mutableStateMapOf<String, UnifiedSearchState>()

    // No fake data is wired into production. Replace this provider when an
    // official VK Music implementation becomes available to the application.
    private val unifiedSearchRepository = UnifiedSearchRepository(
        vkProvider = UnsupportedVkMusicProvider()
    )

    init {
        viewModelScope.launch {
            filter.collect { filter ->
                if (filter == null) {
                    if (summaryPage == null &&
                        providerSearchState(filter).phase == UnifiedSearchPhase.IDLE
                    ) {
                        loadSummary()
                    }
                } else if (filter == FILTER_SONG) {
                    if (viewStateMap[filter.value] == null &&
                        providerSearchState(filter).phase == UnifiedSearchPhase.IDLE
                    ) {
                        loadSongs()
                    }
                } else {
                    if (viewStateMap[filter.value] == null) {
                        loadYouTubeFilter(filter)
                    }
                }
            }
        }
    }

    fun providerSearchState(filter: YouTube.SearchFilter?): UnifiedSearchState =
        providerSearchStateMap[providerSearchKey(filter)] ?: UnifiedSearchState()

    fun searchYouTubeToo() {
        viewModelScope.launch {
            when (filter.value) {
                null -> loadSummary(searchYouTubeOnly = true)
                FILTER_SONG -> loadSongs(searchYouTubeOnly = true)
                else -> Unit
            }
        }
    }

    fun retryProviderSearch() {
        viewModelScope.launch {
            when (val selectedFilter = filter.value) {
                null -> {
                    summaryPage = null
                    loadSummary()
                }

                FILTER_SONG -> {
                    viewStateMap.remove(FILTER_SONG.value)
                    loadSongs()
                }

                else -> Unit
            }
        }
    }

    fun loadMore() {
        val filter = filter.value?.value
        viewModelScope.launch {
            if (filter == null) return@launch
            val viewState = viewStateMap[filter] ?: return@launch
            val continuation = viewState.continuation
            if (continuation != null) {
                val searchResult = YouTube.searchContinuation(continuation).getOrNull() ?: return@launch
                viewStateMap[filter] = ItemsPage((viewState.items + searchResult.items).distinctBy { it.id }, searchResult.continuation)
            }
        }
    }

    private suspend fun loadSummary(searchYouTubeOnly: Boolean = false) {
        val key = providerSearchKey(null)
        val previousState = providerSearchState(null)
        providerSearchStateMap[key] = previousState.loading()
        val result = if (searchYouTubeOnly) {
            unifiedSearchRepository.searchYouTubeToo(previousState.vkTracks) {
                YouTube.searchSummary(query)
            }
        } else {
            unifiedSearchRepository.searchBroad(query) {
                YouTube.searchSummary(query)
            }
        }
        applyUnifiedResult(key, result) { summaryPage = it }
    }

    private suspend fun loadSongs(searchYouTubeOnly: Boolean = false) {
        val key = providerSearchKey(FILTER_SONG)
        val previousState = providerSearchState(FILTER_SONG)
        providerSearchStateMap[key] = previousState.loading()
        val youtubeSearch: suspend () -> Result<ItemsPage> = {
            YouTube.search(query, FILTER_SONG).map { result ->
                ItemsPage(
                    items = result.items.distinctBy { it.id },
                    continuation = result.continuation,
                )
            }
        }
        val result = if (searchYouTubeOnly) {
            unifiedSearchRepository.searchYouTubeToo(
                vkTracks = previousState.vkTracks,
                searchYouTube = youtubeSearch,
            )
        } else {
            unifiedSearchRepository.searchBroad(
                query = query,
                searchYouTube = youtubeSearch,
            )
        }
        applyUnifiedResult(key, result) { page ->
            viewStateMap[FILTER_SONG.value] = page
        }
    }

    private suspend fun loadYouTubeFilter(filter: YouTube.SearchFilter) {
        YouTube.search(query, filter)
            .onSuccess { result ->
                viewStateMap[filter.value] = ItemsPage(
                    items = result.items.distinctBy { it.id },
                    continuation = result.continuation,
                )
            }
            .onFailure(::reportException)
    }

    private fun <T> applyUnifiedResult(
        key: String,
        result: UnifiedSearchResult<T>,
        onYouTubeSuccess: (T) -> Unit,
    ) {
        providerSearchStateMap[key] = UnifiedSearchState.from(result)
        when (val youtube = result.youtube) {
            SearchPayload.NotRequested -> Unit
            is SearchPayload.Success -> onYouTubeSuccess(youtube.value)
            is SearchPayload.Failure -> reportException(youtube.cause)
        }
    }

    private fun providerSearchKey(filter: YouTube.SearchFilter?): String =
        filter?.value ?: PROVIDER_SUMMARY_KEY

    companion object {
        private const val PROVIDER_SUMMARY_KEY = "__provider_summary__"
    }
}
