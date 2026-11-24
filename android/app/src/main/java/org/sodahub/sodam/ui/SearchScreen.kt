package org.sodahub.sodam.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.sodam_ffi.AlbumSummary
import uniffi.sodam_ffi.ArtistSummary
import uniffi.sodam_ffi.PlaylistSummary
import uniffi.sodam_ffi.SearchResults
import uniffi.sodam_ffi.SodaClient
import uniffi.sodam_ffi.SodaException
import uniffi.sodam_ffi.Track

/** 官方搜索页的五个范围（综合里的分组展示由界面自己汇总）。 */
private enum class SearchTab(val label: String) {
    All("综合"),
    Tracks("歌曲"),
    Playlists("歌单"),
    Artists("歌手"),
    Albums("专辑"),
}

/** 综合页每个分组最多预览几条，其余引导到对应 Tab。 */
private const val PREVIEW_COUNT = 4

/** 音乐人/专辑/歌单的详情页：头部信息 + 曲目列表。 */
internal sealed interface SearchDetail {
    val title: String
    val subtitle: String
    val cover: String
    val circular: Boolean

    data class Playlist(val item: PlaylistSummary) : SearchDetail {
        override val title: String get() = item.name
        override val subtitle: String
            get() = buildString {
                if (item.creator.isNotBlank()) append(item.creator).append(" · ")
                append("${item.trackCount} 首")
            }
        override val cover: String get() = item.cover
        override val circular: Boolean get() = false
    }

    data class Album(val item: AlbumSummary) : SearchDetail {
        override val title: String get() = item.name
        override val subtitle: String
            get() = buildString {
                if (item.artist.isNotBlank()) append(item.artist).append(" · ")
                append("${item.trackCount} 首")
            }
        override val cover: String get() = item.cover
        override val circular: Boolean get() = false
    }

    data class Artist(val item: ArtistSummary) : SearchDetail {
        override val title: String get() = item.name
        override val subtitle: String
            get() = "${formatCount(item.followerCount)}人关注 · ${item.trackCount}首歌"
        override val cover: String get() = item.avatar
        override val circular: Boolean get() = true
    }
}

/** 搜索二级页：键盘回车搜索，点结果直接把结果列表设为播放队列并回首页播放页。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchScreen(
    client: SodaClient,
    scope: CoroutineScope,
    onBack: () -> Unit,
    onPlay: (queue: List<Track>, index: Int) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<SearchResults?>(null) }
    var tab by remember { mutableStateOf(SearchTab.All) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<SearchDetail?>(null) }
    var detailTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var detailLoading by remember { mutableStateOf(false) }
    var detailError by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(status) {
        if (status.isNotEmpty()) {
            delay(3_000)
            status = ""
        }
    }

    fun search() {
        if (query.isBlank() || busy) return
        busy = true
        status = "搜索中…"
        scope.launch {
            try {
                val found = withContext(Dispatchers.IO) { client.searchAll(query.trim()) }
                results = found
                status = if (found.tracks.isEmpty() && found.artists.isEmpty() &&
                    found.albums.isEmpty() && found.playlists.isEmpty()
                ) {
                    "没有搜索到结果（可能需要登录）"
                } else {
                    "歌曲 ${found.tracks.size} · 音乐人 ${found.artists.size} · " +
                        "专辑 ${found.albums.size} · 歌单 ${found.playlists.size}"
                }
            } catch (e: SodaException) {
                status = e.readable()
            } finally {
                busy = false
            }
        }
    }

    // 返回键先关详情页，再退出搜索。
    BackHandler {
        if (detail != null) detail = null else onBack()
    }

    // 打开音乐人/专辑/歌单详情时加载曲目。
    LaunchedEffect(detail) {
        val target = detail ?: return@LaunchedEffect
        detailTracks = emptyList()
        detailError = ""
        detailLoading = true
        try {
            detailTracks = withContext(Dispatchers.IO) {
                when (target) {
                    is SearchDetail.Playlist -> client.playlistTracks(target.item.id)
                    is SearchDetail.Album -> client.albumTracks(target.item.id)
                    is SearchDetail.Artist -> client.artistTracks(target.item.id)
                }
            }
        } catch (e: SodaException) {
            detailError = e.readable()
        } finally {
            detailLoading = false
        }
    }

    val opened = detail
    if (opened != null) {
        SearchDetailPage(
            detail = opened,
            tracks = detailTracks,
            loading = detailLoading,
            error = detailError,
            onBack = { detail = null },
            onPlayAll = { if (detailTracks.isNotEmpty()) onPlay(detailTracks, 0) },
            onPlay = { index -> onPlay(detailTracks, index) },
        )
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        ScreenHeader("搜索", onBack = onBack)

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("搜索歌曲、音乐人、专辑或歌单") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    search()
                    focusManager.clearFocus()
                },
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        PrimaryTabRow(selectedTabIndex = tab.ordinal) {
            SearchTab.entries.forEach { item ->
                Tab(
                    selected = tab == item,
                    onClick = { tab = item },
                    text = { Text(item.label, maxLines = 1, softWrap = false) },
                )
            }
        }

        if (busy) {
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        }
        if (status.isNotEmpty()) {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        val found = results
        if (found == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "输入关键词后回车搜索",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        val empty = found.tracks.isEmpty() && found.playlists.isEmpty() &&
            found.artists.isEmpty() && found.albums.isEmpty()
        // 「你可能想搜」里已经出现的音乐人不再在下面的音乐人分组重复。
        val topIds = found.topArtists.mapTo(HashSet()) { it.id }
        val restArtists = found.artists.filter { it.id !in topIds }

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                SearchTab.All -> {
                    if (empty) item { EmptyHint("没有找到结果") }
                    if (found.topArtists.isNotEmpty()) {
                        item { SectionHeader("你可能想搜") { tab = SearchTab.Artists } }
                        itemsIndexed(
                            found.topArtists.take(PREVIEW_COUNT),
                            key = { _, item -> "top:${item.id}" },
                        ) { _, item ->
                            ArtistRow(item, !busy) { detail = SearchDetail.Artist(item) }
                        }
                    }
                    if (found.tracks.isNotEmpty()) {
                        item { SectionHeader("歌曲") { tab = SearchTab.Tracks } }
                        itemsIndexed(
                            found.tracks.take(PREVIEW_COUNT),
                            key = { _, track -> "t:${track.id}" },
                        ) { index, track ->
                            TrackRow(track, !busy) { onPlay(found.tracks, index) }
                        }
                    }
                    if (found.playlists.isNotEmpty()) {
                        item { SectionHeader("歌单") { tab = SearchTab.Playlists } }
                        itemsIndexed(
                            found.playlists.take(PREVIEW_COUNT),
                            key = { _, item -> "p:${item.id}" },
                        ) { _, item ->
                            PlaylistRow(item, !busy) { detail = SearchDetail.Playlist(item) }
                        }
                    }
                    if (restArtists.isNotEmpty()) {
                        item { SectionHeader("歌手") { tab = SearchTab.Artists } }
                        itemsIndexed(
                            restArtists.take(PREVIEW_COUNT),
                            key = { _, item -> "a:${item.id}" },
                        ) { _, item ->
                            ArtistRow(item, !busy) { detail = SearchDetail.Artist(item) }
                        }
                    }
                    if (found.albums.isNotEmpty()) {
                        item { SectionHeader("专辑") { tab = SearchTab.Albums } }
                        itemsIndexed(
                            found.albums.take(PREVIEW_COUNT),
                            key = { _, item -> "b:${item.id}" },
                        ) { _, item ->
                            AlbumRow(item, !busy) { detail = SearchDetail.Album(item) }
                        }
                    }
                }

                SearchTab.Tracks -> {
                    if (found.tracks.isEmpty()) item { EmptyHint("没有找到相关歌曲") }
                    itemsIndexed(
                        found.tracks,
                        key = { _, track -> "t:${track.id}" },
                    ) { index, track ->
                        TrackRow(track, !busy) { onPlay(found.tracks, index) }
                    }
                }

                SearchTab.Playlists -> {
                    if (found.playlists.isEmpty()) item { EmptyHint("没有找到相关歌单") }
                    itemsIndexed(
                        found.playlists,
                        key = { _, item -> "p:${item.id}" },
                    ) { _, item ->
                        PlaylistRow(item, !busy) { detail = SearchDetail.Playlist(item) }
                    }
                }

                SearchTab.Artists -> {
                    if (found.artists.isEmpty()) item { EmptyHint("没有找到相关音乐人") }
                    itemsIndexed(
                        found.artists,
                        key = { _, item -> "a:${item.id}" },
                    ) { _, item ->
                        ArtistRow(item, !busy) { detail = SearchDetail.Artist(item) }
                    }
                }

                SearchTab.Albums -> {
                    if (found.albums.isEmpty()) item { EmptyHint("没有找到相关专辑") }
                    itemsIndexed(
                        found.albums,
                        key = { _, item -> "b:${item.id}" },
                    ) { _, item ->
                        AlbumRow(item, !busy) { detail = SearchDetail.Album(item) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, onViewAll: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onViewAll) { Text("查看全部") }
    }
}

@Composable
internal fun EmptyHint(text: String) {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun TrackRow(track: Track, enabled: Boolean, onClick: () -> Unit) {
    ListItem(
        leadingContent = {
            AsyncImage(
                model = track.cover,
                contentDescription = null,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop,
            )
        },
        headlineContent = {
            Text(track.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                buildString {
                    append(track.artist)
                    if (track.vip) append(" · VIP")
                    if (CachedAudio.isCached(track.id)) append(" · 已缓存")
                },
                maxLines = 1,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatDuration(track.duration * 1000),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LikeButton(track)
            }
        },
        modifier = Modifier.clickable(enabled = enabled) { onClick() },
    )
}

@Composable
internal fun PlaylistRow(item: PlaylistSummary, enabled: Boolean, onClick: () -> Unit) {
    ListItem(
        leadingContent = {
            // 服务端「我喜欢的音乐」用黑底爱心图标，替换默认封面。
            if (item.name.contains("我喜欢的音乐")) {
                LikedIcon()
            } else {
                AsyncImage(
                    model = item.cover,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Crop,
                )
            }
        },
        headlineContent = {
            Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = if (item.creator.isNotBlank()) {
            { Text(item.creator, maxLines = 1) }
        } else {
            null
        },
        modifier = Modifier.clickable(enabled = enabled) { onClick() },
    )
}

@Composable
private fun ArtistRow(item: ArtistSummary, enabled: Boolean, onClick: () -> Unit) {
    ListItem(
        leadingContent = {
            AsyncImage(
                model = item.avatar,
                contentDescription = null,
                modifier = Modifier.size(44.dp).clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        },
        headlineContent = {
            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                "${formatCount(item.followerCount)}人关注 · ${item.trackCount}首歌",
                maxLines = 1,
            )
        },
        modifier = Modifier.clickable(enabled = enabled) { onClick() },
    )
}

@Composable
private fun AlbumRow(item: AlbumSummary, enabled: Boolean, onClick: () -> Unit) {
    ListItem(
        leadingContent = {
            AsyncImage(
                model = item.cover,
                contentDescription = null,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop,
            )
        },
        headlineContent = {
            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                buildString {
                    if (item.artist.isNotBlank()) append(item.artist).append(" · ")
                    append("${item.trackCount} 首")
                },
                maxLines = 1,
            )
        },
        modifier = Modifier.clickable(enabled = enabled) { onClick() },
    )
}

/** 音乐人 / 专辑 / 歌单详情：头部信息 + 曲目列表。 */
@Composable
internal fun SearchDetailPage(
    detail: SearchDetail,
    tracks: List<Track>,
    loading: Boolean,
    error: String,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onPlay: (Int) -> Unit,
    applySystemBars: Boolean = true,
) {
    Column(
        Modifier
            .fillMaxSize()
            .then(
                if (applySystemBars) {
                    Modifier.statusBarsPadding().navigationBarsPadding()
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp),
    ) {
        ScreenHeader("详情", onBack = onBack)
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = detail.cover,
                contentDescription = null,
                modifier = Modifier
                    .size(96.dp)
                    .clip(if (detail.circular) CircleShape else RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop,
            )
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(
                    detail.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    detail.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Button(
            onClick = onPlayAll,
            enabled = tracks.isNotEmpty() && !loading,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            Text("播放全部")
        }
        when {
            loading -> Box(
                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            error.isNotEmpty() -> Text(
                error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 16.dp),
            )
            tracks.isEmpty() -> EmptyHint("没有可播放的曲目")
            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                    TrackRow(track, enabled = true) { onPlay(index) }
                }
            }
        }
    }
}
