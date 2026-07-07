package com.dd3boh.outertune.ui.screens.library

import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.LocalSnackbarHostState
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.CONTENT_TYPE_HEADER
import com.dd3boh.outertune.constants.CONTENT_TYPE_SONG
import com.dd3boh.outertune.constants.FolderSongSortType
import com.dd3boh.outertune.constants.GridThumbnailHeight
import com.dd3boh.outertune.constants.LibraryViewType
import com.dd3boh.outertune.constants.ListThumbnailSize
import com.dd3boh.outertune.constants.LocalFilter
import com.dd3boh.outertune.constants.LocalFilterKey
import com.dd3boh.outertune.constants.LocalLibraryEnableKey
import com.dd3boh.outertune.constants.LocalSongSortDescendingKey
import com.dd3boh.outertune.constants.LocalSongSortTypeKey
import com.dd3boh.outertune.constants.LocalViewTypeKey
import com.dd3boh.outertune.constants.SwipeToQueueKey
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.ui.component.ChipsRow
import com.dd3boh.outertune.ui.component.EmptyPlaceholder
import com.dd3boh.outertune.ui.component.LazyColumnScrollbar
import com.dd3boh.outertune.ui.component.LazyVerticalGridScrollbar
import com.dd3boh.outertune.ui.component.ScrollToTopManager
import com.dd3boh.outertune.ui.component.SortHeader
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.component.items.SongGridItem
import com.dd3boh.outertune.ui.component.items.SongListItem
import com.dd3boh.outertune.ui.utils.MEDIA_PERMISSION_LEVEL
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.viewmodels.LocalLibraryViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalScreen(
    navController: NavController,
    viewModel: LocalLibraryViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val snackbarHostState = LocalSnackbarHostState.current

    var filter by rememberEnumPreference(LocalFilterKey, LocalFilter.SONGS)
    var viewType by rememberEnumPreference(LocalViewTypeKey, LibraryViewType.GRID)
    val (sortType, onSortTypeChange) = rememberEnumPreference(LocalSongSortTypeKey, FolderSongSortType.TRACK_NUMBER)
    val (sortDescending, onSortDescendingChange) = rememberPreference(LocalSongSortDescendingKey, false)
    val localLibEnable by rememberPreference(LocalLibraryEnableKey, defaultValue = true)
    val swipeEnabled by rememberPreference(SwipeToQueueKey, true)

    val songs by viewModel.localSongs.collectAsState()
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val lazyListState = rememberLazyListState()
    val lazyGridState = rememberLazyGridState()

    val filterContent = @Composable {
        var showStoragePerm by remember {
            mutableStateOf(context.checkSelfPermission(MEDIA_PERMISSION_LEVEL) != PackageManager.PERMISSION_GRANTED)
        }
        Column {
            if (localLibEnable && showStoragePerm) {
                TextButton(
                    onClick = {
                        showStoragePerm = false
                        (context as MainActivity).permissionLauncher.launch(MEDIA_PERMISSION_LEVEL)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.error)
                ) {
                    Text(
                        text = stringResource(R.string.missing_media_permission_warning),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
            Row {
                ChipsRow(
                    chips = listOf(
                        LocalFilter.SONGS to stringResource(R.string.songs),
                        LocalFilter.ALBUMS to stringResource(R.string.albums),
                        LocalFilter.ARTISTS to stringResource(R.string.artists),
                        LocalFilter.PLAYLISTS to stringResource(R.string.playlists),
                    ),
                    currentValue = filter,
                    onValueUpdate = { filter = it },
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = {
                        viewType = viewType.toggle()
                    },
                    modifier = Modifier.padding(end = 6.dp)
                ) {
                    Icon(
                        imageVector = when (viewType) {
                            LibraryViewType.LIST -> Icons.AutoMirrored.Rounded.List
                            LibraryViewType.GRID -> Icons.Rounded.GridView
                        },
                        contentDescription = null
                    )
                }
            }
        }
    }

    val songHeaderContent = @Composable {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            SortHeader(
                sortType = sortType,
                sortDescending = sortDescending,
                onSortTypeChange = onSortTypeChange,
                onSortDescendingChange = onSortDescendingChange,
                sortTypeText = { sortType ->
                    when (sortType) {
                        FolderSongSortType.CREATE_DATE -> R.string.sort_by_create_date
                        FolderSongSortType.MODIFIED_DATE -> R.string.sort_by_date_modified
                        FolderSongSortType.RELEASE_DATE -> R.string.sort_by_date_released
                        FolderSongSortType.NAME -> R.string.sort_by_name
                        FolderSongSortType.ARTIST -> R.string.sort_by_artist
                        FolderSongSortType.PLAY_COUNT -> R.string.sort_by_play_count
                        FolderSongSortType.TRACK_NUMBER -> R.string.sort_by_track_number
                    }
                }
            )

            Spacer(Modifier.weight(1f))

            songs?.let { songs ->
                Text(
                    text = pluralStringResource(R.plurals.n_song, songs.size, songs.size),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        ScrollToTopManager(navController, lazyListState)
        when (viewType) {
            LibraryViewType.LIST -> {
                LazyColumn(
                    state = lazyListState,
                    contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()
                ) {
                    item(
                        key = "filter",
                        contentType = CONTENT_TYPE_HEADER
                    ) {
                        Column(
                            modifier = Modifier.background(MaterialTheme.colorScheme.background)
                        ) {
                            filterContent()
                        }
                    }

                    when (filter) {
                        LocalFilter.SONGS -> {
                            item(
                                key = "header",
                                contentType = CONTENT_TYPE_HEADER
                            ) {
                                songHeaderContent()
                            }

                            songs?.let { songs ->
                                if (songs.isEmpty()) {
                                    item {
                                        EmptyPlaceholder(
                                            icon = Icons.Rounded.MusicNote,
                                            text = stringResource(R.string.library_song_empty),
                                            modifier = Modifier.animateItem()
                                        )
                                    }
                                }
                                val thumbnailSize = (ListThumbnailSize.value * density.density).roundToInt()
                                itemsIndexed(
                                    items = songs,
                                    key = { _, item -> item.id },
                                    contentType = { _, _ -> CONTENT_TYPE_SONG }
                                ) { index, song ->
                                    SongListItem(
                                        song = song,
                                        navController = navController,
                                        snackbarHostState = snackbarHostState,
                                        isActive = song.song.id == mediaMetadata?.id,
                                        isPlaying = isPlaying,
                                        inSelectMode = false,
                                        isSelected = false,
                                        onSelectedChange = {},
                                        swipeEnabled = swipeEnabled,
                                        thumbnailSize = thumbnailSize,
                                        onPlay = {
                                            playerConnection.playQueue(
                                                ListQueue(
                                                    title = context.getString(R.string.local_files),
                                                    items = songs.map { it.toMediaMetadata() },
                                                    startIndex = index
                                                )
                                            )
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .animateItem()
                                    )
                                }
                            }
                        }

                        else -> {
                            item {
                                LocalPlaceholder(filter)
                            }
                        }
                    }
                }
                LazyColumnScrollbar(
                    state = lazyListState,
                )
            }

            LibraryViewType.GRID -> {
                LazyVerticalGrid(
                    state = lazyGridState,
                    columns = GridCells.Adaptive(minSize = GridThumbnailHeight + 24.dp),
                    contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()
                ) {
                    item(
                        key = "filter",
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = CONTENT_TYPE_HEADER
                    ) {
                        Column(
                            modifier = Modifier.background(MaterialTheme.colorScheme.background)
                        ) {
                            filterContent()
                        }
                    }

                    when (filter) {
                        LocalFilter.SONGS -> {
                            item(
                                key = "header",
                                span = { GridItemSpan(maxLineSpan) },
                                contentType = CONTENT_TYPE_HEADER
                            ) {
                                songHeaderContent()
                            }

                            songs?.let { songs ->
                                if (songs.isEmpty()) {
                                    item(span = { GridItemSpan(maxLineSpan) }) {
                                        EmptyPlaceholder(
                                            icon = Icons.Rounded.MusicNote,
                                            text = stringResource(R.string.library_song_empty),
                                            modifier = Modifier.animateItem()
                                        )
                                    }
                                }
                                itemsIndexed(
                                    items = songs,
                                    key = { _, item -> item.id },
                                    contentType = { _, _ -> CONTENT_TYPE_SONG }
                                ) { index, song ->
                                    SongGridItem(
                                        song = song,
                                        isActive = song.song.id == mediaMetadata?.id,
                                        isPlaying = isPlaying,
                                        fillMaxWidth = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                playerConnection.playQueue(
                                                    ListQueue(
                                                        title = context.getString(R.string.local_files),
                                                        items = songs.map { it.toMediaMetadata() },
                                                        startIndex = index
                                                    )
                                                )
                                            }
                                            .animateItem()
                                    )
                                }
                            }
                        }

                        else -> {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                LocalPlaceholder(filter)
                            }
                        }
                    }
                }
                LazyVerticalGridScrollbar(
                    state = lazyGridState,
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
                .align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun LocalPlaceholder(filter: LocalFilter) {
    when (filter) {
        LocalFilter.ALBUMS -> EmptyPlaceholder(
            icon = Icons.Rounded.Album,
            text = stringResource(R.string.library_album_empty)
        )

        LocalFilter.ARTISTS -> EmptyPlaceholder(
            icon = Icons.Rounded.Person,
            text = stringResource(R.string.library_artist_empty)
        )

        LocalFilter.PLAYLISTS -> EmptyPlaceholder(
            icon = Icons.AutoMirrored.Rounded.QueueMusic,
            text = stringResource(R.string.library_playlist_empty)
        )

        LocalFilter.SONGS -> Unit
    }
}
