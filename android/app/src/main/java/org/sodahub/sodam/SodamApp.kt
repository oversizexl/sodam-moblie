package org.sodahub.sodam

import android.content.ComponentName
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.BackHandler
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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.sodahub.sodam.ui.AboutScreen
import org.sodahub.sodam.ui.CachedAudio
import org.sodahub.sodam.ui.DiscoverScreen
import org.sodahub.sodam.ui.DiscoverState
import org.sodahub.sodam.ui.HOME_RECOMMEND
import org.sodahub.sodam.ui.LikedTracks
import org.sodahub.sodam.ui.LoginScreen
import org.sodahub.sodam.ui.PlayerBar
import org.sodahub.sodam.ui.PlayerHomeScreen
import org.sodahub.sodam.ui.SearchScreen
import org.sodahub.sodam.ui.SettingsScreen
import org.sodahub.sodam.ui.SodamTheme
import org.sodahub.sodam.ui.ThemeScreen
import org.sodahub.sodam.ui.TrackDownloader
import org.sodahub.sodam.ui.TrackLinkDetailScreen
import org.sodahub.sodam.ui.UI_STYLE_CLEAN
import org.sodahub.sodam.ui.SavedScene
import org.sodahub.sodam.ui.HapticLevel
import org.sodahub.sodam.ui.PlayButtonStyle
import org.sodahub.sodam.ui.UpdateProxy
import org.sodahub.sodam.ui.clearCookie
import org.sodahub.sodam.ui.clearSavedScene
import org.sodahub.sodam.ui.nextMode
import org.sodahub.sodam.ui.paletteStyleLabel
import org.sodahub.sodam.ui.readBackdropAlpha
import org.sodahub.sodam.ui.readCacheLimit
import org.sodahub.sodam.ui.readCookie
import org.sodahub.sodam.ui.readGuideDebug
import org.sodahub.sodam.ui.readGuideSeen
import org.sodahub.sodam.ui.readHapticLevel
import org.sodahub.sodam.ui.readHomeQueue
import org.sodahub.sodam.ui.readPageCount
import org.sodahub.sodam.ui.readPlayButtonStyle
import org.sodahub.sodam.ui.readPlayMode
import org.sodahub.sodam.ui.readQuality
import org.sodahub.sodam.ui.readSavedScene
import org.sodahub.sodam.ui.readTheme
import org.sodahub.sodam.ui.readThemeColor
import org.sodahub.sodam.ui.readThemeStyle
import org.sodahub.sodam.ui.readUiStyle
import org.sodahub.sodam.ui.readUpdateProxy
import org.sodahub.sodam.ui.readable
import org.sodahub.sodam.ui.themeColorLabel
import org.sodahub.sodam.ui.themeLabel
import org.sodahub.sodam.ui.uiStyleLabel
import org.sodahub.sodam.ui.writeBackdropAlpha
import org.sodahub.sodam.ui.writeGuideDebug
import org.sodahub.sodam.ui.writeGuideSeen
import org.sodahub.sodam.ui.writeHapticLevel
import org.sodahub.sodam.ui.writeHomeQueue
import org.sodahub.sodam.ui.writePageCount
import org.sodahub.sodam.ui.writePlayButtonStyle
import org.sodahub.sodam.ui.writePlayMode
import org.sodahub.sodam.ui.writeSavedScene
import org.sodahub.sodam.ui.writeTheme
import org.sodahub.sodam.ui.writeThemeColor
import org.sodahub.sodam.ui.writeThemeStyle
import org.sodahub.sodam.ui.writeUiStyle
import org.sodahub.sodam.ui.writeUpdateProxy
import uniffi.sodam_ffi.AccountInfo
import uniffi.sodam_ffi.SodaClient
import uniffi.sodam_ffi.SodaException
import uniffi.sodam_ffi.Scene
import uniffi.sodam_ffi.Track

private enum class RootTab(val label: String) {
    Home("首页"),
    Discover("发现"),
    Settings("设置"),
}

private sealed interface RootPage {
    data object Main : RootPage
    data object Login : RootPage
    data object Theme : RootPage
    data object About : RootPage
    data object Search : RootPage
    data class TrackAlbumLookup(val track: Track) : RootPage
    data class TrackArtistLookup(val track: Track) : RootPage
}

/**
 * 应用根：初始化客户端、恢复登录态、底部三个 Tab。
 *
 * 首页是沉浸式全屏播放页（上下滑切歌）；发现/设置保留底部播放条，点它回首页。
 */
@Composable
fun SodamApp(
    deviceFile: String,
    cookieFile: String,
    cacheDir: String,
    qualityFile: String,
    cacheLimitFile: String,
    pagesFile: String,
    themeFile: String,
    themeColorFile: String,
    themeStyleFile: String,
    uiStyleFile: String,
    guideFile: String,
    guideDebugFile: String,
    likedFile: String,
    homeQueueFile: String,
    playModeFile: String,
    sceneFile: String,
    downloadDirFile: String,
    hapticFile: String,
    backdropAlphaFile: String,
    playButtonStyleFile: String,
    updateProxyFile: String,
) {
    var themeMode by remember { mutableStateOf(readTheme(themeFile)) }
    var themeColor by remember { mutableStateOf(readThemeColor(themeColorFile)) }
    var paletteStyle by remember { mutableStateOf(readThemeStyle(themeStyleFile)) }
    var uiStyle by remember { mutableStateOf(readUiStyle(uiStyleFile)) }
    var hapticLevel by remember { mutableStateOf(readHapticLevel(hapticFile)) }
    var backdropAlpha by remember { mutableFloatStateOf(readBackdropAlpha(backdropAlphaFile)) }
    var playButtonStyle by remember { mutableStateOf(readPlayButtonStyle(playButtonStyleFile)) }
    var updateProxy by remember { mutableStateOf(readUpdateProxy(updateProxyFile)) }

    SodamTheme(themeMode, themeColor, paletteStyle, uiStyle) {
        var client by remember { mutableStateOf<SodaClient?>(null) }
        var initError by remember { mutableStateOf("") }
        var initAttempt by remember { mutableIntStateOf(0) }
        var account by remember { mutableStateOf<AccountInfo?>(null) }
        var tab by remember { mutableStateOf(RootTab.Home) }
        var page by remember { mutableStateOf<RootPage>(RootPage.Main) }
        var pageCount by remember { mutableStateOf(readPageCount(pagesFile)) }
        var playMode by remember {
            val restored = readPlayMode(playModeFile)
            PlaybackHub.mode = restored
            mutableStateOf(restored)
        }
        var savedScene by remember { mutableStateOf(readSavedScene(sceneFile)) }
        val discoverState = remember { DiscoverState() }
        val scope = rememberCoroutineScope()

        var controller by remember { mutableStateOf<MediaController?>(null) }
        var isPlaying by remember { mutableStateOf(false) }
        val positionState = remember { mutableLongStateOf(0L) }
        val durationState = remember { mutableLongStateOf(0L) }
        val currentIndex by PlaybackHub.indexFlow.collectAsState()
        val qualities by PlaybackHub.qualitiesFlow.collectAsState()
        val currentPreview by PlaybackHub.previewFlow.collectAsState()
        val loading by PlaybackHub.loadingFlow.collectAsState()
        var homeStatus by remember { mutableStateOf("") }
        var homeRefreshing by remember { mutableStateOf(false) }
        var autoPlayPending by remember { mutableStateOf(false) }
        var currentSceneName by remember { mutableStateOf<String?>(null) }
        var scenes by remember { mutableStateOf<List<Scene>>(emptyList()) }
        var scenesLoading by remember { mutableStateOf(false) }
        var sceneSwitching by remember { mutableStateOf(false) }
        // 登录态校验完成前不发需要身份的请求；本地缓存起播不受它影响。
        var sessionReady by remember { mutableStateOf(false) }
        // 是否有可用登录态（本机存有 cookie 且未被服务端判失效）。未登录时不拉推荐/歌单，改引导登录。
        var hasSession by remember { mutableStateOf(false) }
        // 首批推荐（或场景）是否已落定，用来延后非关键的启动预热。
        var homeReady by remember { mutableStateOf(false) }
        // 当前队列是否为“优先起播”的本地缓存临时队列；是则推荐到达时追加而非替换。
        var bootstrapActive by remember { mutableStateOf(false) }
        var guideDebug by remember { mutableStateOf(readGuideDebug(guideDebugFile)) }
        var showGuide by remember {
            mutableStateOf(!readGuideSeen(guideFile) || readGuideDebug(guideDebugFile))
        }
        var sleepDeadline by remember { mutableStateOf<Long?>(null) }
        var sleepRemainingMs by remember { mutableLongStateOf(0L) }

        val context = LocalContext.current
        @Suppress("DEPRECATION")
        val appVersion = remember(context) {
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull().orEmpty().ifBlank { "0.1.0" }
        }

        /**
         * 冷启动优先出声：从上次落盘的首页队列快照里挑 1–2 首已缓存的曲目立即起播。
         * 只读磁盘，不依赖网络与登录校验；推荐随后异步补到队列后面。
         */
        suspend fun playCachedFirst(active: SodaClient): Boolean {
            CachedAudio.init(cacheDir)
            val snapshot = withContext(Dispatchers.IO) { readHomeQueue(homeQueueFile) }
            if (snapshot.isEmpty()) return false
            CachedAudio.refresh()
            val cached = CachedAudio.cachedIds
            if (cached.isEmpty()) return false
            // 优先播“最近缓存”的一首：按音频文件最近落盘时间取最大者，每次只起播 1 首。
            val at = CachedAudio.cachedAt
            val pick = snapshot.asSequence()
                .filter { it.id in cached }
                .maxByOrNull { at[it.id] ?: 0L }
                ?: return false
            PlaybackHub.client = active
            PlaybackHub.queue = listOf(pick)
            PlaybackHub.mode = playMode
            // 缓存队列不参与自动续拉；等推荐回来再接管。
            PlaybackHub.recommendRequest = null
            bootstrapActive = true
            autoPlayPending = true
            return true
        }

        // 缓存索引先建好（缓存曲目的「已缓存」标签与首批起播都要用）。
        LaunchedEffect(Unit) {
            CachedAudio.init(cacheDir)
            CachedAudio.refresh()
        }

        // 从手机文件管理器等外部应用返回前台时，重新核验已下载物理文件是否存在。
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    scope.launch { TrackDownloader.reconcileLocalFiles() }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        LaunchedEffect(initAttempt) {
            initError = ""
            try {
                // 如果后台服务仍在同一进程内持有活跃队列与播放下标（例如系统配置变动导致 Activity 重建），
                // 直接沿用原有客户端与队列，绝不重置队列或打断正在播放的歌曲。
                val existingClient = PlaybackHub.client
                val hasActiveHub = existingClient != null &&
                    PlaybackHub.queue.isNotEmpty() &&
                    PlaybackHub.index in PlaybackHub.queue.indices
                val created = existingClient ?: withContext(Dispatchers.IO) {
                    SodaClient(deviceFile, cacheDir)
                }
                withContext(Dispatchers.IO) {
                    created.setQualityPreference(readQuality(qualityFile))
                    created.setCacheLimit(readCacheLimit(cacheLimitFile))
                }
                // 「喜欢」写服务端：拿到 client 后挂上；登录校验后再异步拉一次已喜欢曲目。
                LikedTracks.attach(created)
                TrackDownloader.init(context, created, downloadDirFile)
                if (hasActiveHub) {
                    bootstrapActive = false
                    autoPlayPending = false
                    homeReady = true
                    homeRefreshing = false
                } else {
                    // 先本地起播：只读磁盘，不挡网络与登录校验，用户几乎立刻有声音。
                    val booted = playCachedFirst(created)
                    // 没有可秒开的缓存时先占住加载态，避免闪一帧空状态。
                    if (!booted) homeRefreshing = true
                }
                client = created
                // 登录态校验放后台：只回填账号信息，不再阻塞首屏与起播。
                val saved = readCookie(cookieFile)
                hasSession = saved != null
                if (saved != null) {
                    try {
                        account = withContext(Dispatchers.IO) { created.verifyCookie(saved) }
                        hasSession = true
                    } catch (e: SodaException.Auth) {
                        // 服务端明确判定登录态无效（1000016/1000006）：清掉本机凭据。
                        withContext(Dispatchers.IO) {
                            runCatching { created.logout() }
                            clearCookie(cookieFile)
                        }
                        hasSession = false
                    } catch (e: Exception) {
                        // 网络抖动、签名器临时 5xx、超时：保留 cookie，本次按已有登录态继续，
                        // 下次启动再校验。临时故障绝不能当登出，否则推荐/发现页会一起拉不出来。
                    }
                }
                sessionReady = true
                launch { LikedTracks.reload() }
            } catch (e: SodaException) {
                initError = "初始化失败：${e.readable()}"
            } catch (e: Exception) {
                initError = "初始化失败：${e.message ?: e::class.java.simpleName}"
            }
        }
        // 注意：不调用 client.destroy()。播放服务在 Activity 销毁后仍可能持有同一个
        // SodaClient（PlaybackHub.client），提前释放会造成原生指针悬空。

        DisposableEffect(Unit) {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val future = MediaController.Builder(context, token).buildAsync()
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    durationState.longValue = controller?.duration?.coerceAtLeast(0L) ?: 0L
                }
            }
            future.addListener(
                {
                    try {
                        val connected = future.get()
                        controller = connected
                        connected.addListener(listener)
                        isPlaying = connected.isPlaying
                        durationState.longValue = connected.duration.coerceAtLeast(0L)
                    } catch (_: Exception) {
                        // 连接失败时首页仍可浏览，点播会给出提示。
                    }
                },
                ContextCompat.getMainExecutor(context),
            )
            onDispose {
                controller?.removeListener(listener)
                MediaController.releaseFuture(future)
            }
        }

        val hubQueue by PlaybackHub.queueFlow.collectAsState()

        // 落盘首页队列快照：下次冷启动据此挑已缓存曲目优先起播。
        // 临时缓存队列（bootstrapActive）不写，避免用 1–2 首覆盖掉完整快照。
        LaunchedEffect(hubQueue, bootstrapActive) {
            if (bootstrapActive || hubQueue.isEmpty()) return@LaunchedEffect
            withContext(Dispatchers.IO) { writeHomeQueue(homeQueueFile, hubQueue) }
        }

        // 切歌瞬间先归零，避免进度条停在上一首末尾再跳回开头。
        LaunchedEffect(currentIndex) {
            positionState.longValue = 0L
            durationState.longValue = 0L
        }

        // 解析/预取落盘后刷新缓存索引，让「已缓存」标签及时出现。
        LaunchedEffect(currentIndex, loading) {
            if (!loading) CachedAudio.refresh()
        }

        // 只刷新进度/时长，并把 State 对象往下传：每 200ms 只重组真正读它的控件。
        LaunchedEffect(Unit) {
            while (true) {
                val active = controller
                if (active != null) {
                    positionState.longValue = active.currentPosition.coerceAtLeast(0L)
                    durationState.longValue = active.duration.coerceAtLeast(0L)
                }
                delay(if (active?.isPlaying == true) 200 else 500)
            }
        }

        LaunchedEffect(pageCount) {
            PlaybackHub.pagesPerFetch = pageCount
        }

        LaunchedEffect(sleepDeadline) {
            val deadline = sleepDeadline
            if (deadline == null) {
                sleepRemainingMs = 0L
                return@LaunchedEffect
            }
            while (true) {
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0L) {
                    controller?.pause()
                    sleepRemainingMs = 0L
                    sleepDeadline = null
                    break
                }
                sleepRemainingMs = left
                delay(1_000)
            }
        }

        LaunchedEffect(homeStatus) {
            if (homeStatus.isNotEmpty()) {
                delay(3_000)
                homeStatus = ""
            }
        }

        fun playIndex(index: Int) {
            controller?.sendCustomCommand(
                SessionCommand(CMD_PLAY_INDEX, Bundle.EMPTY),
                Bundle().apply { putInt(ARG_INDEX, index) },
            )
        }

        /** 首页滑到队列末尾时预拉下一批：只扩充队列，不打断当前播放。 */
        fun loadMoreQueue(active: SodaClient) {
            val request = PlaybackHub.recommendRequest ?: return
            if (!PlaybackHub.beginAppend()) return
            scope.launch {
                try {
                    val more = withContext(Dispatchers.IO) {
                        buildList {
                            repeat(pageCount.coerceAtLeast(1)) {
                                addAll(active.recommend(request.first, request.second))
                            }
                        }
                    }
                    val now = PlaybackHub.queue
                    val ids = now.mapTo(HashSet()) { it.id }
                    val fresh = more.distinctBy { it.id }.filter { it.id !in ids }
                    if (fresh.isNotEmpty()) {
                        PlaybackHub.queue = now + fresh
                        // 续拉进来的新歌同样异步预取前几首。
                        active.prefetch(fresh.take(3).map { it.id })
                    }
                } catch (_: SodaException) {
                    // 拉取失败就保持原队列，下次滑到末尾再试。
                } finally {
                    PlaybackHub.endAppend()
                }
            }
        }

        fun startPlay(queue: List<Track>?, index: Int, request: Pair<Long, String>?) {            val active = client ?: return
            val tracks = queue ?: return
            if (controller == null) {
                homeStatus = "播放器还在连接，请稍后再点"
                return
            }
            PlaybackHub.client = active
            PlaybackHub.queue = tracks
            PlaybackHub.mode = playMode
            PlaybackHub.recommendRequest = request
            currentSceneName = null
            bootstrapActive = false
            // 预取交给播放服务：切歌成功后解析下一首并预取再往后两首。
            playIndex(index)
        }

        fun loadScenes() {
            if (scenesLoading || scenes.isNotEmpty()) return
            val active = client ?: return
            scenesLoading = true
            scope.launch {
                try {
                    scenes = withContext(Dispatchers.IO) { active.feedScenes() }
                } catch (e: SodaException) {
                    homeStatus = e.readable()
                } finally {
                    scenesLoading = false
                }
            }
        }

        fun startScene(sceneModeId: Long, subQueueType: String, text: String, keepBootstrap: Boolean = false) {
            if (sceneSwitching) return
            val active = client ?: return
            sceneSwitching = true
            scope.launch {
                try {
                    val list = withContext(Dispatchers.IO) {
                        active.recommend(sceneModeId, subQueueType)
                    }.distinctBy { it.id }
                    if (list.isEmpty()) {
                        homeStatus = "《$text》没有可播放的歌曲"
                    } else {
                        // 冷启动恢复场景时保留正在播的缓存曲目；手动切场景则整队替换。
                        val base = if (keepBootstrap && bootstrapActive) PlaybackHub.queue else emptyList()
                        val baseIds = base.mapTo(HashSet()) { it.id }
                        val fresh = list.filter { it.id !in baseIds }
                        PlaybackHub.client = active
                        PlaybackHub.queue = if (base.isEmpty()) fresh else base + fresh
                        PlaybackHub.mode = playMode
                        PlaybackHub.recommendRequest = sceneModeId to subQueueType
                        currentSceneName = text
                        savedScene = SavedScene(sceneModeId, subQueueType, text)
                        writeSavedScene(sceneFile, sceneModeId, subQueueType, text)
                        if (!keepBootstrap || PlaybackHub.index < 0) autoPlayPending = true
                        bootstrapActive = false
                        // 场景歌曲同样先异步预取前几首。
                        val upcoming = fresh.take(3).map { it.id }
                        if (upcoming.isNotEmpty()) active.prefetch(upcoming)
                    }
                } catch (e: SodaException) {
                    homeStatus = e.readable()
                } finally {
                    homeReady = true
                    homeRefreshing = false
                    sceneSwitching = false
                }
            }
        }

        /** 从首页切换听歌模式：取该场景的首批歌曲，整队替换并开始播放。 */
        fun selectScene(scene: Scene) {
            startScene(scene.sceneModeId, scene.subQueueType, scene.text)
        }

        /**
         * 拉取首页推荐。`background = true` 用于冷启动：不显示加载态，也不打断正在播放的
         * 本地缓存曲目；推荐去重后接到缓存队列后面（下标不变、播放不断）。
         */
        fun refreshHome(active: SodaClient, background: Boolean = false) {
            if (!background && homeRefreshing) return
            if (!PlaybackHub.beginAppend()) {
                // 冷启动预占的加载态不能让并发占用卡住不放。
                if (background) {
                    homeRefreshing = false
                    homeReady = true
                }
                return
            }
            if (!background) {
                homeRefreshing = true
                homeStatus = "正在加载…"
            }
            scope.launch {
                try {
                    val list = withContext(Dispatchers.IO) {
                        buildList {
                            repeat(pageCount.coerceAtLeast(1)) {
                                addAll(active.recommend(HOME_RECOMMEND.first, HOME_RECOMMEND.second))
                            }
                        }
                    }.distinctBy { it.id }
                    if (list.isEmpty()) {
                        homeStatus = "没有推荐（可能需要登录）"
                    } else {
                        // 缓存曲目正在播时把推荐去重接到其后：当前下标不变、播放不断。
                        val base = if (bootstrapActive) PlaybackHub.queue else emptyList()
                        val baseIds = base.mapTo(HashSet()) { it.id }
                        val fresh = list.filter { it.id !in baseIds }
                        PlaybackHub.client = active
                        PlaybackHub.queue = if (base.isEmpty()) fresh else base + fresh
                        PlaybackHub.mode = playMode
                        PlaybackHub.recommendRequest = HOME_RECOMMEND
                        currentSceneName = null
                        savedScene = null
                        clearSavedScene(sceneFile)
                        if (PlaybackHub.index < 0) autoPlayPending = true
                        bootstrapActive = false
                        // 推荐一到就异步预取前几首：滑过去时直接命中缓存，不必现下载等十几秒。
                        val upcoming = fresh.take(3).map { it.id }
                        if (upcoming.isNotEmpty()) active.prefetch(upcoming)
                        homeStatus = "推荐 ${PlaybackHub.queue.size} 首"
                    }
                } catch (e: SodaException) {
                    homeStatus = e.readable()
                } finally {
                    homeReady = true
                    PlaybackHub.endAppend()
                    homeRefreshing = false
                }
            }
        }

        // 队列就绪且控制器连上后，自动从第一首开始播放。
        LaunchedEffect(autoPlayPending, controller, hubQueue.size) {
            if (autoPlayPending && controller != null && hubQueue.isNotEmpty()) {
                autoPlayPending = false
                playIndex(0)
            }
        }

        fun togglePlayback() {
            controller?.let { active ->
                if (active.isPlaying) {
                    active.pause()
                } else {
                    if (active.playbackState == Player.STATE_ENDED) active.seekTo(0)
                    active.play()
                }
            }
        }

        fun logout(active: SodaClient) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching { active.logout() }
                    clearCookie(cookieFile)
                }
                account = null
                hasSession = false
                LikedTracks.attach(null)
                // 重置发现页“已加载”标记，重新登录后能再次拉取。
                discoverState.recommendedLoaded = false
                discoverState.playlistsLoaded = false
                discoverState.status = ""
                homeStatus = "已退出登录"
            }
        }

        // 启动自动加载：上次选过听歌场景就继续该场景，否则拉首页推荐。
        // 等登录校验（sessionReady）完成、且确有登录态再发身份相关请求；此时缓存曲目已经在播。
        // 若当前已有完整队列（如后台正在播歌接管），则不再重新加载覆盖队列。
        LaunchedEffect(client, sessionReady, hasSession) {
            val active = client ?: return@LaunchedEffect
            if (!sessionReady || !hasSession || homeReady) return@LaunchedEffect
            val restored = savedScene
            if (restored != null) {
                startScene(
                    restored.sceneModeId,
                    restored.subQueueType,
                    restored.text,
                    keepBootstrap = true,
                )
            } else {
                refreshHome(active, background = true)
            }
        }

        // 发现页推荐歌单：登录态就绪即独立预热；与“我的歌单”完全解耦不互锁。
        LaunchedEffect(client, sessionReady, hasSession) {
            val active = client ?: return@LaunchedEffect
            if (!sessionReady || !hasSession) return@LaunchedEffect
            if (discoverState.recommendedLoaded || discoverState.recommendedBusy) return@LaunchedEffect
            discoverState.recommendedBusy = true
            try {
                discoverState.recommended = withContext(Dispatchers.IO) {
                    active.discoverPlaylists()
                }
                discoverState.recommendedLoaded = true
            } catch (e: SodaException) {
                discoverState.status = e.readable()
            } finally {
                discoverState.recommendedBusy = false
            }
        }

        // 我的歌单（含「我喜欢的音乐」）：登录态就绪后立刻独立并行预加载，打开发现页直接展示。
        LaunchedEffect(client, sessionReady, hasSession) {
            val active = client ?: return@LaunchedEffect
            if (!sessionReady || !hasSession) return@LaunchedEffect
            if (discoverState.playlistsLoaded || discoverState.playlistsBusy) return@LaunchedEffect
            discoverState.playlistsBusy = true
            try {
                val list = withContext(Dispatchers.IO) { active.myPlaylists() }
                discoverState.playlists = list
                if (list.isNotEmpty()) {
                    discoverState.playlistsLoaded = true
                }
            } catch (e: SodaException) {
                discoverState.status = e.readable()
            } finally {
                discoverState.playlistsBusy = false
            }
        }

        // 听歌模式列表同样在登录态就绪后独立预热；未登录则不发请求。
        LaunchedEffect(client, sessionReady, hasSession) {
            val active = client ?: return@LaunchedEffect
            if (!sessionReady || !hasSession) return@LaunchedEffect
            if (scenes.isNotEmpty() || scenesLoading) return@LaunchedEffect
            scenesLoading = true
            try {
                scenes = withContext(Dispatchers.IO) { active.feedScenes() }
            } catch (e: SodaException) {
                homeStatus = e.readable()
            } finally {
                scenesLoading = false
            }
        }

        // 服务端自动续拉后，首页页数跟着长（pager 自动扩容）。
        val currentTrack = hubQueue.getOrNull(currentIndex)

        val active = client
        if (active == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (initError.isEmpty()) {
                        CircularProgressIndicator()
                        Text("正在启动…", Modifier.padding(top = 12.dp))
                    } else {
                        Text(initError, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { initAttempt++ }) { Text("重试") }
                    }
                }
            }
            return@SodamTheme
        }

        when (val currentPage = page) {
            RootPage.Login -> {
                LoginScreen(
                    client = active,
                    cookieFile = cookieFile,
                    onLoggedIn = { info ->
                        account = info
                        hasSession = true
                        page = RootPage.Main
                        tab = RootTab.Home
                        // 登录后重置发现页“已加载”标记，让推荐歌单重新拉取。
                        discoverState.recommendedLoaded = false
                        discoverState.playlistsLoaded = false
                        discoverState.status = ""
                        // 登录后重新拉一次已喜欢曲目，爱心状态才对得上。
                        LikedTracks.attach(active)
                        scope.launch { LikedTracks.reload() }
                        refreshHome(active)
                    },
                    onBack = { page = RootPage.Main },
                )
                return@SodamTheme
            }

            RootPage.Theme -> {
                ThemeScreen(
                    themeMode = themeMode,
                    themeColorHex = themeColor,
                    paletteStyle = paletteStyle,
                    uiStyle = uiStyle,
                    onModeChange = {
                        themeMode = it
                        writeTheme(themeFile, it)
                    },
                    onColorChange = {
                        themeColor = it
                        writeThemeColor(themeColorFile, it)
                    },
                    onPaletteStyleChange = {
                        paletteStyle = it
                        writeThemeStyle(themeStyleFile, it)
                    },
                    onUiStyleChange = {
                        uiStyle = it
                        writeUiStyle(uiStyleFile, it)
                    },
                    onBack = { page = RootPage.Main },
                )
                return@SodamTheme
            }

            RootPage.About -> {
                AboutScreen(
                    appVersion = appVersion,
                    updateProxy = updateProxy,
                    onUpdateProxyChange = { next ->
                        updateProxy = next
                        writeUpdateProxy(updateProxyFile, next)
                    },
                    onBack = { page = RootPage.Main },
                )
                return@SodamTheme
            }

            RootPage.Search -> {
                SearchScreen(
                    client = active,
                    scope = scope,
                    onBack = { page = RootPage.Main },
                    onPlay = { queue, index ->
                        // 搜索/详情页的队列播完后继续接首页推荐，别在最后一首停住。
                        startPlay(queue, index, HOME_RECOMMEND)
                        page = RootPage.Main
                        tab = RootTab.Home
                    },
                )
                return@SodamTheme
            }

            is RootPage.TrackAlbumLookup -> {
                TrackLinkDetailScreen(
                    client = active,
                    track = currentPage.track,
                    lookupAlbum = true,
                    onBack = { page = RootPage.Main },
                    onPlay = { queue, index ->
                        startPlay(queue, index, HOME_RECOMMEND)
                        page = RootPage.Main
                        tab = RootTab.Home
                    },
                )
                return@SodamTheme
            }

            is RootPage.TrackArtistLookup -> {
                TrackLinkDetailScreen(
                    client = active,
                    track = currentPage.track,
                    lookupAlbum = false,
                    onBack = { page = RootPage.Main },
                    onPlay = { queue, index ->
                        startPlay(queue, index, HOME_RECOMMEND)
                        page = RootPage.Main
                        tab = RootTab.Home
                    },
                )
                return@SodamTheme
            }

            RootPage.Main -> Unit
        }

        // 非首页 Tab 上按返回：先回首页，再按一次才退出 App。
        BackHandler(enabled = tab != RootTab.Home) { tab = RootTab.Home }

        val isCleanMode = uiStyle == UI_STYLE_CLEAN
        val navBarInsetsBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val miniTrack = if (tab != RootTab.Home) currentTrack else null
        val hasMiniPlayer = miniTrack != null
        val homeBottomInset = (if (isCleanMode) 76.dp else 82.dp) + navBarInsetsBottom
        val subPageBottomInset = (if (hasMiniPlayer) 156.dp else 88.dp) + navBarInsetsBottom

        Box(Modifier.fillMaxSize()) {
            Crossfade(
                targetState = tab,
                animationSpec = tween(durationMillis = 180),
                label = "root_tab_crossfade",
                modifier = Modifier.fillMaxSize(),
            ) { activeTab ->
                when (activeTab) {
                    RootTab.Home -> PlayerHomeScreen(
                        client = active,
                        queue = hubQueue,
                        currentIndex = currentIndex,
                        currentTrackId = currentTrack?.id,
                        isPlaying = isPlaying,
                        positionMs = positionState,
                        durationMs = durationState,
                        qualities = qualities,
                        preview = currentPreview,
                        playMode = playMode,
                        loading = loading,
                        refreshing = homeRefreshing,
                        status = homeStatus,
                        sleepRemainingMs = sleepRemainingMs,
                        scenes = scenes,
                        scenesLoading = scenesLoading,
                        currentSceneName = currentSceneName,
                        backdropAlpha = backdropAlpha,
                        hapticLevel = hapticLevel,
                        playButtonStyle = playButtonStyle,
                        bottomInset = homeBottomInset,
                        onLoadScenes = { loadScenes() },
                        onSelectScene = { scene -> selectScene(scene) },
                        onSelectRecommendMode = { refreshHome(active) },
                        onToggle = { togglePlayback() },
                        onSeek = { ms ->
                            controller?.seekTo(ms)
                            positionState.longValue = ms
                        },
                        onSelectIndex = { index -> playIndex(index) },
                        onSelectMode = { mode ->
                            playMode = mode
                            writePlayMode(playModeFile, mode)
                            controller?.sendCustomCommand(
                                SessionCommand(CMD_SET_MODE, Bundle.EMPTY),
                                Bundle().apply { putString(ARG_MODE, mode.name) },
                            )
                        },
                        onLoadMore = { loadMoreQueue(active) },
                        onRefresh = { refreshHome(active) },
                        onOpenSearch = { page = RootPage.Search },
                        onOpenAlbumForTrack = { t -> page = RootPage.TrackAlbumLookup(t) },
                        onOpenArtistForTrack = { t -> page = RootPage.TrackArtistLookup(t) },
                        onOpenSettings = { tab = RootTab.Settings },
                        onStartSleepTimer = { minutes ->
                            val total = minutes.coerceAtLeast(1) * 60_000L
                            sleepRemainingMs = total
                            sleepDeadline = SystemClock.elapsedRealtime() + total
                        },
                        onCancelSleepTimer = {
                            sleepDeadline = null
                            sleepRemainingMs = 0L
                        },
                        needsLogin = !hasSession,
                        onOpenLogin = { page = RootPage.Login },
                    )

                    RootTab.Discover -> DiscoverScreen(
                        state = discoverState,
                        client = active,
                        scope = scope,
                        loading = loading,
                        isPlaying = isPlaying,
                        currentTrackId = currentTrack?.id,
                        sessionReady = sessionReady,
                        bottomInset = subPageBottomInset,
                        onPlay = { queue, index, request ->
                            startPlay(queue, index, request)
                            tab = RootTab.Home
                        },
                        needsLogin = !hasSession,
                        onOpenLogin = { page = RootPage.Login },
                    )

                    RootTab.Settings -> SettingsScreen(
                        client = active,
                        account = account,
                        qualityFile = qualityFile,
                        cacheLimitFile = cacheLimitFile,
                        pageCount = pageCount,
                        themeSummary = "${themeLabel(themeMode)} · ${uiStyleLabel(uiStyle)} · ${themeColorLabel(themeColor)} · ${paletteStyleLabel(paletteStyle)}",
                        guideDebug = guideDebug,
                        backdropAlpha = backdropAlpha,
                        hapticLevel = hapticLevel,
                        playButtonStyle = playButtonStyle,
                        appVersion = appVersion,
                        bottomInset = subPageBottomInset,
                        onBackdropAlphaChange = { a ->
                            backdropAlpha = a
                            writeBackdropAlpha(backdropAlphaFile, a)
                        },
                        onHapticLevelChange = { lvl ->
                            hapticLevel = lvl
                            writeHapticLevel(hapticFile, lvl)
                        },
                        onPlayButtonStyleChange = { style ->
                            playButtonStyle = style
                            writePlayButtonStyle(playButtonStyleFile, style)
                        },
                        onQualityChanged = {
                            controller?.sendCustomCommand(
                                SessionCommand(CMD_REFRESH_QUALITY, Bundle.EMPTY),
                                Bundle.EMPTY,
                            )
                        },
                        onPageCountChange = { pages ->
                            pageCount = pages
                            writePageCount(pagesFile, pages)
                        },
                        onOpenLogin = { page = RootPage.Login },
                        onOpenTheme = { page = RootPage.Theme },
                        onOpenAbout = { page = RootPage.About },
                        onGuideDebugChange = { enabled ->
                            guideDebug = enabled
                            writeGuideDebug(guideDebugFile, enabled)
                        },
                        onLogout = { logout(active) },
                    )
                }
            }

            // 底部控制区：简洁模式为悬浮胶囊，Material You 为固定贴底栏
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .then(if (isCleanMode) Modifier.navigationBarsPadding() else Modifier),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (miniTrack != null) {
                    PlayerBar(
                        track = miniTrack,
                        isPlaying = isPlaying,
                        positionMs = positionState,
                        durationMs = durationState,
                        playButtonStyle = playButtonStyle,
                        onToggle = { togglePlayback() },
                        onPrevious = {
                            controller?.sendCustomCommand(
                                SessionCommand(CMD_PREVIOUS, Bundle.EMPTY),
                                Bundle.EMPTY,
                            )
                        },
                        onNext = {
                            controller?.sendCustomCommand(
                                SessionCommand(CMD_NEXT, Bundle.EMPTY),
                                Bundle.EMPTY,
                            )
                        },
                        onSeek = { ms ->
                            controller?.seekTo(ms)
                            positionState.longValue = ms
                        },
                        onOpen = { tab = RootTab.Home },
                    )
                }
                if (isCleanMode) {
                    FloatingCleanNavBar(
                        current = tab,
                        onSelect = { tab = it },
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 10.dp),
                    )
                } else {
                    MaterialFixedNavBar(
                        current = tab,
                        transparent = (tab == RootTab.Home),
                        onSelect = { tab = it },
                    )
                }
            }

            // 引导提示置灰层：放在最外层全屏 Box，100% 覆盖顶部状态栏、主内容与底部导航栏！
            if (tab == RootTab.Home && showGuide && currentTrack != null) {
                SwipeGuideOverlay(
                    onDismiss = {
                        showGuide = false
                        writeGuideSeen(guideFile)
                    },
                )
            }
        }
    }
}

/**
 * 简洁模式悬浮胶囊导航栏（NeriPlayer 液态悬浮交互）：
 * - 悬浮圆角胶囊 + 柔和投影与高光描边；
 * - 支持直接点击，以及按下/长按左右拖动手指连续滑动高亮游标并切换页面（带震动反馈）。
 */
@Composable
private fun FloatingCleanNavBar(
    current: RootTab,
    onSelect: (RootTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = RootTab.entries
    val selectedIndex = tabs.indexOf(current).coerceAtLeast(0)
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    var barWidthPx by remember { mutableIntStateOf(1) }
    var isDragging by remember { mutableStateOf(false) }
    var dragPointerX by remember { mutableFloatStateOf(0f) }
    val latestCurrent by rememberUpdatedState(current)
    val latestOnSelect by rememberUpdatedState(onSelect)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp)
            .shadow(
                elevation = 14.dp,
                shape = CircleShape,
                spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
            )
            .clip(CircleShape)
            .border(
                width = 0.8.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f),
                shape = CircleShape,
            ),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
        tonalElevation = 8.dp,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp)
                .onSizeChanged { barWidthPx = it.width.coerceAtLeast(1) }
                // 关键修复：key 只绑定 tabs.size，绝不绑定 current，避免切换 Tab 时重启手势协程导致卡死！
                .pointerInput(tabs.size) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startX = down.position.x
                        dragPointerX = startX
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    val targetIdx = ((change.position.x / barWidthPx.toFloat()) * tabs.size)
                                        .toInt()
                                        .coerceIn(0, tabs.lastIndex)
                                    if (tabs[targetIdx] != latestCurrent) {
                                        latestOnSelect(tabs[targetIdx])
                                    }
                                    break
                                }
                                val dx = kotlin.math.abs(change.position.x - startX)
                                val heldMs = change.uptimeMillis - down.uptimeMillis
                                if (isDragging || dx > 8f || heldMs > 120L) {
                                    if (!isDragging) {
                                        isDragging = true
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                    dragPointerX = change.position.x.coerceIn(0f, barWidthPx.toFloat())
                                    val hoverIdx = ((dragPointerX / barWidthPx.toFloat()) * tabs.size)
                                        .toInt()
                                        .coerceIn(0, tabs.lastIndex)
                                    if (tabs[hoverIdx] != latestCurrent) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        latestOnSelect(tabs[hoverIdx])
                                    }
                                    change.consume()
                                }
                            }
                        } finally {
                            isDragging = false
                        }
                    }
                },
        ) {
            val slotWidth = maxWidth / tabs.size
            val targetOffset = if (isDragging) {
                val fingerDp = with(density) { dragPointerX.toDp() }
                (fingerDp - slotWidth / 2).coerceIn(0.dp, maxWidth - slotWidth)
            } else {
                slotWidth * selectedIndex
            }
            val indicatorOffset by animateDpAsState(
                targetValue = targetOffset,
                animationSpec = if (isDragging) {
                    spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessHigh)
                } else {
                    spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)
                },
                label = "nav_indicator_x",
            )
            val indicatorScale by animateFloatAsState(
                targetValue = if (isDragging) 1.05f else 1f,
                animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium),
                label = "nav_indicator_scale",
            )

            // 液态滑动高亮药丸底板
            Box(
                modifier = Modifier
                    .offset(x = indicatorOffset)
                    .width(slotWidth)
                    .fillMaxHeight()
                    .graphicsLayer {
                        scaleX = indicatorScale
                        scaleY = indicatorScale
                    }
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = if (isDragging) 0.22f else 0.14f)),
            )

            // 前景图标与标签
            Row(modifier = Modifier.fillMaxSize()) {
                tabs.forEach { tabItem ->
                    val selected = current == tabItem
                    val iconRes = when (tabItem) {
                        RootTab.Home -> R.drawable.ic_home
                        RootTab.Discover -> R.drawable.ic_discover
                        RootTab.Settings -> R.drawable.ic_settings
                    }
                    val tint by animateColorAsState(
                        targetValue = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        label = "clean_tab_tint",
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            painter = painterResource(iconRes),
                            contentDescription = tabItem.label,
                            modifier = Modifier.size(20.dp),
                            tint = tint,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = tabItem.label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.5.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            ),
                            color = tint,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Material You 模式底部固定导航栏：标准 Material 3 贴底风格，不再悬浮。
 * 在首页时透明融于流光背景，非首页时展示统一容器底色。
 */
@Composable
private fun MaterialFixedNavBar(
    current: RootTab,
    transparent: Boolean = false,
    onSelect: (RootTab) -> Unit,
) {
    NavigationBar(
        containerColor = if (transparent) {
            Color.Transparent
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
        },
        tonalElevation = if (transparent) 0.dp else 6.dp,
    ) {
        RootTab.entries.forEach { tabItem ->
            val selected = current == tabItem
            val iconRes = when (tabItem) {
                RootTab.Home -> R.drawable.ic_home
                RootTab.Discover -> R.drawable.ic_discover
                RootTab.Settings -> R.drawable.ic_settings
            }
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(tabItem) },
                icon = {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = tabItem.label,
                        modifier = Modifier.size(22.dp),
                    )
                },
                label = {
                    Text(
                        text = tabItem.label,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                },
            )
        }
    }
}

/** 首次进首页的上下滑切歌提示：淡入，5 秒后淡出，点击任意位置立即关闭。 */
@Composable
private fun SwipeGuideOverlay(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf(false) }
    var closed by remember { mutableStateOf(false) }

    fun close() {
        if (closed) return
        closed = true
        shown = false
        scope.launch {
            delay(220)
            onDismiss()
        }
    }

    LaunchedEffect(Unit) {
        shown = true
        delay(5_000)
        close()
    }

    val pulse by rememberInfiniteTransition(label = "swipe-guide").animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "swipe-guide-alpha",
    )

    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(220)),
        exit = fadeOut(tween(180)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable { close() },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.graphicsLayer { alpha = pulse },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("↑", style = MaterialTheme.typography.displayMedium, color = Color.White)
                Text(
                    "上滑切换下一首",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                Text("↓", style = MaterialTheme.typography.displayMedium, color = Color.White)
                Text(
                    "下滑回到上一首",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(28.dp))
                Text(
                    "轻点任意位置开始",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
        }
    }
}
