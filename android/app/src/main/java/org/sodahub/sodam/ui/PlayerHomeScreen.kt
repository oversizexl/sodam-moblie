package org.sodahub.sodam.ui

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.sodahub.sodam.PlaybackMode
import org.sodahub.sodam.R
import uniffi.sodam_ffi.Scene
import uniffi.sodam_ffi.SodaClient
import uniffi.sodam_ffi.Track
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 首页 = 全屏沉浸播放页（保留上下滑切歌与左右滑切全屏歌词交互）。
 *
 * - 全版本兼容的动态封面色调提取渐变背景（Android 12+ 同步叠加硬件模糊层），浓度可在设置页自定义；
 * - 切歌触发可自定义强度的震动触感；
 * - 横滑歌词页右下角提供一键滑回封面的胶囊返回按钮；
 * - 点击歌名跳转专辑页、点击歌手跳转歌手页、点击音质跳转设置页。
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
    backdropAlpha: Float = DEFAULT_BACKDROP_ALPHA,
    hapticLevel: HapticLevel = HapticLevel.Light,
    playButtonStyle: PlayButtonStyle = PlayButtonStyle.ProgressRing,
    bottomInset: Dp = 0.dp,
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
    onOpenAlbumForTrack: (Track) -> Unit = {},
    onOpenArtistForTrack: (Track) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onStartSleepTimer: (Int) -> Unit,
    onCancelSleepTimer: () -> Unit,
    needsLogin: Boolean,
    onOpenLogin: () -> Unit,
) {
    var showQueue by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var showScenes by remember { mutableStateOf(false) }
    var viewerUrl by remember { mutableStateOf<String?>(null) }
    var downloadToast by remember { mutableStateOf("") }
    val queueSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val moreSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sceneSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pagerState = rememberPagerState(
        initialPage = currentIndex.coerceAtLeast(0),
        pageCount = { queue.size },
    )
    var autoScrollTarget by remember { mutableStateOf<Int?>(null) }
    var pendingSelect by remember { mutableStateOf<Int?>(null) }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(downloadToast) {
        if (downloadToast.isNotEmpty()) {
            delay(3_000)
            downloadToast = ""
        }
    }

    // 只消费一次性的下载提示事件，切换底部导航不再重复出现
    LaunchedEffect(Unit) {
        TrackDownloader.events.collect { msg ->
            downloadToast = msg
        }
    }

    if (queue.isEmpty() && refreshing && !needsLogin) {
        Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(strokeWidth = 2.5.dp)
                Text(
                    "正在调制专属音乐流…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        }
        return
    }

    // 服务自动切歌后，pager 跟着滑过去。
    LaunchedEffect(resumed, currentIndex, queue.size) {
        if (currentIndex !in queue.indices) return@LaunchedEffect
        if (pendingSelect != null && pendingSelect != currentIndex) return@LaunchedEffect
        if (pagerState.currentPage != currentIndex) {
            val distance = abs(currentIndex - pagerState.currentPage)
            autoScrollTarget = currentIndex
            try {
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

    // 用户滑到新的一页 -> 请求播放 + 触发可定制强度的切歌微震触感。
    LaunchedEffect(pagerState.settledPage) {
        val page = pagerState.settledPage
        if (autoScrollTarget != null) {
            autoScrollTarget = null
            return@LaunchedEffect
        }
        if (page in queue.indices && page != currentIndex && page != pendingSelect) {
            performSodamHaptic(context, hapticLevel)
            pendingSelect = page
            onSelectIndex(page)
        }
    }

    LaunchedEffect(loading) {
        if (!loading) pendingSelect = null
    }

    LaunchedEffect(pagerState.settledPage, queue.size) {
        if (queue.isNotEmpty() && queue.lastIndex - pagerState.settledPage <= 2) onLoadMore()
    }

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

    val activeTrack = queue.getOrNull(currentIndex) ?: queue.getOrNull(pagerState.currentPage)
    val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            // 全版本兼容的动态封面色提取 + 高斯模糊氛围底板（铺满含状态栏及底部导航区）
            AmbientCoverBackdrop(
                coverUrl = activeTrack?.cover.orEmpty(),
                intensity = backdropAlpha,
            )

            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(bottom = bottomInset),
            ) {
                // 顶部沉浸栏：流光模式切换胶囊 + 定时倒计时 + 搜索/更多圆形微光按钮
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 14.dp, top = 6.dp, bottom = 2.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                            .border(
                                0.8.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                RoundedCornerShape(18.dp),
                            )
                            .bounceClick(scaleDown = 0.94f) {
                                onLoadScenes()
                                showScenes = true
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(R.drawable.ic_headphones),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = currentSceneName ?: "推荐模式",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    if (sleepRemainingMs > 0) {
                        Box(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                                .clickable { showMore = true }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_timer),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(13.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = formatCountdown(sleepRemainingMs),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                            .bounceClick(scaleDown = 0.9f, onClick = onOpenSearch),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = "搜索",
                            modifier = Modifier.size(19.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                            .bounceClick(scaleDown = 0.9f) { showMore = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "更多",
                            modifier = Modifier.size(19.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                if (queue.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 32.dp),
                        ) {
                            if (needsLogin) {
                                Text(
                                    "欢迎使用 Sodam",
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                )
                                Text(
                                    "登录后即可开启个性化上下滑听歌与歌单同步",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                                Button(
                                    onClick = onOpenLogin,
                                    shape = RoundedCornerShape(18.dp),
                                    modifier = Modifier.padding(top = 20.dp),
                                ) {
                                    Text("立即登录", modifier = Modifier.padding(horizontal = 12.dp))
                                }
                            } else if (refreshing) {
                                CircularProgressIndicator(strokeWidth = 2.5.dp)
                                Text(
                                    "正在加载曲库…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                            } else {
                                Text(
                                    status.ifBlank { "还没有可播放的歌曲" },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                                TextButton(onClick = onRefresh, modifier = Modifier.padding(top = 8.dp)) {
                                    Text("重新加载")
                                }
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
                            val activePosition = if (isCurrent) positionMs else null
                            val lyricsPager = rememberPagerState(pageCount = { 2 })

                            val pageOffset = (
                                (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                                ).coerceIn(-1f, 1f)
                            val pageScale = lerp(1f, 0.93f, abs(pageOffset))
                            val pageAlpha = lerp(1f, 0.45f, abs(pageOffset))

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        scaleX = pageScale
                                        scaleY = pageScale
                                        alpha = pageAlpha
                                    },
                            ) {
                                Column(Modifier.fillMaxSize()) {
                                    // 极简双点翻页指示器（实时跟随左滑封面 <-> 全屏歌词过渡伸缩）
                                    val paneProgress = (
                                        lyricsPager.currentPage + lyricsPager.currentPageOffsetFraction
                                        ).coerceIn(0f, 1f)
                                    val leftDotWidth = lerp(14f, 5f, paneProgress).dp
                                    val rightDotWidth = lerp(5f, 14f, paneProgress).dp
                                    val leftDotAlpha = lerp(0.9f, 0.3f, paneProgress)
                                    val rightDotAlpha = lerp(0.3f, 0.9f, paneProgress)
                                    val dotBaseColor = MaterialTheme.colorScheme.onSurface

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 4.dp, bottom = 2.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .width(leftDotWidth)
                                                .height(5.dp)
                                                .clip(CircleShape)
                                                .background(dotBaseColor.copy(alpha = leftDotAlpha)),
                                        )
                                        Spacer(Modifier.width(5.dp))
                                        Box(
                                            modifier = Modifier
                                                .width(rightDotWidth)
                                                .height(5.dp)
                                                .clip(CircleShape)
                                                .background(dotBaseColor.copy(alpha = rightDotAlpha)),
                                        )
                                    }

                                    HorizontalPager(
                                        state = lyricsPager,
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
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
                                                onClickTitle = { onOpenAlbumForTrack(track) },
                                                onClickArtist = { onOpenArtistForTrack(track) },
                                                onClickQuality = onOpenSettings,
                                                onDownloadFeedback = { downloadToast = it },
                                            )
                                        } else {
                                            FullLyricsPane(
                                                lyrics = lyrics,
                                                positionMs = activePosition,
                                                clickable = isCurrent,
                                                onSeek = onSeek,
                                                onBackToCover = {
                                                    scope.launch { lyricsPager.animateScrollToPage(0) }
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 固定底部控件：流体进度条 + 实体控制核心区
                BottomControls(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    isPlaying = isPlaying,
                    playButtonStyle = playButtonStyle,
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

            FloatingStatusPill(
                message = downloadToast,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottomInset + 16.dp),
            )
        }

        if (showQueue) {
            ModalBottomSheet(
                onDismissRequest = { showQueue = false },
                sheetState = queueSheetState,
                shape = sheetShape,
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "播放队列",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                                .padding(horizontal = 10.dp, vertical = 3.dp),
                        ) {
                            Text(
                                text = "${queue.size} 首",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    val initialQueuePos = (currentIndex - 2).coerceAtLeast(0)
                    val queueListState = rememberLazyListState(initialFirstVisibleItemIndex = initialQueuePos)
                    LazyColumn(
                        state = queueListState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 440.dp)
                            .navigationBarsPadding(),
                        contentPadding = PaddingValues(bottom = 20.dp),
                    ) {
                        itemsIndexed(queue, key = { _, track -> track.id }) { index, track ->
                            TrackRow(
                                track = track,
                                enabled = true,
                                index = index,
                                isCurrent = track.id == currentTrackId,
                                isPlaying = isPlaying,
                                onClick = {
                                    onSelectIndex(index)
                                    showQueue = false
                                },
                            )
                        }
                    }
                }
            }
        }

        if (showMore) {
            ModalBottomSheet(
                onDismissRequest = { showMore = false },
                sheetState = moreSheetState,
                shape = sheetShape,
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                ) {
                    SectionLabel("播放模式")
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp),
                    ) {
                        listOf(
                            PlaybackMode.ListLoop,
                            PlaybackMode.Shuffle,
                            PlaybackMode.SingleLoop,
                        ).forEach { mode ->
                            SelectablePill(
                                label = mode.label,
                                selected = playMode == mode,
                                onClick = { onSelectMode(mode) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    Spacer(Modifier.height(14.dp))

                    SleepTimerPanel(
                        sleepRemainingMs = sleepRemainingMs,
                        onStart = onStartSleepTimer,
                        onCancel = onCancelSleepTimer,
                    )
                }
            }
        }

        if (showScenes) {
            ModalBottomSheet(
                onDismissRequest = { showScenes = false },
                sheetState = sceneSheetState,
                shape = sheetShape,
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
                    Text(
                        "听歌模式",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                    )
                    if (scenesLoading) {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                        }
                    } else if (scenes.isEmpty()) {
                        Text(
                            "没有可用听歌模式（可能需要登录）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
                        )
                    } else {
                        LazyColumn(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 440.dp)
                                .navigationBarsPadding(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp),
                        ) {
                            item(key = "recommend-mode") {
                                val selected = currentSceneName == null
                                SceneCardRow(
                                    title = "推荐模式",
                                    subtitle = "根据听歌偏好实时生成流式电台",
                                    selected = selected,
                                    leading = {
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                painterResource(R.drawable.ic_switch),
                                                contentDescription = null,
                                                modifier = Modifier.size(22.dp),
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    },
                                    onClick = {
                                        showScenes = false
                                        onSelectRecommendMode()
                                    },
                                )
                            }
                            itemsIndexed(
                                scenes,
                                key = { _, scene -> "s-${scene.sceneModeId}-${scene.subQueueType}-${scene.text}" },
                            ) { _, scene ->
                                val selected = scene.text == currentSceneName
                                SceneCardRow(
                                    title = scene.text,
                                    subtitle = if (scene.sceneModeId >= 0) "官方场景电台" else "本地频道模式",
                                    selected = selected,
                                    leading = {
                                        Box(
                                            Modifier
                                                .size(40.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(Color(0xFF14171A)),
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
                                    onClick = {
                                        showScenes = false
                                        onSelectScene(scene)
                                    },
                                )
                            }
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

/**
 * 兼容全版本 Android 的动态封面背景：
 * 1. 通过 Coil 提取缩略图主色调与呼应色，在 Android 8~16 上均能渲染双色流光渐变底板；
 * 2. Android 12+ 额外叠加硬件模糊封面图层；
 * 3. 背景透明度（浓度）由 [intensity]（0f..1f）实时驱动，支持在设置页自定义。
 */
@Composable
private fun AmbientCoverBackdrop(coverUrl: String, intensity: Float) {
    val context = LocalContext.current
    val surface = MaterialTheme.colorScheme.background
    val fallbackPrimary = MaterialTheme.colorScheme.primary

    var extractedColors by remember { mutableStateOf(fallbackPrimary to fallbackPrimary) }
    LaunchedEffect(coverUrl, fallbackPrimary) {
        if (coverUrl.isBlank()) {
            extractedColors = fallbackPrimary to fallbackPrimary
            return@LaunchedEffect
        }
        val colors = withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(coverUrl)
                    .size(64)
                    .allowHardware(false)
                    .build()
                val res = context.imageLoader.execute(request) as? SuccessResult
                val bmp = res?.drawable?.toBitmap()
                if (bmp != null) extractCoverPalette(bmp, fallbackPrimary) else null
            }.getOrNull()
        }
        if (colors != null) extractedColors = colors
    }

    val topTone by animateColorAsState(
        targetValue = extractedColors.first,
        animationSpec = tween(750),
        label = "backdrop_tone1",
    )
    val bottomTone by animateColorAsState(
        targetValue = extractedColors.second,
        animationSpec = tween(750),
        label = "backdrop_tone2",
    )
    val strength = intensity.coerceIn(0f, 1f)

    Box(Modifier.fillMaxSize().background(surface)) {
        if (strength > 0.01f) {
            // 全版本生效：提取的主色调双色流光渐变
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.0f to topTone.copy(alpha = strength * 0.55f),
                            0.42f to bottomTone.copy(alpha = strength * 0.34f),
                            0.80f to topTone.copy(alpha = strength * 0.22f),
                            1.0f to bottomTone.copy(alpha = strength * 0.30f),
                        ),
                    ),
            )

            // Android 12+ 叠加坡度更柔和的高斯模糊封面纹理
            if (coverUrl.isNotBlank() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Crossfade(
                    targetState = coverUrl,
                    animationSpec = tween(durationMillis = 600),
                    label = "ambient_cover",
                ) { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .blur(85.dp)
                            .alpha(strength * 0.50f),
                    )
                }
            }
        }

        // 柔和前景色保护层：保持流光透出到底，同时保证浅色/深色模式下歌名和歌词高度可读
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to surface.copy(alpha = 0.24f),
                        0.55f to surface.copy(alpha = 0.42f),
                        1.0f to surface.copy(alpha = 0.58f),
                    ),
                ),
        )
    }
}

@Composable
private fun SceneCardRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    leading: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val bg by animateColorAsState(
        targetValue = if (selected) primary.copy(alpha = 0.13f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        label = "scene_bg",
    )
    val border by animateColorAsState(
        targetValue = if (selected) primary.copy(alpha = 0.65f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        label = "scene_border",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(18.dp))
            .bounceClick(scaleDown = 0.98f, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                ),
                color = if (selected) primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(primary)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = "当前",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun BottomControls(
    positionMs: State<Long>,
    durationMs: State<Long>,
    isPlaying: Boolean,
    playButtonStyle: PlayButtonStyle,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenQueue: () -> Unit,
    onOpenScenes: () -> Unit,
    enabled: Boolean,
) {
    val position = positionMs.value
    val duration = durationMs.value
    val progress = if (duration > 0L) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 2.dp),
    ) {
        FluidSeekBar(
            positionMs = position,
            durationMs = duration,
            enabled = enabled,
            onSeek = onSeek,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .border(
                        0.8.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                        CircleShape,
                    )
                    .bounceClick(enabled = enabled, scaleDown = 0.9f, onClick = onOpenQueue),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.List,
                    contentDescription = "播放列表",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(21.dp),
                )
            }

            // 可在设置页切换的 4 种播放/暂停主按键样式
            MorphingPlayButton(
                isPlaying = isPlaying,
                style = playButtonStyle,
                progress = progress,
                enabled = enabled,
                size = 58.dp,
                iconSize = 25.dp,
                onClick = onToggle,
            )

            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .border(
                        0.8.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                        CircleShape,
                    )
                    .bounceClick(scaleDown = 0.9f, onClick = onOpenScenes),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_switch),
                    contentDescription = "听歌模式",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(21.dp),
                )
            }
        }
    }
}

/**
 * NeriPlayer 风格流体细轨进度条（FluidSeekBar）：
 * - 默认：精致 3.5dp 圆润轨道 + 小巧发光指示点；
 * - 按住拖动：轨道平稳加粗至 7dp，滑点放大带半透明光环，并悬浮显示实时时间气泡；
 */
@Composable
private fun FluidSeekBar(
    positionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var widthPx by remember { mutableStateOf(1) }

    val realFraction = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val shownFraction = if (dragging) dragFraction else realFraction
    val shownPosition = if (dragging) (dragFraction * durationMs).toLong() else positionMs

    val trackHeight by animateDpAsState(
        targetValue = if (dragging) 7.dp else 3.5.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "seek_height",
    )
    val thumbRadius by animateDpAsState(
        targetValue = if (dragging) 7.5.dp else 4.dp,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium),
        label = "seek_thumb",
    )
    val haloRadius by animateDpAsState(
        targetValue = if (dragging) 15.dp else 0.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "seek_halo",
    )
    val animatedFraction by animateFloatAsState(
        targetValue = shownFraction,
        animationSpec = if (dragging) tween(0) else tween(120),
        label = "seek_frac",
    )

    val primary = MaterialTheme.colorScheme.primary
    val inactiveTrack = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)

    Column(Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
                .pointerInput(enabled, durationMs) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { offset ->
                        val frac = (offset.x / widthPx).coerceIn(0f, 1f)
                        onSeek((frac * durationMs).toLong())
                    }
                }
                .pointerInput(enabled, durationMs) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            dragFraction = (offset.x / widthPx).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            onSeek((dragFraction * durationMs).toLong())
                            dragging = false
                        },
                        onDragCancel = {
                            dragging = false
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            dragFraction = (change.position.x / widthPx).coerceIn(0f, 1f)
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxWidth().height(24.dp)) {
                val trackH = trackHeight.toPx()
                val cy = size.height / 2f
                val corner = CornerRadius(trackH / 2f, trackH / 2f)

                drawRoundRect(
                    color = inactiveTrack,
                    topLeft = Offset(0f, cy - trackH / 2f),
                    size = Size(size.width, trackH),
                    cornerRadius = corner,
                )

                val activeW = (size.width * animatedFraction).coerceIn(0f, size.width)
                if (activeW > 0f) {
                    drawRoundRect(
                        color = primary,
                        topLeft = Offset(0f, cy - trackH / 2f),
                        size = Size(activeW, trackH),
                        cornerRadius = corner,
                    )
                }

                val cx = activeW
                if (haloRadius.toPx() > 0f) {
                    drawCircle(
                        color = primary.copy(alpha = 0.24f),
                        radius = haloRadius.toPx(),
                        center = Offset(cx, cy),
                    )
                }

                drawCircle(
                    color = Color.White,
                    radius = thumbRadius.toPx(),
                    center = Offset(cx, cy),
                )
            }

            if (dragging && durationMs > 0L) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset {
                            val px = ((widthPx * dragFraction).roundToInt() - 40).coerceIn(0, (widthPx - 80).coerceAtLeast(0))
                            IntOffset(px, -48)
                        }
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.92f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = formatDuration(shownPosition),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontFeatureSettings = "tnum",
                        ),
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatDuration(shownPosition),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Medium,
                    fontFeatureSettings = "tnum",
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatDuration(durationMs),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Medium,
                    fontFeatureSettings = "tnum",
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 页内第一屏：
 * - 点击歌名跳转包含该曲目的专辑页；
 * - 点击歌手跳转歌手页；
 * - 点击音质标签跳转设置页。
 */
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
    onClickTitle: () -> Unit,
    onClickArtist: () -> Unit,
    onClickQuality: () -> Unit,
    onDownloadFeedback: (String) -> Unit,
) {
    val qualityLabel = quality.substringBefore(" · 已缓存")
    val showCache = CachedAudio.isCached(track.id) || quality.contains("已缓存")
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            val side = minOf(maxWidth, maxHeight)
            Box(
                modifier = Modifier.size(side),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size((side * 0.93f).coerceAtLeast(0.dp))
                        .shadow(
                            elevation = 30.dp,
                            shape = RoundedCornerShape(28.dp),
                            spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                        ),
                )
                // 封面加载微光呼吸占位底板
                val pulse by rememberInfiniteTransition(label = "cover_placeholder").animateFloat(
                    initialValue = 0.35f,
                    targetValue = 0.65f,
                    animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
                    label = "cover_pulse",
                )
                Box(
                    modifier = Modifier
                        .size(side)
                        .clip(RoundedCornerShape(26.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = pulse))
                        .border(
                            0.8.dp,
                            Color.White.copy(alpha = 0.14f),
                            RoundedCornerShape(26.dp),
                        )
                        .bounceClick(scaleDown = 0.97f, onClick = onOpenCover),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_music_note),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f),
                        modifier = Modifier.size(48.dp),
                    )
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(track.cover)
                            .crossfade(380)
                            .build(),
                        contentDescription = "封面",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 6.dp)) {
                Text(
                    track.name,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onClickTitle)
                        .padding(vertical = 1.dp),
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
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onClickArtist)
                        .padding(top = 3.dp, bottom = 1.dp),
                )
            }
            if (isCurrent) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DownloadButton(
                        track = track,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        onFeedback = onDownloadFeedback,
                    )
                    LikeButton(track)
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
        ) {
            if (track.vip) InfoBadge("VIP", Color(0xFFFFB300))
            if (preview) InfoBadge("试听", MaterialTheme.colorScheme.error)
            if (qualityLabel.isNotBlank()) {
                InfoBadge(
                    text = qualityLabel,
                    color = MaterialTheme.colorScheme.primary,
                    onClick = onClickQuality,
                )
            }
            if (showCache) InfoBadge("已缓存", MaterialTheme.colorScheme.tertiary)
        }

        LyricWindow(
            lyrics = lyrics,
            positionMs = positionMs,
            clickable = isCurrent,
            onLineClick = onSeek,
            modifier = Modifier.fillMaxWidth().height(102.dp).padding(vertical = 2.dp),
        )
    }
}

/** 页内第二屏（左滑进入）：舞台景深全屏歌词，带一键滑回封面胶囊按钮。 */
@Composable
private fun FullLyricsPane(
    lyrics: LyricsState,
    positionMs: State<Long>?,
    clickable: Boolean,
    onSeek: (Long) -> Unit,
    onBackToCover: () -> Unit,
) {
    val activeIndex by remember(lyrics, positionMs) {
        derivedStateOf {
            val position = positionMs?.value ?: return@derivedStateOf -1
            lyrics.lines.indexOfLast { it.startMs <= position }
        }
    }
    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                lyrics.loading -> CircularProgressIndicator(strokeWidth = 2.5.dp)
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
                            val distance = if (activeIndex >= 0) abs(index - activeIndex) else 10
                            val targetAlpha = when {
                                active -> 1.0f
                                distance == 1 -> 0.65f
                                distance == 2 -> 0.40f
                                else -> 0.25f
                            }
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .onGloballyPositioned { heights[index] = it.size.height }
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable(enabled = clickable) { onSeek(line.startMs) }
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    line.text,
                                    textAlign = TextAlign.Center,
                                    maxLines = Int.MAX_VALUE,
                                    softWrap = true,
                                    fontSize = if (active) 22.sp else 16.sp,
                                    lineHeight = if (active) 30.sp else 24.sp,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                    color = if (active) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = targetAlpha)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(centerPad))
                    }
                }
            }
        }

        // 悬浮“返回封面”轻量磨砂胶囊按钮
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 18.dp, bottom = 12.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
                .border(
                    0.8.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                    RoundedCornerShape(18.dp),
                )
                .bounceClick(scaleDown = 0.94f, onClick = onBackToCover)
                .padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回封面",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = "封面",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun InfoBadge(
    text: String,
    color: Color,
    onClick: (() -> Unit)? = null,
) {
    val modifier = Modifier
        .padding(end = 6.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(color.copy(alpha = 0.14f))
        .border(0.6.dp, color.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
        .let { m -> if (onClick != null) m.bounceClick(scaleDown = 0.92f, onClick = onClick) else m }
        .padding(horizontal = 7.dp, vertical = 2.dp)

    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        color = color,
        modifier = modifier,
    )
}

/** 固定高度的歌词窗：自动跟随、不可手动滚动；点词 seek，纵向滑动留给整页切歌。 */
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
            lyrics.loading -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
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
                LaunchedEffect(activeIndex) {
                    if (activeIndex < 0 || activeIndex >= heights.size) return@LaunchedEffect
                    withFrameNanos { }
                    withFrameNanos { }
                    var before = 0
                    for (i in 0 until activeIndex) before += heights[i]
                    scrollState.animateScrollTo((before + heights[activeIndex] / 2).coerceAtLeast(0))
                }
                Column(
                    Modifier.fillMaxSize().verticalScroll(scrollState, enabled = false),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(centerPad))
                    lyrics.lines.forEachIndexed { index, line ->
                        val active = index == activeIndex
                        val distance = if (activeIndex >= 0) abs(index - activeIndex) else 10
                        val targetAlpha = when {
                            active -> 1.0f
                            distance == 1 -> 0.58f
                            else -> 0.30f
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { heights[index] = it.size.height }
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = clickable) { onLineClick(line.startMs) }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                line.text,
                                textAlign = TextAlign.Center,
                                maxLines = Int.MAX_VALUE,
                                softWrap = true,
                                fontSize = if (active) 17.sp else 13.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                color = if (active) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = targetAlpha)
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
    var chosenMinutes by remember { mutableStateOf<Int?>(30) }
    var showCustom by remember { mutableStateOf(false) }
    var customMinutes by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth()) {
        SectionLabel("定时关闭", subtitle = "到点后自动暂停播放")
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 6.dp),
        ) {
            listOf(15, 30, 45, 60).forEach { minutes ->
                SelectablePill(
                    label = "${minutes}m",
                    selected = !showCustom && chosenMinutes == minutes,
                    onClick = {
                        showCustom = false
                        chosenMinutes = minutes
                        customMinutes = ""
                    },
                )
            }
            SelectablePill(
                label = "自定义",
                selected = showCustom,
                onClick = { showCustom = !showCustom },
            )
        }

        AnimatedVisibility(visible = showCustom) {
            OutlinedTextField(
                value = customMinutes,
                onValueChange = { customMinutes = it },
                label = { Text("自定义分钟数（1-720）") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
        }

        Spacer(Modifier.height(14.dp))

        if (sleepRemainingMs > 0) {
            IslandCard(
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            text = "倒计时运行中",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = formatCountdown(sleepRemainingMs),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    SelectablePill(
                        label = "取消定时",
                        selected = true,
                        onClick = onCancel,
                    )
                }
            }
        } else {
            val validMinutes = (if (showCustom) customMinutes.trim().toIntOrNull() else chosenMinutes)
                ?.takeIf { it in 1..720 }
            Button(
                onClick = { if (validMinutes != null) onStart(validMinutes) },
                enabled = validMinutes != null,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (validMinutes != null) "开启 $validMinutes 分钟定时关闭" else "开启定时关闭",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

internal fun formatCountdown(ms: Long): String {
    val seconds = ((ms - 1_000L).coerceAtLeast(0L)) / 1_000L
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}
