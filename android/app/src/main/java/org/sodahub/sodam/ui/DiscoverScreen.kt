package org.sodahub.sodam.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.sodahub.sodam.PlaybackHub
import uniffi.sodam_ffi.PlaylistGroup
import uniffi.sodam_ffi.PlaylistSummary
import uniffi.sodam_ffi.SodaClient
import uniffi.sodam_ffi.SodaException
import uniffi.sodam_ffi.Track

internal sealed interface DiscoverPage {
    data object Root : DiscoverPage
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

    /** 进过的歌单曲目缓存：第二次进入不再请求接口。 */
    val tracksCache = mutableMapOf<String, List<Track>>()
}

/** 发现：听歌模式与我的歌单。点进去是带箭头返回的二级列表页。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiscoverScreen(
    state: DiscoverState,
    client: SodaClient,
    scope: CoroutineScope,
    loading: Boolean,
    isPlaying: Boolean,
    currentTrackId: String?,
    onPlay: (queue: List<Track>, index: Int, recommendRequest: Pair<Long, String>?) -> Unit,
    needsLogin: Boolean,
    onOpenLogin: () -> Unit,
) {
    if (needsLogin) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("未登录", style = MaterialTheme.typography.titleMedium)
                Text(
                    "登录后才能使用搜索、歌单与播放",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Button(onClick = onOpenLogin, modifier = Modifier.padding(top = 16.dp)) {
                    Text("去登录")
                }
            }
        }
        return
    }

    fun loadRecommended() {
        if (state.busy) return
        state.busy = true
        state.status = "加载推荐歌单…"
        scope.launch {
            try {
                state.recommended = withContext(Dispatchers.IO) { client.discoverPlaylists() }
                val total = state.recommended.sumOf { it.playlists.size }
                state.status = if (total == 0) "没有推荐歌单（可能需要登录）" else "推荐歌单 $total 个"
            } catch (e: SodaException) {
                state.status = e.readable()
            } finally {
                // 失败也标记为“已尝试”，避免 busy 翻转导致自动重试死循环闪烁。
                state.recommendedLoaded = true
                state.busy = false
            }
        }
    }

    fun loadPlaylists() {
        if (state.busy) return
        state.busy = true
        state.status = "加载歌单…"
        scope.launch {
            try {
                state.playlists = withContext(Dispatchers.IO) { client.myPlaylists() }
                state.status = if (state.playlists.isEmpty()) "没有歌单（可能需要登录）" else "歌单 ${state.playlists.size} 个"
            } catch (e: SodaException) {
                state.status = e.readable()
            } finally {
                // 失败也标记为“已尝试”，避免 busy 翻转导致自动重试死循环闪烁。
                state.playlistsLoaded = true
                state.busy = false
            }
        }
    }

    fun openPlaylist(playlist: PlaylistSummary) {
        val isLikedPlaylist = playlist.name.contains("我喜欢的音乐")
        // 「我喜欢的音乐」每次都拉接口：喜欢/取消后列表要立刻反映最新内容。
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
                    // 用服务端结果校准爱心状态，保证列表里都是实心爱心。
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

    LaunchedEffect(state.status) {
        if (state.status.isNotEmpty()) {
            delay(3_000)
            state.status = ""
        }
    }

    // 右上角刷新按钮的旋转：加载时持续转，结束后停住。
    val refreshRotation = remember { Animatable(0f) }
    LaunchedEffect(state.busy) {
        if (state.busy) {
            while (isActive) {
                refreshRotation.snapTo(0f)
                refreshRotation.animateTo(360f, tween(700, easing = LinearEasing))
            }
        } else {
            refreshRotation.snapTo(0f)
        }
    }

    // 进发现页自动加载我的歌单，不再需要按钮触发。
    LaunchedEffect(state.busy) {
        if (!state.busy && !state.playlistsLoaded) loadPlaylists()
    }

    /** 「我喜欢的音乐」重新拉一次最新内容（从别处点喜欢后回来、或切回该页时用）。 */
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

    // 推荐歌单也自动加载；与「我的歌单」共用 busy 旗标，靠状态变化轮转补上。
    LaunchedEffect(state.busy, state.recommendedLoaded) {
        if (!state.busy && !state.recommendedLoaded) loadRecommended()
    }

    // 停留在「我喜欢的音乐」时，若在别处（如首页）新喜欢了歌，切回来要能看到：
    // 一旦已喜欢集合里出现当前列表没有的歌，就重新拉一次。
    LaunchedEffect(LikedTracks.likedIds) {
        val page = state.page as? DiscoverPage.Tracks ?: return@LaunchedEffect
        if (!page.playlist.name.contains("我喜欢的音乐")) return@LaunchedEffect
        val shownIds = page.tracks.mapTo(HashSet()) { it.id }
        if (LikedTracks.likedIds.any { it !in shownIds }) {
            refreshLikedPage()
        }
    }

    // 队列变化（例如首页上拉追加）时同步当前二级列表。
    val hubQueue by PlaybackHub.queueFlow.collectAsState()
    LaunchedEffect(hubQueue) {
        val current = state.page
        if (current is DiscoverPage.Tracks &&
            // 「我喜欢的音乐」是独立歌单，绝不能被首页推荐队列覆盖。
            !current.playlist.name.contains("我喜欢的音乐") &&
            // 空列表不能做前缀比较（空 list 是任何 list 的前缀），否则会误灌入首页队列。
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
            // 「我喜欢的音乐」按服务端已喜欢集合过滤：取消喜欢后该行会立即消失。
            val shown = if (current.playlist.name.contains("我喜欢的音乐")) {
                current.tracks.filter { it.id in LikedTracks.likedIds }
            } else {
                current.tracks
            }
            SearchDetailPage(
                detail = SearchDetail.Playlist(current.playlist),
                tracks = shown,
                loading = loading,
                error = "",
                onBack = { state.page = DiscoverPage.Root },
                onPlayAll = {
                    if (shown.isNotEmpty()) onPlay(shown, 0, HOME_RECOMMEND)
                },
                onPlay = { index -> onPlay(shown, index, HOME_RECOMMEND) },
                applySystemBars = false,
            )
        }

        DiscoverPage.Root -> Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            ScreenHeader(
                "发现",
                actions = {
                    IconButton(
                        onClick = { if (!state.busy) loadRecommended() },
                        enabled = !state.busy,
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "刷新推荐歌单",
                            modifier = Modifier.rotate(refreshRotation.value),
                        )
                    }
                },
            )
            if (state.status.isNotEmpty()) {
                Text(
                    state.status,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                if (state.recommendedLoaded) {
                    state.recommended.forEach { group ->
                        // 分组标题本身就是分类，不再重复“推荐歌单”文案。
                        if (group.title.isNotBlank() && group.title != "推荐歌单") {
                            item(key = "g-${group.title}") { SectionTitle(group.title) }
                        }
                        items(group.playlists, key = { "rp-${it.id}" }) { playlist ->
                            PlaylistRow(playlist, enabled = !state.busy) {
                                openPlaylist(playlist)
                            }
                        }
                    }
                }
                if (state.playlistsLoaded) {
                    item { SectionTitle("我的歌单") }
                    items(state.playlists, key = { "p-${it.id}" }) { playlist ->
                        PlaylistRow(playlist, enabled = !state.busy) {
                            openPlaylist(playlist)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

/** 「我喜欢的音乐」图标：黑色圆角底 + 红色实心爱心。 */
@Composable
internal fun LikedIcon() {
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Favorite,
            contentDescription = null,
            tint = Color(0xFFE53935),
        )
    }
}
