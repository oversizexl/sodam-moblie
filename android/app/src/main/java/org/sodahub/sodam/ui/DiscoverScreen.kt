package org.sodahub.sodam.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.sodahub.sodam.PlaybackHub
import org.sodahub.sodam.R
import uniffi.sodam_ffi.PlaylistGroup
import uniffi.sodam_ffi.PlaylistSummary
import uniffi.sodam_ffi.SodaClient
import uniffi.sodam_ffi.SodaException
import uniffi.sodam_ffi.Track

internal sealed interface DiscoverPage {
    data object Root : DiscoverPage
    data object LocalDownloads : DiscoverPage
    data class Tracks(
        val playlist: PlaylistSummary,
        val tracks: List<Track>,
    ) : DiscoverPage
}

/**
 * 发现页状态提到 Tab 之外：进详情页再返回时仍停在原来的歌曲列表，
 * 不会掉回发现根页。
 */
@Stable
internal class DiscoverState {
    var page by mutableStateOf<DiscoverPage>(DiscoverPage.Root)
    var recommended by mutableStateOf<List<PlaylistGroup>>(emptyList())
    var playlists by mutableStateOf<List<PlaylistSummary>>(emptyList())
    var recommendedLoaded by mutableStateOf(false)
    var playlistsLoaded by mutableStateOf(false)
    var status by mutableStateOf("")
    var busy by mutableStateOf(false)
    var recommendedBusy by mutableStateOf(false)
    var playlistsBusy by mutableStateOf(false)

    val anyBusy: Boolean get() = busy || recommendedBusy || playlistsBusy

    /** 进过的歌单曲目缓存：第二次进入不再请求接口。 */
    val tracksCache = mutableMapOf<String, List<Track>>()
}

/** 发现：沉浸焦点收藏卡 + 两行整体横向推荐网格 + 我的歌单岛屿分组。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiscoverScreen(
    state: DiscoverState,
    client: SodaClient,
    scope: CoroutineScope,
    loading: Boolean,
    isPlaying: Boolean,
    currentTrackId: String?,
    sessionReady: Boolean = true,
    bottomInset: Dp = 96.dp,
    onPlay: (queue: List<Track>, index: Int, recommendRequest: Pair<Long, String>?) -> Unit,
    needsLogin: Boolean,
    onOpenLogin: () -> Unit,
) {
    if (needsLogin) {
        Box(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(bottom = bottomInset),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 32.dp),
            ) {
                Text(
                    text = "探索专属音乐",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                )
                Text(
                    text = "登录后即可同步我的歌单、我喜欢的音乐以及个性推荐",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Button(
                    onClick = onOpenLogin,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.padding(top = 20.dp),
                ) {
                    Text("立即登录", modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
                }
            }
        }
        return
    }

    fun loadRecommended() {
        if (!sessionReady || state.recommendedBusy) return
        state.recommendedBusy = true
        state.status = "正在刷新推荐歌单…"
        scope.launch {
            try {
                state.recommended = withContext(Dispatchers.IO) { client.discoverPlaylists() }
                val total = state.recommended.sumOf { it.playlists.size }
                state.status = if (total == 0) "暂无推荐歌单" else "已刷新 $total 个推荐歌单"
                state.recommendedLoaded = true
            } catch (e: SodaException) {
                state.status = e.readable()
            } finally {
                state.recommendedBusy = false
            }
        }
    }

    fun loadPlaylists() {
        if (!sessionReady || state.playlistsBusy) return
        state.playlistsBusy = true
        scope.launch {
            try {
                val list = withContext(Dispatchers.IO) { client.myPlaylists() }
                state.playlists = list
                if (list.isNotEmpty()) {
                    state.playlistsLoaded = true
                }
                // 服务端歌单摘要里的 trackCount 可能有缓存延迟，同步校准真实已喜欢歌曲总数。
                if (!LikedTracks.loaded) {
                    launch { LikedTracks.reload() }
                }
            } catch (e: SodaException) {
                state.status = e.readable()
            } finally {
                state.playlistsBusy = false
            }
        }
    }

    fun openPlaylist(playlist: PlaylistSummary) {
        val isLikedPlaylist = playlist.name.contains("我喜欢的音乐")
        if (!isLikedPlaylist) {
            state.tracksCache[playlist.id]?.let { cached ->
                state.page = DiscoverPage.Tracks(playlist = playlist, tracks = cached)
                return
            }
        }
        if (state.busy) return
        state.busy = true
        state.status = "加载《${playlist.name}》…"
        scope.launch {
            try {
                val list = withContext(Dispatchers.IO) { client.playlistTracks(playlist.id) }
                    .distinctBy { it.id }
                if (list.isNotEmpty()) client.prefetch(list.take(3).map { it.id })
                if (isLikedPlaylist) {
                    LikedTracks.setFromServerTracks(list)
                } else {
                    state.tracksCache[playlist.id] = list
                }
                state.page = DiscoverPage.Tracks(playlist = playlist, tracks = list)
                state.status = ""
            } catch (e: SodaException) {
                state.status = e.readable()
            } finally {
                state.busy = false
            }
        }
    }

    fun quickPlayPlaylist(playlist: PlaylistSummary) {
        val isLikedPlaylist = playlist.name.contains("我喜欢的音乐")
        if (!isLikedPlaylist) {
            state.tracksCache[playlist.id]?.let { cached ->
                if (cached.isNotEmpty()) {
                    onPlay(cached, 0, HOME_RECOMMEND)
                    return
                }
            }
        }
        if (state.busy) return
        state.busy = true
        state.status = "正在准备播放《${playlist.name}》…"
        scope.launch {
            try {
                val list = withContext(Dispatchers.IO) { client.playlistTracks(playlist.id) }
                    .distinctBy { it.id }
                if (isLikedPlaylist) {
                    LikedTracks.setFromServerTracks(list)
                } else {
                    state.tracksCache[playlist.id] = list
                }
                if (list.isNotEmpty()) {
                    onPlay(list, 0, HOME_RECOMMEND)
                    state.status = ""
                } else {
                    state.status = "歌单暂无可播放歌曲"
                }
            } catch (e: SodaException) {
                state.status = e.readable()
            } finally {
                state.busy = false
            }
        }
    }

    LaunchedEffect(state.status) {
        if (state.status.isNotEmpty()) {
            delay(3_000)
            state.status = ""
        }
    }

    // 只接收一次性的下载提示事件，切换底部导航时不再重复出现。
    LaunchedEffect(Unit) {
        TrackDownloader.events.collect { msg ->
            state.status = msg
        }
    }

    val refreshRotation = remember { Animatable(0f) }
    LaunchedEffect(state.anyBusy) {
        if (state.anyBusy) {
            while (isActive) {
                refreshRotation.snapTo(0f)
                refreshRotation.animateTo(360f, tween(700, easing = LinearEasing))
            }
        } else {
            refreshRotation.snapTo(0f)
        }
    }

    // 进发现页自动核验本地已下载文件真实存在性（如用户手动删过文件，数量与列表实时更新）
    LaunchedEffect(Unit) {
        TrackDownloader.reconcileLocalFiles()
    }
    LaunchedEffect(state.page) {
        if (state.page == DiscoverPage.LocalDownloads) {
            TrackDownloader.reconcileLocalFiles()
        }
    }

    // 登录态就绪后独立并发加载“我的歌单”与“推荐歌单”，互不锁死。
    LaunchedEffect(sessionReady, state.playlistsLoaded) {
        if (sessionReady && !state.playlistsLoaded && !state.playlistsBusy) {
            loadPlaylists()
        }
    }

    LaunchedEffect(sessionReady, state.recommendedLoaded) {
        if (sessionReady && !state.recommendedLoaded && !state.recommendedBusy) {
            loadRecommended()
        }
    }

    fun refreshLikedPage() {
        val page = state.page as? DiscoverPage.Tracks ?: return
        if (!page.playlist.name.contains("我喜欢的音乐")) return
        if (state.busy) return
        state.busy = true
        scope.launch {
            try {
                val list = withContext(Dispatchers.IO) { client.playlistTracks(page.playlist.id) }
                    .distinctBy { it.id }
                LikedTracks.setFromServerTracks(list)
                state.page = DiscoverPage.Tracks(playlist = page.playlist, tracks = list)
            } catch (e: SodaException) {
                state.status = e.readable()
            } finally {
                state.busy = false
            }
        }
    }

    LaunchedEffect(LikedTracks.likedIds) {
        val page = state.page as? DiscoverPage.Tracks ?: return@LaunchedEffect
        if (!page.playlist.name.contains("我喜欢的音乐")) return@LaunchedEffect
        val shownIds = page.tracks.mapTo(HashSet()) { it.id }
        if (LikedTracks.likedIds.any { it !in shownIds }) {
            refreshLikedPage()
        }
    }

    val hubQueue by PlaybackHub.queueFlow.collectAsState()
    LaunchedEffect(hubQueue) {
        val current = state.page
        if (current is DiscoverPage.Tracks &&
            !current.playlist.name.contains("我喜欢的音乐") &&
            current.tracks.isNotEmpty() &&
            hubQueue.size > current.tracks.size &&
            hubQueue.take(current.tracks.size).map { it.id } == current.tracks.map { it.id }
        ) {
            state.page = current.copy(tracks = hubQueue.distinctBy { it.id })
        }
    }

    when (val current = state.page) {
        is DiscoverPage.Tracks -> {
            BackHandler { state.page = DiscoverPage.Root }
            val shown = if (current.playlist.name.contains("我喜欢的音乐")) {
                current.tracks.filter { it.id in LikedTracks.likedIds }
            } else {
                current.tracks
            }
            val exactSubtitle = buildString {
                if (current.playlist.creator.isNotBlank()) append(current.playlist.creator).append(" · ")
                append("${shown.size} 首")
            }
            Box(Modifier.fillMaxSize().statusBarsPadding()) {
                SearchDetailPage(
                    detail = SearchDetail.Playlist(current.playlist),
                    tracks = shown,
                    loading = loading,
                    error = "",
                    currentTrackId = currentTrackId,
                    isPlaying = isPlaying,
                    subtitleOverride = exactSubtitle,
                    onBack = { state.page = DiscoverPage.Root },
                    onPlayAll = {
                        if (shown.isNotEmpty()) onPlay(shown, 0, HOME_RECOMMEND)
                    },
                    onPlay = { index -> onPlay(shown, index, HOME_RECOMMEND) },
                    applySystemBars = false,
                    bottomInset = bottomInset,
                )
                FloatingStatusPill(
                    message = state.status,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = bottomInset + 8.dp),
                )
            }
        }

        DiscoverPage.LocalDownloads -> {
            BackHandler { state.page = DiscoverPage.Root }
            val localTracks = TrackDownloader.downloadedTracks
            Box(Modifier.fillMaxSize().statusBarsPadding()) {
                SearchDetailPage(
                    detail = SearchDetail.Playlist(
                        PlaylistSummary(
                            id = "local-downloads",
                            name = "本地已下载",
                            creator = "离线曲库",
                            cover = localTracks.firstOrNull()?.cover.orEmpty(),
                            trackCount = localTracks.size.toLong(),
                        ),
                    ),
                    tracks = localTracks,
                    loading = false,
                    error = "",
                    currentTrackId = currentTrackId,
                    isPlaying = isPlaying,
                    subtitleOverride = "已导出 ${localTracks.size} 首到本地",
                    onBack = { state.page = DiscoverPage.Root },
                    onPlayAll = {
                        if (localTracks.isNotEmpty()) onPlay(localTracks, 0, HOME_RECOMMEND)
                    },
                    onPlay = { index -> onPlay(localTracks, index, HOME_RECOMMEND) },
                    showDownloadAll = false,
                    applySystemBars = false,
                    bottomInset = bottomInset,
                )
                FloatingStatusPill(
                    message = state.status,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = bottomInset + 8.dp),
                )
            }
        }

        DiscoverPage.Root -> {
            val likedPlaylist = remember(state.playlists) {
                state.playlists.firstOrNull { it.name.contains("我喜欢的音乐") }
            }
            val otherPlaylists = remember(state.playlists, likedPlaylist) {
                if (likedPlaylist == null) state.playlists else state.playlists.filter { it.id != likedPlaylist.id }
            }
            val likedCount = if (LikedTracks.loaded) {
                LikedTracks.count
            } else {
                likedPlaylist?.trackCount?.toInt() ?: 0
            }

            Box(Modifier.fillMaxSize().statusBarsPadding()) {
                Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    ScreenHeader(
                        "发现",
                        actions = {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                                    .bounceClick(enabled = !state.anyBusy, scaleDown = 0.9f) {
                                        if (!state.anyBusy) {
                                            loadRecommended()
                                            loadPlaylists()
                                            scope.launch {
                                                LikedTracks.reload()
                                                TrackDownloader.reconcileLocalFiles()
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = "刷新推荐歌单",
                                    modifier = Modifier
                                        .size(20.dp)
                                        .rotate(refreshRotation.value),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        },
                    )

                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(top = 6.dp, bottom = bottomInset),
                    ) {
                        // 1. 「我喜欢的音乐」专属焦点大卡片 + 「本地已下载」入口
                        if (likedPlaylist != null) {
                            item(key = "liked-hero") {
                                LikedHeroCard(
                                    playlist = likedPlaylist,
                                    realTrackCount = likedCount,
                                    enabled = !state.busy,
                                    onOpen = { openPlaylist(likedPlaylist) },
                                    onQuickPlay = { quickPlayPlaylist(likedPlaylist) },
                                )
                                Spacer(Modifier.height(12.dp))
                            }
                        }

                        item(key = "local-downloads-entry") {
                            LocalDownloadsEntryCard(
                                count = TrackDownloader.downloadedTracks.size,
                                onClick = { state.page = DiscoverPage.LocalDownloads },
                            )
                            Spacer(Modifier.height(20.dp))
                        }

                        // 2. 推荐歌单两行整体横向滑动网格（GridCells.Fixed(2)）
                        if (state.recommendedLoaded) {
                            state.recommended.forEachIndexed { idx, group ->
                                val title = group.title.takeIf { it.isNotBlank() && it != "推荐歌单" }
                                    ?: "精选推荐"
                                item(key = "rg-title-$idx-$title") {
                                    SectionLabel(
                                        title = title,
                                        subtitle = "${group.playlists.size} 张歌单 · 左滑查看更多",
                                    )
                                }
                                item(key = "rg-grid-$idx-$title") {
                                    val rowCount = if (group.playlists.size <= 1) 1 else 2
                                    val gridHeight = if (rowCount == 2) 368.dp else 182.dp
                                    LazyHorizontalGrid(
                                        rows = GridCells.Fixed(rowCount),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(14.dp),
                                        contentPadding = PaddingValues(vertical = 6.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(gridHeight),
                                    ) {
                                        items(group.playlists, key = { "rp-${it.id}" }) { playlist ->
                                            RecommendedCarouselCard(
                                                playlist = playlist,
                                                enabled = !state.busy,
                                                onClick = { openPlaylist(playlist) },
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(16.dp))
                                }
                            }
                        }

                        // 3. 我的歌单（其余自建/收藏歌单收拢进岛屿卡片）
                        if (state.playlistsLoaded && otherPlaylists.isNotEmpty()) {
                            item(key = "my-playlists-header") {
                                SectionLabel(
                                    title = "我的歌单",
                                    subtitle = "${otherPlaylists.size} 个",
                                )
                            }
                            item(key = "my-playlists-island") {
                                IslandCard(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                                    otherPlaylists.forEachIndexed { index, playlist ->
                                        DiscoverPlaylistTile(
                                            playlist = playlist,
                                            enabled = !state.busy,
                                            onClick = { openPlaylist(playlist) },
                                        )
                                        if (index < otherPlaylists.lastIndex) {
                                            HorizontalDivider(
                                                modifier = Modifier.padding(start = 66.dp),
                                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                FloatingStatusPill(
                    message = state.status,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = bottomInset + 8.dp),
                )
            }
        }
    }
}

@Composable
private fun LikedHeroCard(
    playlist: PlaylistSummary,
    realTrackCount: Int,
    enabled: Boolean,
    onOpen: () -> Unit,
    onQuickPlay: () -> Unit,
) {
    val heartColor = Color(0xFFE53935)
    val cardGradient = Brush.linearGradient(
        colors = listOf(
            heartColor.copy(alpha = 0.22f),
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        ),
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(24.dp),
                spotColor = heartColor.copy(alpha = 0.25f),
            )
            .clip(RoundedCornerShape(24.dp))
            .background(cardGradient)
            .border(
                width = 1.dp,
                color = heartColor.copy(alpha = 0.3f),
                shape = RoundedCornerShape(24.dp),
            )
            .bounceClick(enabled = enabled, scaleDown = 0.98f, onClick = onOpen)
            .padding(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            LikedIcon(size = 60.dp)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp),
            ) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (realTrackCount > 0) "$realTrackCount 首红心单曲" else "点击查看与同步收藏曲目",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .shadow(4.dp, CircleShape, spotColor = heartColor.copy(alpha = 0.45f))
                    .clip(CircleShape)
                    .background(heartColor)
                    .bounceClick(enabled = enabled, scaleDown = 0.9f, onClick = onQuickPlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_play),
                    contentDescription = "播放我喜欢的音乐",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun RecommendedCarouselCard(
    playlist: PlaylistSummary,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(124.dp)
            .bounceClick(enabled = enabled, scaleDown = 0.95f, onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(124.dp)
                .shadow(
                    elevation = 6.dp,
                    shape = RoundedCornerShape(18.dp),
                    spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                )
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = playlist.cover,
                contentDescription = playlist.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (playlist.trackCount > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(7.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.56f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "${playlist.trackCount}首",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = Color.White,
                    )
                }
            }
        }
        Text(
            text = playlist.name,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 12.5.sp,
                lineHeight = 16.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
    }
}

@Composable
private fun DiscoverPlaylistTile(
    playlist: PlaylistSummary,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .bounceClick(enabled = enabled, scaleDown = 0.98f, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = playlist.cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = buildString {
                if (playlist.trackCount > 0) append("${playlist.trackCount} 首")
                if (playlist.creator.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(playlist.creator)
                }
            }
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(18.dp),
        )
    }
}

/** 「本地已下载」离线曲库入口卡片。 */
@Composable
private fun LocalDownloadsEntryCard(
    count: Int,
    onClick: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    IslandCard(
        cornerRadius = 20.dp,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_download),
                    contentDescription = null,
                    tint = primary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    text = "本地已下载",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    text = if (count > 0) "已导出 $count 首可离线播放歌曲" else "下载后的歌曲将保存在这里",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 「我喜欢的音乐」图标：深墨红圆角底 + 发光实心爱心。 */
@Composable
internal fun LikedIcon(size: Dp = 48.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFF2B1419), Color(0xFF1A161A)),
                ),
            )
            .border(0.8.dp, Color(0xFFE53935).copy(alpha = 0.4f), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Favorite,
            contentDescription = null,
            tint = Color(0xFFE53935),
            modifier = Modifier.size(size * 0.48f),
        )
    }
}
