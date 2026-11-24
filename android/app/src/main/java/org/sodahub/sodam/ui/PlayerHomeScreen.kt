package org.sodahub.sodam.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import org.sodahub.sodam.PlaybackMode
import org.sodahub.sodam.R
import uniffi.sodam_ffi.Scene
import uniffi.sodam_ffi.SodaClient
import uniffi.sodam_ffi.Track

/**
 * 首页 = 全屏播放页（参考汽水音乐）。
 *
 * - 上下滑切歌；左滑进入全屏歌词（页内横向 pager），歌词窗本身不可手动滚。
 * - 底部进度条/播放键固定不随翻页移动；切歌时只把时间清零。
 * - 右上角：搜索、更多（播放模式 + 定时关闭）。底部左侧：播放列表。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerHomeScreen(
    client: SodaClient,
    queue: List<Track>,
    currentIndex: Int,
    currentTrackId: String?,
    isPlaying: Boolean,
    positionMs: State<Long>,
    durationMs: State<Long>,
    qualities: Map<String, String>,
    preview: Boolean,
    playMode: PlaybackMode,
    loading: Boolean,
    refreshing: Boolean,
    status: String,
    sleepRemainingMs: Long,
    scenes: List<Scene>,
    scenesLoading: Boolean,
    currentSceneName: String?,
    onLoadScenes: () -> Unit,
    onSelectScene: (Scene) -> Unit,
    onSelectRecommendMode: () -> Unit,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onSelectIndex: (Int) -> Unit,
    onSelectMode: (PlaybackMode) -> Unit,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSearch: () -> Unit,
    onStartSleepTimer: (Int) -> Unit,
    onCancelSleepTimer: () -> Unit,
    needsLogin: Boolean,
    onOpenLogin: () -> Unit,
) {
    var showQueue by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var showScenes by remember { mutableStateOf(false) }
    var viewerUrl by remember { mutableStateOf<String?>(null) }
    val queueSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val moreSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sceneSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pagerState = rememberPagerState(
        initialPage = currentIndex.coerceAtLeast(0),
        pageCount = { queue.size },
    )
    var autoScrollTarget by remember { mutableStateOf<Int?>(null) }
    var pendingSelect by remember { mutableStateOf<Int?>(null) }
    // 锁屏/后台时帧时钟会被挂起，动画可能半途取消；回到前台必须重新对齐一次。
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

    // 首次加载只保留一个居中的加载态，避免与顶部/底部控件、引导叠在一起显得杂乱。
    if (queue.isEmpty() && refreshing && !needsLogin) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text("正在准备…", modifier = Modifier.padding(top = 12.dp))
            }
        }
        return
    }

    // 服务自动切歌后，pager 跟着滑过去。
    LaunchedEffect(resumed, currentIndex, queue.size) {
        if (currentIndex !in queue.indices) return@LaunchedEffect
        // 用户刚滑到别的页、切歌还没落实时，先别把人拽走。
        if (pendingSelect != null && pendingSelect != currentIndex) return@LaunchedEffect
        if (pagerState.currentPage != currentIndex) {
            val distance = kotlin.math.abs(currentIndex - pagerState.currentPage)
            autoScrollTarget = currentIndex
            try {
                // 后台跨了多首时不做长动画，回前台直接对齐到真实播放页。
                if (resumed && distance == 1) {
                    pagerState.animateScrollToPage(currentIndex)
                } else {
                    pagerState.scrollToPage(currentIndex)
                }
            } finally {
                if (autoScrollTarget == currentIndex) autoScrollTarget = null
            }
        }
        if (pendingSelect == currentIndex) pendingSelect = null
    }
    // 用户滑到新的一页 → 请求播放。程序自动滑页不算用户选择。
    LaunchedEffect(pagerState.settledPage) {
        val page = pagerState.settledPage
        if (autoScrollTarget != null) {
            autoScrollTarget = null
            return@LaunchedEffect
        }
        if (page in queue.indices && page != currentIndex && page != pendingSelect) {
            pendingSelect = page
            onSelectIndex(page)
        }
    }
    // 一次切歌结束后允许再次选择同一页（例如上次解析失败）。
    LaunchedEffect(loading) {
        if (!loading) pendingSelect = null
    }
    // 离队尾还有两页就预拉下一批，别等滑到最后一页才现拉。
    LaunchedEffect(pagerState.settledPage, queue.size) {
        if (queue.isNotEmpty() && queue.lastIndex - pagerState.settledPage <= 2) onLoadMore()
    }
    // 预取邻居封面与歌词：翻页时直接命中，不出现白块或"暂无歌词"闪一下。
    val context = LocalContext.current
    LaunchedEffect(currentIndex, queue.size) {
        if (currentIndex !in queue.indices) return@LaunchedEffect
        val neighbors = listOfNotNull(
            queue.getOrNull(currentIndex - 1),
            queue.getOrNull(currentIndex + 1),
        )
        for (track in neighbors) {
            if (track.cover.isNotBlank()) {
                context.imageLoader.enqueue(
                    ImageRequest.Builder(context).data(track.cover).build(),
                )
            }
        }
        for (track in neighbors) {
            launch { LyricsCache.prefetch(client, track.id) }
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 20.dp, end = 4.dp),
            ) {
                Text(
                    currentSceneName ?: "推荐模式",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (sleepRemainingMs > 0) {
                    Text(
                        "定时 ${formatCountdown(sleepRemainingMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = onOpenSearch) {
                    Icon(Icons.Filled.Search, contentDescription = "搜索")
                }
                IconButton(onClick = { showMore = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                }
            }

            if (queue.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (needsLogin) {
                            Text("未登录", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "登录后才能使用搜索、歌单与播放",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            Button(
                                onClick = onOpenLogin,
                                modifier = Modifier.padding(top = 16.dp),
                            ) { Text("去登录") }
                        } else if (refreshing) {
                            CircularProgressIndicator()
                            Text("正在加载…", Modifier.padding(top = 12.dp))
                        } else {
                            Text(
                                status.ifBlank { "还没有可播放的歌曲" },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            TextButton(onClick = onRefresh) { Text("重新加载") }
                        }
                    }
                }
            } else {
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = { if (pagerState.currentPage == 0) onRefresh() },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    VerticalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                    ) { page ->
                        val track = queue.getOrNull(page) ?: return@VerticalPager
                        val isCurrent = track.id == currentTrackId
                        val lyrics = rememberLyrics(client, track.id)
                        // 只有当前页跟随播放位置；传 State 对象，让高亮在歌词组件内部局部重组。
                        val activePosition = if (isCurrent) positionMs else null
                        val lyricsPager = rememberPagerState(pageCount = { 2 })

                        HorizontalPager(
                            state = lyricsPager,
                            modifier = Modifier.fillMaxSize(),
                        ) { pane ->
                            if (pane == 0) {
                                SongInfoPage(
                                    track = track,
                                    isCurrent = isCurrent,
                                    quality = qualities[track.id].orEmpty(),
                                    preview = isCurrent && preview,
                                    lyrics = lyrics,
                                    positionMs = activePosition,
                                    onSeek = onSeek,
                                    onOpenCover = { viewerUrl = track.cover },
                                )
                            } else {
                                FullLyricsPane(
                                    lyrics = lyrics,
                                    positionMs = activePosition,
                                    clickable = isCurrent,
                                    onSeek = onSeek,
                                )
                            }
                        }
                    }
                }
            }

            // 固定底部控件：翻页时不移动，切歌时只把时间归零。
            BottomControls(
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = isPlaying,
                onToggle = onToggle,
                onSeek = onSeek,
                onOpenQueue = { showQueue = true },
                onOpenScenes = {
                    onLoadScenes()
                    showScenes = true
                },
                enabled = queue.isNotEmpty(),
            )
        }

        if (showQueue) {
            ModalBottomSheet(
                onDismissRequest = { showQueue = false },
                sheetState = queueSheetState,
            ) {
                Text(
                    "播放列表（${queue.size}）",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp).navigationBarsPadding()) {
                    itemsIndexed(queue, key = { _, track -> track.id }) { index, track ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    track.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (track.id == currentTrackId) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            },
                            supportingContent = {
                                Text(
                                    buildString {
                                        append(track.artist)
                                        if (track.vip) append(" · VIP")
                                        if (CachedAudio.isCached(track.id)) append(" · 已缓存")
                                        append(" · ").append(formatDuration(track.duration * 1000))
                                    },
                                    maxLines = 1,
                                )
                            },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (track.id == currentTrackId) {
                                        PlayingBars(active = isPlaying)
                                    }
                                    LikeButton(track)
                                }
                            },
                            modifier = Modifier.clickable {
                                onSelectIndex(index)
                                showQueue = false
                            },
                        )
                    }
                }
            }
        }

        if (showMore) {
            ModalBottomSheet(
                onDismissRequest = { showMore = false },
                sheetState = moreSheetState,
            ) {
                Text(
                    "播放模式",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                listOf(
                    PlaybackMode.ListLoop,
                    PlaybackMode.Shuffle,
                    PlaybackMode.SingleLoop,
                ).forEach { mode ->
                    ListItem(
                        headlineContent = { Text(mode.label) },
                        trailingContent = {
                            if (playMode == mode) {
                                Text("已选", color = MaterialTheme.colorScheme.primary)
                            }
                        },
                        modifier = Modifier.clickable { onSelectMode(mode) },
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                SleepTimerPanel(
                    sleepRemainingMs = sleepRemainingMs,
                    onStart = onStartSleepTimer,
                    onCancel = onCancelSleepTimer,
                )
            }
        }

        if (showScenes) {
            ModalBottomSheet(
                onDismissRequest = { showScenes = false },
                sheetState = sceneSheetState,
            ) {
                Text(
                    "听歌模式",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                if (scenesLoading) {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                } else if (scenes.isEmpty()) {
                    Text(
                        "没有听歌模式（需要登录）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                } else {
                    LazyColumn(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .navigationBarsPadding(),
                    ) {
                        item(key = "recommend-mode") {
                            ListItem(
                                leadingContent = {
                                    Icon(
                                        painterResource(R.drawable.ic_switch),
                                        contentDescription = null,
                                        modifier = Modifier.size(36.dp),
                                        tint = if (currentSceneName == null) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                },
                                headlineContent = {
                                    Text(
                                        "推荐模式",
                                        color = if (currentSceneName == null) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                },
                                supportingContent = { Text("默认推荐") },
                                modifier = Modifier.clickable {
                                    showScenes = false
                                    onSelectRecommendMode()
                                },
                            )
                        }
                        itemsIndexed(
                            scenes,
                            key = { _, scene -> "s-${scene.sceneModeId}-${scene.subQueueType}-${scene.text}" },
                        ) { _, scene ->
                            ListItem(
                                leadingContent = {
                                    // 场景图标多为白色线条，加黑底才能在浅色主题下看清。
                                    Box(
                                        Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color.Black),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        AsyncImage(
                                            model = scene.cover,
                                            contentDescription = null,
                                            modifier = Modifier.size(26.dp),
                                            contentScale = ContentScale.Fit,
                                        )
                                    }
                                },
                                headlineContent = {
                                    Text(
                                        scene.text,
                                        color = if (scene.text == currentSceneName) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                },
                                supportingContent = {
                                    Text(if (scene.sceneModeId >= 0) "场景模式" else "本地模式")
                                },
                                modifier = Modifier.clickable {
                                    showScenes = false
                                    onSelectScene(scene)
                                },
                            )
                        }
                    }
                }
            }
        }

        viewerUrl?.let { url ->
            CoverViewerDialog(url = url, onDismiss = { viewerUrl = null })
        }
    }
}

@Composable
private fun BottomControls(
    positionMs: State<Long>,
    durationMs: State<Long>,
    isPlaying: Boolean,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenQueue: () -> Unit,
    onOpenScenes: () -> Unit,
    enabled: Boolean,
) {
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val position = positionMs.value
    val duration = durationMs.value
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatDuration(position), style = MaterialTheme.typography.labelSmall)
            Slider(
                value = dragValue ?: position.toFloat(),
                onValueChange = { dragValue = it },
                onValueChangeFinished = {
                    dragValue?.let { onSeek(it.toLong()) }
                    dragValue = null
                },
                valueRange = 0f..(if (duration > 0) duration.toFloat() else 1f),
                enabled = enabled,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text(formatDuration(duration), style = MaterialTheme.typography.labelSmall)
        }
        Box(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(
                onClick = onOpenQueue,
                enabled = enabled,
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                Icon(Icons.Filled.List, contentDescription = "播放列表")
            }
            Button(onClick = onToggle, enabled = enabled) {
                Text(if (isPlaying) "暂停" else "播放")
            }
            IconButton(
                onClick = onOpenScenes,
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Icon(
                    painterResource(R.drawable.ic_switch),
                    contentDescription = "听歌模式",
                )
            }
        }
    }
}

/** 页内第一屏：封面、歌名/作者、标签、固定高度歌词窗。 */
@Composable
private fun SongInfoPage(
    track: Track,
    isCurrent: Boolean,
    quality: String,
    preview: Boolean,
    lyrics: LyricsState,
    positionMs: State<Long>?,
    onSeek: (Long) -> Unit,
    onOpenCover: () -> Unit,
) {
    // 缓存标签独立于解析档位：进 App 时已落盘的歌即使还没解析也能显示。
    val qualityLabel = quality.substringBefore(" · 已缓存")
    val showCache = CachedAudio.isCached(track.id) || quality.contains("已缓存")
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // 正方形封面取可用宽高里较小的一边，避免在矮屏上撑出格子盖住歌名。
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            val side = minOf(maxWidth, maxHeight)
            AsyncImage(
                model = track.cover,
                contentDescription = "封面",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(side)
                    .clip(RoundedCornerShape(24.dp))
                    .clickable { onOpenCover() },
            )
        }

        // 歌名 + 歌手作为一组，高度随行数自适应；上方留出与封面的间距，标签紧贴其下。
        Box(
            Modifier.fillMaxWidth().padding(top = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Column(Modifier.fillMaxWidth().padding(end = 48.dp)) {
                Text(
                    track.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    buildString {
                        append(track.artist)
                        if (track.album.isNotBlank() && track.album != track.artist) {
                            append(" · ").append(track.album)
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
            }
            if (isCurrent) {
                Box(Modifier.align(Alignment.CenterEnd)) { LikeButton(track) }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp),
        ) {
            if (track.vip) InfoBadge("VIP", MaterialTheme.colorScheme.tertiary)
            if (preview) InfoBadge("试听", MaterialTheme.colorScheme.error)
            if (qualityLabel.isNotBlank()) InfoBadge(qualityLabel, MaterialTheme.colorScheme.primary)
            if (showCache) InfoBadge("已缓存", MaterialTheme.colorScheme.tertiary)
        }

        LyricWindow(
            lyrics = lyrics,
            positionMs = positionMs,
            clickable = isCurrent,
            onLineClick = onSeek,
            modifier = Modifier.fillMaxWidth().height(140.dp).padding(vertical = 4.dp),
        )
    }
}

/** 页内第二屏（左滑进入）：全屏歌词，可手动滚动。 */
@Composable
private fun FullLyricsPane(
    lyrics: LyricsState,
    positionMs: State<Long>?,
    clickable: Boolean,
    onSeek: (Long) -> Unit,
) {
    val activeIndex by remember(lyrics, positionMs) {
        derivedStateOf {
            val position = positionMs?.value ?: return@derivedStateOf -1
            lyrics.lines.indexOfLast { it.startMs <= position }
        }
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            lyrics.loading -> CircularProgressIndicator()
            lyrics.error.isNotEmpty() -> Text(
                lyrics.error,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            lyrics.lines.isEmpty() -> Text(
                "暂无歌词",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                val scrollState = rememberScrollState()
                val heights = remember(lyrics) { IntArray(lyrics.lines.size) }
                val viewportPx = constraints.maxHeight.toFloat()
                val centerPad = with(LocalDensity.current) { (viewportPx / 2f).toDp() }
                // 自然高度换行 + 实测位置，选中行落在中线；用户手动滚动时不抢。
                LaunchedEffect(activeIndex) {
                    if (activeIndex < 0 || activeIndex >= heights.size) return@LaunchedEffect
                    if (scrollState.isScrollInProgress) return@LaunchedEffect
                    withFrameNanos { }
                    withFrameNanos { }
                    var before = 0
                    for (i in 0 until activeIndex) before += heights[i]
                    scrollState.animateScrollTo((before + heights[activeIndex] / 2).coerceAtLeast(0))
                }
                Column(
                    Modifier.fillMaxSize().verticalScroll(scrollState),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(centerPad))
                    lyrics.lines.forEachIndexed { index, line ->
                        val active = index == activeIndex
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { heights[index] = it.size.height }
                                .clickable(enabled = clickable) { onSeek(line.startMs) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                line.text,
                                textAlign = TextAlign.Center,
                                // 长句（尤其英文）必须整行换行展示，不能省略成一行。
                                maxLines = Int.MAX_VALUE,
                                softWrap = true,
                                fontSize = if (active) 19.sp else 15.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (active) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 2.dp, vertical = 6.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(centerPad))
                }
            }
        }
    }
}

@Composable
private fun InfoBadge(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .padding(end = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** 固定高度的歌词窗：自动跟随、不可手动滚动；点词 seek，非当前页点任意位置切歌。 */
@Composable
private fun LyricWindow(
    lyrics: LyricsState,
    positionMs: State<Long>?,
    clickable: Boolean,
    onLineClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeIndex by remember(lyrics, positionMs) {
        derivedStateOf {
            val position = positionMs?.value ?: return@derivedStateOf -1
            lyrics.lines.indexOfLast { it.startMs <= position }
        }
    }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        when {
            lyrics.loading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            lyrics.error.isNotEmpty() -> Text(
                lyrics.error,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            lyrics.lines.isEmpty() -> Text(
                "暂无歌词",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                val scrollState = rememberScrollState()
                val heights = remember(lyrics) { IntArray(lyrics.lines.size) }
                val viewportPx = constraints.maxHeight.toFloat()
                val centerPad = with(LocalDensity.current) { (viewportPx / 2f).toDp() }
                // 按实际测量高度滚动：超长行自然换行，行距只保留 3dp。
                LaunchedEffect(activeIndex) {
                    if (activeIndex < 0 || activeIndex >= heights.size) return@LaunchedEffect
                    withFrameNanos { }
                    withFrameNanos { }
                    var before = 0
                    for (i in 0 until activeIndex) before += heights[i]
                    scrollState.animateScrollTo((before + heights[activeIndex] / 2).coerceAtLeast(0))
                }
                Column(
                    // 歌词窗本身不接收滑动手势，纵向滑动留给整页切歌。
                    Modifier.fillMaxSize().verticalScroll(scrollState, enabled = false),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(centerPad))
                    lyrics.lines.forEachIndexed { index, line ->
                        val active = index == activeIndex
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { heights[index] = it.size.height }
                                .clickable(enabled = clickable) { onLineClick(line.startMs) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                line.text,
                                textAlign = TextAlign.Center,
                                // 固定歌词窗同样换行完整展示，避免长句被截断。
                                maxLines = Int.MAX_VALUE,
                                softWrap = true,
                                fontSize = if (active) 16.sp else 13.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (active) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 2.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(centerPad))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepTimerPanel(
    sleepRemainingMs: Long,
    onStart: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    var chosenMinutes by remember { mutableStateOf<Int?>(null) }
    var customMinutes by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text("定时关闭", style = MaterialTheme.typography.titleMedium)
        Text(
            "到点自动暂停播放",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 12.dp),
        ) {
            listOf(15, 30, 45, 60).forEach { minutes ->
                FilterChip(
                    selected = chosenMinutes == minutes,
                    onClick = {
                        chosenMinutes = minutes
                        customMinutes = ""
                    },
                    label = { Text("$minutes 分钟") },
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            OutlinedTextField(
                value = customMinutes,
                onValueChange = { customMinutes = it },
                label = { Text("自定义（分钟）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    val minutes = customMinutes.trim().toIntOrNull() ?: chosenMinutes
                    if (minutes != null && minutes in 1..720) onStart(minutes)
                },
                modifier = Modifier.padding(start = 8.dp),
            ) { Text("开始倒计时") }
        }
        if (sleepRemainingMs > 0) {
            Text(
                "剩余 ${formatCountdown(sleepRemainingMs)}",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 16.dp),
            )
            TextButton(onClick = onCancel) { Text("取消倒计时") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

internal fun formatCountdown(ms: Long): String {
    val seconds = ((ms - 1_000L).coerceAtLeast(0L)) / 1_000L
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}
