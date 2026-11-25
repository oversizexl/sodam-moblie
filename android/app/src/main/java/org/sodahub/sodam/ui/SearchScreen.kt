package org.sodahub.sodam.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.sodahub.sodam.R
import java.io.File
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

/** 搜索二级页：胶囊搜索框 + 搜索历史胶囊 + 药丸 Tab + 岛屿结果卡片。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SearchScreen(
    client: SodaClient,
    scope: CoroutineScope,
    onBack: () -> Unit,
    onPlay: (queue: List<Track>, index: Int) -> Unit,
) {
    val context = LocalContext.current
    val historyFile = remember { File(context.filesDir, "search_history.json").absolutePath }
    var searchHistory by remember { mutableStateOf(readSearchHistory(historyFile)) }
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

    LaunchedEffect(Unit) {
        TrackDownloader.events.collect { msg ->
            status = msg
        }
    }

    fun search(keywordOverride: String? = null) {
        val targetQuery = (keywordOverride ?: query).trim()
        if (targetQuery.isEmpty() || busy) return
        if (keywordOverride != null) query = targetQuery
        val updatedHistory = (listOf(targetQuery) + searchHistory.filter { !it.equals(targetQuery, ignoreCase = true) }).take(12)
        searchHistory = updatedHistory
        scope.launch(Dispatchers.IO) { writeSearchHistory(historyFile, updatedHistory) }

        busy = true
        status = "搜索中…"
        scope.launch {
            try {
                val found = withContext(Dispatchers.IO) { client.searchAll(targetQuery) }
                results = found
                status = if (found.tracks.isEmpty() && found.artists.isEmpty() &&
                    found.albums.isEmpty() && found.playlists.isEmpty()
                ) {
                    "没有搜索到结果（可能需要登录）"
                } else {
                    "找到歌曲 ${found.tracks.size} · 歌手 ${found.artists.size} · 专辑 ${found.albums.size}"
                }
            } catch (e: SodaException) {
                status = e.readable()
            } finally {
                busy = false
            }
        }
    }

    BackHandler {
        if (detail != null) detail = null else onBack()
    }

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

    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            ScreenHeader("搜索", onBack = onBack)

            // 胶囊搜索框
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .border(
                        0.8.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                        RoundedCornerShape(22.dp),
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            text = "搜索歌曲、音乐人、专辑或歌单",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                search()
                                focusManager.clearFocus()
                            },
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            query = ""
                            results = null
                        },
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "清除",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            // 药丸分类切换栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SearchTab.entries.forEach { item ->
                    SelectablePill(
                        label = item.label,
                        selected = tab == item,
                        onClick = { tab = item },
                    )
                }
            }

            if (busy) {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }

            val found = results
            if (found == null) {
                Column(Modifier.weight(1f).fillMaxWidth()) {
                    if (searchHistory.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "最近搜索",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.primary,
                            )
                            TextButton(
                                onClick = {
                                    searchHistory = emptyList()
                                    scope.launch(Dispatchers.IO) { writeSearchHistory(historyFile, emptyList()) }
                                },
                            ) {
                                Text("清空", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            searchHistory.forEach { keyword ->
                                SelectablePill(
                                    label = keyword,
                                    selected = false,
                                    onClick = {
                                        focusManager.clearFocus()
                                        search(keyword)
                                    },
                                )
                            }
                        }
                    } else {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Filled.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                    modifier = Modifier.size(48.dp),
                                )
                                Text(
                                    text = "输入关键词后点击键盘搜索",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                            }
                        }
                    }
                }
            } else {
                val empty = found.tracks.isEmpty() && found.playlists.isEmpty() &&
                    found.artists.isEmpty() && found.albums.isEmpty()
                val topIds = found.topArtists.mapTo(HashSet()) { it.id }
                val restArtists = found.artists.filter { it.id !in topIds }

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
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

        FloatingStatusPill(
            message = status,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
        )
    }
}

@Composable
private fun SectionHeader(title: String, onViewAll: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
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
internal fun TrackRow(
    track: Track,
    enabled: Boolean,
    index: Int? = null,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    onClick: () -> Unit,
) {
    val rowBg = if (isCurrent) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
    } else {
        androidx.compose.ui.graphics.Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(rowBg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) {
            Box(
                modifier = Modifier.width(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (isCurrent) {
                    PlayingBars(active = isPlaying)
                } else {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
        }
        AsyncImage(
            model = track.cover,
            contentDescription = null,
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = track.name,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                    fontSize = 15.sp,
                ),
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(track.artist)
                    if (track.vip) append(" · VIP")
                    if (CachedAudio.isCached(track.id)) append(" · 已缓存")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatDuration(track.duration * 1000),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            var menuOpen by remember { mutableStateOf(false) }
            val liked = LikedTracks.isLiked(track.id)
            val downloading = TrackDownloader.isDownloading(track.id)
            val downloaded = TrackDownloader.isDownloaded(track.id)
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.size(36.dp),
                ) {
                    if (downloading) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    } else {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "更多操作",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    shape = RoundedCornerShape(16.dp),
                ) {
                    DropdownMenuItem(
                        text = { Text(if (liked) "取消喜欢" else "喜欢歌曲") },
                        leadingIcon = {
                            Icon(
                                imageVector = if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = null,
                                tint = if (liked) androidx.compose.ui.graphics.Color(0xFFE53935) else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        onClick = {
                            menuOpen = false
                            LikedTracks.toggle(track)
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                when {
                                    downloading -> "正在下载…"
                                    downloaded -> "重新下载（当前音质）"
                                    else -> "下载歌曲"
                                },
                            )
                        },
                        leadingIcon = {
                            if (downloaded) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            } else {
                                Icon(
                                    painter = painterResource(R.drawable.ic_download),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        },
                        enabled = !downloading,
                        onClick = {
                            menuOpen = false
                            if (!downloading) {
                                TrackDownloader.download(track, force = downloaded)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun PlaylistRow(item: PlaylistSummary, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.name.contains("我喜欢的音乐")) {
            LikedIcon()
        } else {
            AsyncImage(
                model = item.cover,
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.creator.isNotBlank()) {
                Text(
                    text = item.creator,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun ArtistRow(item: ArtistSummary, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = item.avatar,
            contentDescription = null,
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${formatCount(item.followerCount)}人关注 · ${item.trackCount}首歌",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun AlbumRow(item: AlbumSummary, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = item.cover,
            contentDescription = null,
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    if (item.artist.isNotBlank()) append(item.artist).append(" · ")
                    append("${item.trackCount} 首")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 音乐人 / 专辑 / 歌单详情：沉浸式头图卡片 + 流光播放胶囊 + 曲目列表。 */
@Composable
internal fun SearchDetailPage(
    detail: SearchDetail,
    tracks: List<Track>,
    loading: Boolean,
    error: String,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onPlay: (Int) -> Unit,
    currentTrackId: String? = null,
    isPlaying: Boolean = false,
    subtitleOverride: String? = null,
    showDownloadAll: Boolean = true,
    applySystemBars: Boolean = true,
    bottomInset: androidx.compose.ui.unit.Dp = 24.dp,
) {
    val primary = MaterialTheme.colorScheme.primary
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

        // 沉浸式头部卡片
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            primary.copy(alpha = 0.14f),
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        ),
                    ),
                )
                .border(
                    0.8.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    RoundedCornerShape(22.dp),
                )
                .padding(14.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val coverShape = if (detail.circular) CircleShape else RoundedCornerShape(16.dp)
                    AsyncImage(
                        model = detail.cover,
                        contentDescription = null,
                        modifier = Modifier
                            .size(92.dp)
                            .shadow(8.dp, coverShape, spotColor = primary.copy(alpha = 0.35f))
                            .clip(coverShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Crop,
                    )
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(
                            text = detail.title,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val shownSubtitle = subtitleOverride ?: detail.subtitle
                        if (shownSubtitle.isNotBlank()) {
                            Text(
                                text = shownSubtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 播放全部 + 全部下载 双操作胶囊栏
                val canPlay = tracks.isNotEmpty() && !loading
                val batchBusy = TrackDownloader.batchBusy
                val allDownloaded = tracks.isNotEmpty() && tracks.all { TrackDownloader.isDownloaded(it.id) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .shadow(
                                elevation = if (canPlay) 4.dp else 0.dp,
                                shape = RoundedCornerShape(16.dp),
                                spotColor = primary.copy(alpha = 0.45f),
                            )
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (canPlay) primary else MaterialTheme.colorScheme.surfaceVariant,
                            )
                            .bounceClick(enabled = canPlay, scaleDown = 0.97f, onClick = onPlayAll)
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(R.drawable.ic_play),
                                contentDescription = null,
                                tint = if (canPlay) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (tracks.isNotEmpty()) "播放全部 (${tracks.size}首)" else "播放全部",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = if (canPlay) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (showDownloadAll && tracks.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
                                .border(
                                    0.8.dp,
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                                    RoundedCornerShape(16.dp),
                                )
                                .bounceClick(
                                    enabled = canPlay && !batchBusy && !allDownloaded,
                                    scaleDown = 0.95f,
                                ) {
                                    TrackDownloader.downloadAll(tracks)
                                }
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (batchBusy) {
                                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                                } else if (allDownloaded) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = primary,
                                        modifier = Modifier.size(17.dp),
                                    )
                                } else {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_download),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(17.dp),
                                    )
                                }
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    text = when {
                                        batchBusy -> "下载中"
                                        allDownloaded -> "已下载"
                                        else -> "下载"
                                    },
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                                    color = if (allDownloaded) primary else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        when {
            loading -> Box(
                Modifier.fillMaxWidth().padding(vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.5.dp)
            }
            error.isNotEmpty() -> Text(
                error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 16.dp),
            )
            tracks.isEmpty() -> EmptyHint("没有可播放的曲目")
            else -> LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = bottomInset),
            ) {
                itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                    TrackRow(
                        track = track,
                        enabled = true,
                        index = index,
                        isCurrent = track.id == currentTrackId,
                        isPlaying = isPlaying,
                        onClick = { onPlay(index) },
                    )
                }
            }
        }
    }
}

/**
 * 首页点击歌名（跳所属专辑）或点击歌手名（跳歌手页）的直达详情页：
 * 自动检索并匹配对应专辑/歌手并加载全部曲目。
 */
@Composable
internal fun TrackLinkDetailScreen(
    client: SodaClient,
    track: Track,
    lookupAlbum: Boolean,
    onBack: () -> Unit,
    onPlay: (queue: List<Track>, index: Int) -> Unit,
) {
    BackHandler(onBack = onBack)

    var detail by remember(track.id, lookupAlbum) {
        mutableStateOf<SearchDetail>(
            if (lookupAlbum) {
                SearchDetail.Album(
                    AlbumSummary(
                        id = "",
                        name = track.album.ifBlank { track.name },
                        artist = track.artist,
                        cover = track.cover,
                        trackCount = 0,
                    ),
                )
            } else {
                val primaryArtist = track.artist.split('/', '、', ',', '&')
                    .firstOrNull()?.trim().orEmpty().ifBlank { track.artist }
                SearchDetail.Artist(
                    ArtistSummary(
                        id = "",
                        name = primaryArtist,
                        avatar = track.cover,
                        followerCount = 0,
                        trackCount = 0,
                    ),
                )
            },
        )
    }
    var tracks by remember(track.id, lookupAlbum) { mutableStateOf<List<Track>>(emptyList()) }
    var loading by remember(track.id, lookupAlbum) { mutableStateOf(true) }
    var error by remember(track.id, lookupAlbum) { mutableStateOf("") }
    var toast by remember { mutableStateOf("") }

    LaunchedEffect(toast) {
        if (toast.isNotEmpty()) {
            delay(3_000)
            toast = ""
        }
    }
    LaunchedEffect(Unit) {
        TrackDownloader.events.collect { toast = it }
    }

    LaunchedEffect(track.id, lookupAlbum) {
        loading = true
        error = ""
        try {
            if (lookupAlbum) {
                val keyword = track.album.ifBlank { "${track.name} ${track.artist}".trim() }
                val primaryArtist = track.artist.split('/', '、', ',', '&')
                    .firstOrNull()?.trim().orEmpty()
                val found = withContext(Dispatchers.IO) { client.searchAll(keyword) }
                val matched = found.albums.firstOrNull { album ->
                    album.name.equals(track.album, ignoreCase = true) &&
                        (primaryArtist.isBlank() || album.artist.contains(primaryArtist, ignoreCase = true))
                } ?: found.albums.firstOrNull { album ->
                    track.album.isNotBlank() && album.name.contains(track.album, ignoreCase = true)
                } ?: found.albums.firstOrNull()

                if (matched != null) {
                    detail = SearchDetail.Album(matched)
                    val list = withContext(Dispatchers.IO) { client.albumTracks(matched.id) }
                    tracks = list
                } else if (found.tracks.isNotEmpty()) {
                    // 未匹配到专辑对象时兜底展示关联歌曲
                    tracks = found.tracks
                } else {
                    error = "未能找到包含《${track.name}》的专辑信息"
                }
            } else {
                val primaryArtist = track.artist.split('/', '、', ',', '&')
                    .firstOrNull()?.trim().orEmpty().ifBlank { track.artist }
                val found = withContext(Dispatchers.IO) { client.searchAll(primaryArtist) }
                val candidates = found.topArtists + found.artists
                val matched = candidates.firstOrNull {
                    it.name.equals(primaryArtist, ignoreCase = true)
                } ?: candidates.firstOrNull()

                if (matched != null) {
                    detail = SearchDetail.Artist(matched)
                    val list = withContext(Dispatchers.IO) { client.artistTracks(matched.id) }
                    tracks = list
                } else {
                    error = "未能找到歌手「$primaryArtist」的主页信息"
                }
            }
        } catch (e: SodaException) {
            error = e.readable()
        } finally {
            loading = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        SearchDetailPage(
            detail = detail,
            tracks = tracks,
            loading = loading,
            error = error,
            onBack = onBack,
            onPlayAll = { if (tracks.isNotEmpty()) onPlay(tracks, 0) },
            onPlay = { index -> onPlay(tracks, index) },
        )
        FloatingStatusPill(
            message = toast,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp),
        )
    }
}

private fun readSearchHistory(path: String): List<String> = runCatching {
    val text = File(path).takeIf { it.isFile }?.readText().orEmpty()
    if (text.isBlank()) return emptyList()
    val array = JSONArray(text)
    buildList {
        for (i in 0 until array.length()) {
            val word = array.optString(i).trim()
            if (word.isNotEmpty()) add(word)
        }
    }
}.getOrDefault(emptyList())

private fun writeSearchHistory(path: String, items: List<String>) {
    runCatching {
        val array = JSONArray()
        items.forEach { array.put(it) }
        File(path).apply {
            parentFile?.mkdirs()
            writeText(array.toString())
        }
    }
}
