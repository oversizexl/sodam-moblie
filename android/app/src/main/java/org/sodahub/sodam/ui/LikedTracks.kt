package org.sodahub.sodam.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.sodam_ffi.SodaClient
import uniffi.sodam_ffi.Track

/**
 * 服务端「我喜欢的音乐」状态：只缓存已喜欢曲目的 id，真正的喜欢/取消走接口。
 *
 * 登录后由 [attach] + [reload] 拉一次；点爱心先乐观更新，接口失败再回滚。
 * 针对冷启动优先起播的缓存歌曲（尚不在 Rust 内存 songs 表中），若首次调用报错，
 * 自动通过搜索把该曲目注册进内存曲目表后立即重试。
 */
internal object LikedTracks {
    private const val LIKED_PLAYLIST = "我喜欢的音乐"

    private var client: SodaClient? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 已喜欢的曲目 id；读取方会随状态变化重组。 */
    var likedIds by mutableStateOf<Set<String>>(emptySet())
        private set

    /** 是否已至少与服务端同步过一次完整歌单曲目列表。 */
    var loaded by mutableStateOf(false)
        private set

    val count: Int get() = likedIds.size

    fun attach(c: SodaClient?) {
        client = c
        if (c == null) {
            likedIds = emptySet()
            loaded = false
        }
    }

    fun isLiked(trackId: String): Boolean = trackId.isNotEmpty() && trackId in likedIds

    /** 拉一次服务端「我喜欢的音乐」里的曲目 id。 */
    suspend fun reload() {
        val c = client ?: return
        val ids = withContext(Dispatchers.IO) {
            runCatching {
                val liked = c.myPlaylists().firstOrNull { it.name.contains(LIKED_PLAYLIST) }
                    ?: return@runCatching null
                c.playlistTracks(liked.id).mapNotNullTo(HashSet()) { it.id.takeIf(String::isNotEmpty) }
            }.getOrNull()
        }
        if (ids != null) {
            likedIds = ids
            loaded = true
        }
    }

    /** 用服务端歌单里的曲目刷新已喜欢集合（打开「我喜欢的音乐」时调用）。 */
    fun setFromServerTracks(tracks: List<Track>) {
        likedIds = tracks.mapNotNullTo(HashSet()) { it.id.takeIf(String::isNotEmpty) }
        loaded = true
    }

    /** 点击爱心：乐观切换 + 调接口；缓存首播曲目未注册进本地表时自动检索补录后重试。 */
    fun toggle(track: Track) {
        val c = client ?: return
        if (track.id.isBlank()) return
        val wasLiked = isLiked(track.id)
        likedIds = if (wasLiked) likedIds - track.id else likedIds + track.id
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                val firstTry = runCatching {
                    if (wasLiked) c.unlikeTrack(track.id) else c.likeTrack(track.id)
                }
                if (firstTry.isSuccess) return@withContext true

                // 冷启动缓存歌曲尚不在 Rust self.songs 表里：先检索将其载入本地表，再重试喜欢/取消。
                val queryPrimary = "${track.name} ${track.artist}".trim()
                val registered = runCatching {
                    val found = if (queryPrimary.isNotEmpty()) c.search(queryPrimary) else emptyList()
                    if (found.none { it.id == track.id } && track.name.isNotBlank()) {
                        c.search(track.name)
                    }
                    true
                }.getOrDefault(false)

                if (!registered) return@withContext false
                runCatching {
                    if (wasLiked) c.unlikeTrack(track.id) else c.likeTrack(track.id)
                }.isSuccess
            }
            if (!ok) {
                likedIds = if (wasLiked) likedIds + track.id else likedIds - track.id
            }
        }
    }
}

/** 歌曲列表/播放页通用的爱心按钮：空心=未喜欢，红色实心=已喜欢。 */
@Composable
internal fun LikeButton(track: Track) {
    val liked = LikedTracks.isLiked(track.id)
    IconButton(onClick = { LikedTracks.toggle(track) }) {
        Icon(
            if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = if (liked) "取消喜欢" else "喜欢",
            tint = if (liked) Color(0xFFE53935) else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
