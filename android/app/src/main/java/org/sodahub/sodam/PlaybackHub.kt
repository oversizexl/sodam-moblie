package org.sodahub.sodam

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import uniffi.sodam_ffi.SodaClient
import uniffi.sodam_ffi.Track
import java.util.concurrent.atomic.AtomicBoolean

enum class PlaybackMode(val label: String) {
    ListLoop("列表循环"),
    SingleLoop("单曲循环"),
    Shuffle("随机循环"),
}

/** 自定义会话命令：UI 与通知栏都通过它们驱动 PlaybackService。 */
const val CMD_PLAY_INDEX = "org.sodahub.sodam.PLAY_INDEX"
const val CMD_NEXT = "org.sodahub.sodam.NEXT"
const val CMD_PREVIOUS = "org.sodahub.sodam.PREVIOUS"
const val CMD_SET_MODE = "org.sodahub.sodam.SET_MODE"
const val ARG_INDEX = "index"
const val ARG_MODE = "mode"

/**
 * UI 与 [PlaybackService] 共享的播放状态。
 *
 * 队列、当前下标、播放模式都放这里：通知栏的下一首/上一首与“播完自动续拉推荐”都在服务里执行，
 * 不能依赖 Activity/Compose 还活着。
 */
object PlaybackHub {
    @Volatile
    var client: SodaClient? = null

    @Volatile
    private var queueValue: List<Track> = emptyList()

    private val queueState = MutableStateFlow<List<Track>>(emptyList())

    /** 队列变化时通知界面：服务里自动追加推荐后，首页列表要跟着变长。 */
    val queueFlow: StateFlow<List<Track>> = queueState

    var queue: List<Track>
        get() = queueValue
        set(value) {
            queueValue = value
            queueState.value = value
        }

    private val indexState = MutableStateFlow(-1)
    val indexFlow: StateFlow<Int> = indexState

    /** 当前播放的队列下标；写入后界面一帧内跟到新页。 */
    var index: Int
        get() = indexState.value
        set(value) {
            indexState.value = value
        }

    @Volatile
    var mode: PlaybackMode = PlaybackMode.ListLoop

    private val loadingState = MutableStateFlow(false)
    val loadingFlow: StateFlow<Boolean> = loadingState

    /** 正在下载/解密目标歌曲；期间保留旧曲目的展示与播放。 */
    var loading: Boolean
        get() = loadingState.value
        set(value) {
            loadingState.value = value
        }

    private val previewState = MutableStateFlow(false)
    val previewFlow: StateFlow<Boolean> = previewState

    /** 当前曲目是否只是试听片段。 */
    var preview: Boolean
        get() = previewState.value
        set(value) {
            previewState.value = value
        }

    /** 每首歌解析出来的音质标签，按曲目缓存，切歌时标签不闪烁。 */
    private val qualitiesState = MutableStateFlow<Map<String, String>>(emptyMap())
    val qualitiesFlow: StateFlow<Map<String, String>> = qualitiesState

    fun rememberQuality(trackId: String, quality: String) {
        if (quality.isBlank()) return
        qualitiesState.update { it + (trackId to quality) }
    }

    /** 服务端提前续拉 / 首页上拉 / 下拉刷新共用的互斥标记。 */
    private val appending = AtomicBoolean(false)

    /** 抢到续拉权返回 true；抢不到说明已有请求在路上，调用方别把它当作已接管。 */
    fun beginAppend(): Boolean = appending.compareAndSet(false, true)

    fun endAppend() {
        appending.set(false)
    }

    /** 每次续拉请求几页，由界面按用户设置的页数同步。 */
    @Volatile
    var pagesPerFetch: Int = 3

    /** 当前队列来自推荐时可自动追加；`null` 表示队列不可延长。 */
    @Volatile
    var recommendRequest: Pair<Long, String>? = null

    fun nextIndex(from: Int, shuffle: Boolean): Int? {
        if (queue.isEmpty()) return null
        if (queue.size == 1) return 0
        if (shuffle) {
            var pick = kotlin.random.Random.nextInt(queue.size)
            if (pick == from) pick = (pick + 1) % queue.size
            return pick
        }
        return ((if (from >= 0) from else -1) + 1) % queue.size
    }

    fun previousIndex(from: Int): Int {
        if (queue.isEmpty()) return -1
        if (queue.size == 1) return 0
        return ((if (from > 0) from else queue.size) - 1) % queue.size
    }
}
