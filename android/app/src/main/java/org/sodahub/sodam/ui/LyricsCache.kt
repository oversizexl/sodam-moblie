package org.sodahub.sodam.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uniffi.sodam_ffi.LyricLine
import uniffi.sodam_ffi.SodaClient
import uniffi.sodam_ffi.SodaException

/** 歌词随曲目缓存：进出播放页/全屏歌词都不重新请求。 */
internal object LyricsCache {
    private const val CAPACITY = 24
    private val entries = object : LinkedHashMap<String, List<LyricLine>>(CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, List<LyricLine>>?,
        ): Boolean = size > CAPACITY
    }

    /** 串行化拉取：邻居预取和页面组合撞上同一首时只请求一次。 */
    private val requests = Mutex()

    fun get(trackId: String): List<LyricLine>? = synchronized(entries) { entries[trackId] }

    fun put(trackId: String, lines: List<LyricLine>) {
        synchronized(entries) { entries[trackId] = lines }
    }

    /** 命中缓存直接返回；否则拉取并写入缓存。 */
    suspend fun ensure(client: SodaClient, trackId: String): List<LyricLine> {
        get(trackId)?.let { return it }
        return requests.withLock {
            get(trackId) ?: withContext(Dispatchers.IO) { client.lyrics(trackId) }
                .also { put(trackId, it) }
        }
    }

    /** 预取邻居歌词；失败静默，真正播放到该页时会再拉一次。 */
    suspend fun prefetch(client: SodaClient, trackId: String) {
        if (get(trackId) != null) return
        runCatching { ensure(client, trackId) }
    }
}

internal data class LyricsState(
    val lines: List<LyricLine>,
    val loading: Boolean,
    val error: String,
)

@Composable
internal fun rememberLyrics(client: SodaClient, trackId: String): LyricsState {
    var state by remember(trackId) {
        val cached = LyricsCache.get(trackId)
        mutableStateOf(LyricsState(cached ?: emptyList(), cached == null, ""))
    }
    LaunchedEffect(trackId) {
        // 预取可能刚好在组合之后写入缓存，这里再查一次并同步状态。
        LyricsCache.get(trackId)?.let {
            state = LyricsState(it, false, "")
            return@LaunchedEffect
        }
        state = LyricsState(emptyList(), true, "")
        try {
            val fetched = LyricsCache.ensure(client, trackId)
            state = LyricsState(fetched, false, "")
        } catch (e: SodaException) {
            state = LyricsState(emptyList(), false, e.readable())
        }
    }
    return state
}
