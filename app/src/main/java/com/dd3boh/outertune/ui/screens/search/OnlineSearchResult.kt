package com.dd3boh.outertune.ui.screens.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalMenuState
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.LocalSnackbarHostState
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AppBarHeight
import com.dd3boh.outertune.constants.ListThumbnailSize
import com.dd3boh.outertune.constants.SearchFilterHeight
import com.dd3boh.outertune.constants.SwipeToQueueKey
import com.dd3boh.outertune.constants.ThumbnailCornerRadius
import com.dd3boh.outertune.extensions.toMediaItem
import com.dd3boh.outertune.extensions.togglePlayPause
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.providers.ProviderId
import com.dd3boh.outertune.providers.domain.RemoteTrack
import com.dd3boh.outertune.providers.search.UnifiedSearchNotice
import com.dd3boh.outertune.providers.search.UnifiedSearchPhase
import com.dd3boh.outertune.ui.component.ChipsRow
import com.dd3boh.outertune.ui.component.EmptyPlaceholder
import com.dd3boh.outertune.ui.component.LazyColumnScrollbar
import com.dd3boh.outertune.ui.component.NavigationTitle
import com.dd3boh.outertune.ui.component.SourceBadge
import com.dd3boh.outertune.ui.component.SwipeToQueueBox
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.component.items.ItemThumbnail
import com.dd3boh.outertune.ui.component.items.ListItem
import com.dd3boh.outertune.ui.component.items.YouTubeListItem
import com.dd3boh.outertune.ui.component.shimmer.ListItemPlaceHolder
import com.dd3boh.outertune.ui.component.shimmer.ShimmerHost
import com.dd3boh.outertune.ui.menu.YouTubeAlbumMenu
import com.dd3boh.outertune.ui.menu.YouTubeArtistMenu
import com.dd3boh.outertune.ui.menu.YouTubePlaylistMenu
import com.dd3boh.outertune.ui.menu.YouTubeSongMenu
import com.dd3boh.outertune.utils.joinByBullet
import com.dd3boh.outertune.utils.makeTimeString
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.viewmodels.OnlineSearchViewModel
import com.zionhuang.innertube.YouTube.SearchFilter.Companion.FILTER_ALBUM
import com.zionhuang.innertube.YouTube.SearchFilter.Companion.FILTER_ARTIST
import com.zionhuang.innertube.YouTube.SearchFilter.Companion.FILTER_COMMUNITY_PLAYLIST
import com.zionhuang.innertube.YouTube.SearchFilter.Companion.FILTER_FEATURED_PLAYLIST
import com.zionhuang.innertube.YouTube.SearchFilter.Companion.FILTER_SONG
import com.zionhuang.innertube.YouTube.SearchFilter.Companion.FILTER_VIDEO
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YTItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URLDecoder

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnlineSearchResult(
    navController: NavController,
    viewModel: OnlineSearchViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val menuState = LocalMenuState.current
    val playerConnection = LocalPlayerConnection.current ?: return

    val swipeEnabled by rememberPreference(SwipeToQueueKey, true)

    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val coroutineScope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()
    val snackbarHostState = LocalSnackbarHostState.current

    val searchFilter by viewModel.filter.collectAsState()
    val searchSummary = viewModel.summaryPage
    val itemsPage by remember(searchFilter) {
        derivedStateOf {
            searchFilter?.value?.let {
                viewModel.viewStateMap[it]
            }
        }
    }
    val providerSearchState by remember(searchFilter) {
        derivedStateOf {
            viewModel.providerSearchState(searchFilter)
        }
    }

    LaunchedEffect(lazyListState) {
        snapshotFlow {
            lazyListState.layoutInfo.visibleItemsInfo.any { it.key == "loading" }
        }.collect { shouldLoadMore ->
            if (!shouldLoadMore) return@collect
            viewModel.loadMore()
        }
    }

    val ytItemContent: @Composable LazyItemScope.(YTItem, List<YTItem>) -> Unit =
        { item: YTItem, collection: List<YTItem> ->
            val content: @Composable () -> Unit = {
                YouTubeListItem(
                    item = item,
                    isActive = when (item) {
                        is SongItem -> mediaMetadata?.id == item.id
                        is AlbumItem -> mediaMetadata?.album?.id == item.id
                        else -> false
                    },
                    isPlaying = isPlaying,
                    trailingContent = {
                        SourceBadge(
                            provider = ProviderId.YOUTUBE,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                        IconButton(
                            onClick = {
                                menuState.show {
                                    when (item) {
                                        is SongItem -> YouTubeSongMenu(
                                            song = item,
                                            navController = navController,
                                            onDismiss = menuState::dismiss
                                        )

                                        is AlbumItem -> YouTubeAlbumMenu(
                                            albumItem = item,
                                            navController = navController,
                                            onDismiss = menuState::dismiss
                                        )

                                        is ArtistItem -> YouTubeArtistMenu(
                                            artist = item,
                                            onDismiss = menuState::dismiss
                                        )

                                        is PlaylistItem -> YouTubePlaylistMenu(
                                            navController = navController,
                                            playlist = item,
                                            coroutineScope = coroutineScope,
                                            onDismiss = menuState::dismiss
                                        )
                                    }
                                }
                            }
                        ) {
                            Icon(
                                Icons.Rounded.MoreVert,
                                contentDescription = null
                            )
                        }

                    },
                    modifier = Modifier
                        .combinedClickable(
                            onClick = {
                                when (item) {
                                    is SongItem -> {
                                        if (item.id == mediaMetadata?.id) {
                                            playerConnection.player.togglePlayPause()
                                        } else {
                                            val songSuggestions = collection.filter { it is SongItem }
                                            playerConnection.playQueue(
                                                ListQueue(
                                                    title = "${context.getString(R.string.queue_searched_songs_ot)} ${
                                                        URLDecoder.decode(
                                                            viewModel.query,
                                                            "UTF-8"
                                                        )
                                                    }",
                                                    items = songSuggestions.map { (it as SongItem).toMediaMetadata() },
                                                    startIndex = songSuggestions.indexOf(item)
                                                ),
                                                replace = true,
                                            )
                                        }
                                    }

                                    is AlbumItem -> navController.navigate("album/${item.id}")
                                    is ArtistItem -> navController.navigate("artist/${item.id}")
                                    is PlaylistItem -> navController.navigate("online_playlist/${item.id}")
                                }
                            },
                            onLongClick = {
                                menuState.show {
                                    when (item) {
                                        is SongItem -> YouTubeSongMenu(
                                            song = item,
                                            navController = navController,
                                            onDismiss = menuState::dismiss
                                        )

                                        else -> {}
                                    }
                                }
                            }
                        )
                        .animateItem()
                )
            }

            if (item !is SongItem) content()
            else SwipeToQueueBox(
                item = item.toMediaItem(),
                swipeEnabled = swipeEnabled,
                snackbarHostState = snackbarHostState,
                content = { content() },
            )
        }

    LazyColumn(
        state = lazyListState,
        contentPadding = LocalPlayerAwareWindowInsets.current
            .add(WindowInsets(top = SearchFilterHeight))
            .asPaddingValues()
    ) {
        if (providerSearchState.isLoading) {
            item(key = "provider-search-loading") {
                DelayedProviderSearchLoading()
            }
        }

        providerSearchState.notice?.let { notice ->
            item(key = "provider-search-notice") {
                ProviderSearchNoticeRow(
                    notice = notice,
                    canRetry = providerSearchState.canRetry,
                    onRetry = viewModel::retryProviderSearch,
                )
            }
        }

        if (providerSearchState.vkTracks.isNotEmpty()) {
            item(key = "provider-vk-title") {
                NavigationTitle(stringResource(R.string.provider_search_vk_section))
            }
            items(
                items = providerSearchState.vkTracks,
                key = { track -> "vk/${track.ownerId.orEmpty()}/${track.remoteId}" },
            ) { track ->
                ProviderTrackSearchRow(track)
            }
        }

        if (providerSearchState.showSearchYouTubeToo) {
            item(key = "provider-search-youtube-too") {
                SearchYouTubeTooButton(onClick = viewModel::searchYouTubeToo)
            }
        }

        if (providerSearchState.youtubeLoaded && providerSearchState.vkTracks.isNotEmpty()) {
            item(key = "provider-youtube-title") {
                NavigationTitle(stringResource(R.string.provider_search_youtube_section))
            }
        }

        if (searchFilter == null) {
            searchSummary?.summaries?.forEach { summary ->
                item {
                    NavigationTitle(summary.title)
                }

                items(
                    items = summary.items,
                    key = { "${summary.title}/${it.id}" }
                ) { item ->
                    ytItemContent(item, summary.items)
                }
            }

            if (searchSummary?.summaries?.isEmpty() == true &&
                providerSearchState.vkTracks.isEmpty()
            ) {
                item {
                    EmptyPlaceholder(
                        icon = Icons.Rounded.Search,
                        text = stringResource(R.string.no_results_found),
                        modifier = Modifier.animateItem()
                    )
                }
            }
        } else {
            items(
                items = itemsPage?.items.orEmpty(),
                key = { it.id }
            ) { item ->
                ytItemContent(item, itemsPage?.items.orEmpty())
            }

            if (itemsPage?.continuation != null) {
                item(key = "loading") {
                    ShimmerHost {
                        repeat(3) {
                            ListItemPlaceHolder()
                        }
                    }
                }
            }

            if (itemsPage?.items?.isEmpty() == true &&
                providerSearchState.vkTracks.isEmpty()
            ) {
                item {
                    EmptyPlaceholder(
                        icon = Icons.Rounded.Search,
                        text = stringResource(R.string.no_results_found),
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }

        val showLoadingPlaceholder = when {
            searchFilter == null -> searchSummary == null &&
                providerSearchState.phase in setOf(
                    UnifiedSearchPhase.IDLE,
                    UnifiedSearchPhase.LOADING,
                )

            searchFilter == FILTER_SONG -> itemsPage == null &&
                providerSearchState.phase in setOf(
                    UnifiedSearchPhase.IDLE,
                    UnifiedSearchPhase.LOADING,
                )

            else -> itemsPage == null
        }
        if (showLoadingPlaceholder) {
            item {
                DelayedSearchPlaceholders()
            }
        }
    }
    LazyColumnScrollbar(
        state = lazyListState,
    )

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
                .align(Alignment.BottomCenter)
        )
    }

    ChipsRow(
        chips = listOf(
            null to stringResource(R.string.filter_all),
            FILTER_SONG to stringResource(R.string.filter_songs),
            FILTER_VIDEO to stringResource(R.string.filter_videos),
            FILTER_ALBUM to stringResource(R.string.filter_albums),
            FILTER_ARTIST to stringResource(R.string.filter_artists),
            FILTER_COMMUNITY_PLAYLIST to stringResource(R.string.filter_community_playlists),
            FILTER_FEATURED_PLAYLIST to stringResource(R.string.filter_featured_playlists)
        ),
        currentValue = searchFilter,
        onValueUpdate = {
            if (viewModel.filter.value != it) {
                viewModel.filter.value = it
            }
            coroutineScope.launch {
                lazyListState.animateScrollToItem(0)
            }
        },
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top).add(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal)))
            .padding(top = AppBarHeight)
    )
}

@Composable
private fun ProviderTrackSearchRow(track: RemoteTrack) {
    ListItem(
        title = track.title,
        subtitle = joinByBullet(
            track.artists.joinToString(),
            makeTimeString(track.durationSeconds?.times(1000L)),
        ),
        badges = {
            SourceBadge(ProviderId.VK)
            Spacer(Modifier.width(4.dp))
        },
        thumbnailContent = {
            ItemThumbnail(
                thumbnailUrl = track.artworkUrl,
                isActive = false,
                isPlaying = false,
                shape = RoundedCornerShape(ThumbnailCornerRadius),
                modifier = Modifier.size(ListThumbnailSize),
            )
        },
    )
}

@Composable
private fun ProviderSearchNoticeRow(
    notice: UnifiedSearchNotice,
    canRetry: Boolean,
    onRetry: () -> Unit,
) {
    val message = stringResource(
        when (notice) {
            UnifiedSearchNotice.VK_SIGN_IN_REQUIRED -> R.string.provider_search_vk_sign_in
            UnifiedSearchNotice.VK_SEARCH_UNAVAILABLE -> R.string.provider_search_vk_unavailable
            UnifiedSearchNotice.VK_SEARCH_FAILED -> R.string.provider_search_vk_failed
            UnifiedSearchNotice.VK_NO_HIGH_CONFIDENCE_MATCH -> R.string.provider_search_no_high_match
            UnifiedSearchNotice.YOUTUBE_SEARCH_FAILED -> R.string.provider_search_youtube_failed
        }
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = message,
            color = MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        if (canRetry) {
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.provider_search_retry))
            }
        }
    }
}

@Composable
private fun SearchYouTubeTooButton(onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 8.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.PlayCircle,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.provider_search_youtube_too))
    }
}

@Composable
private fun DelayedProviderSearchLoading() {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(PROVIDER_LOADING_FEEDBACK_DELAY_MS)
        visible = true
    }
    if (!visible) return

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
        )
        Text(
            text = stringResource(R.string.provider_search_loading),
            color = MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun DelayedSearchPlaceholders() {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(PROVIDER_LOADING_FEEDBACK_DELAY_MS)
        visible = true
    }
    if (!visible) return

    ShimmerHost {
        repeat(8) {
            ListItemPlaceHolder()
        }
    }
}

private const val PROVIDER_LOADING_FEEDBACK_DELAY_MS = 300L
