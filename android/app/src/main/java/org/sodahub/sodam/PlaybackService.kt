package org.sodahub.sodam

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import uniffi.sodam_ffi.ResolvedTrack
import uniffi.sodam_ffi.Track
import java.io.File

private const val OPEN_APP_REQUEST = 1

/** 队列剩余少于等于这个数时提前续拉，不等播到最后一首。 */
private const val PREFETCH_AHEAD = 2

/**
 * 后台播放宿主：队列、下一首/上一首、播完自动续拉推荐都在这里执行。
 * 通知栏通过自定义命令按钮驱动同一套逻辑，不依赖 Activity 是否存活。
 */
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 已预解析、排在当前曲后面的队列下标。 */
    private var nextQueueIndex: Int? = null
    private var nextResolved: ResolvedTrack? = null

    /** 每次 playAt 自增：后台解析回来时据此丢弃过时结果。 */
    private var generation = 0
    private var nextToken = 0

    override fun onCreate() {
        super.onCreate()
        // 与参考实现一致：标准 Media3 通知，三键布局（上一首 / 播放暂停 / 下一首），
        // 只把小图标换成应用自己的音符，免得用 Media3 默认的圆环播放键。
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(this).apply {
                setSmallIcon(R.drawable.ic_stat_sodam)
            },
        )
        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
        }
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    onTrackEnded()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // 自然播完是 AUTO；通知栏/系统媒体面板的“下一首”走 seekToNextMediaItem，是 SEEK。
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                    reason != Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
                ) {
                    return
                }
                adoptNext(mediaItem)
            }
        })

        // 系统媒体控制（Android 13+ 的 |< / >|）发的是播放器命令，不是自定义命令。
        // 队列是一首一首解析出来的，播放器里只有一个媒体项，原生 seekToNext/Previous 只会
        // 在本曲内跳。这里把四个切歌入口都接到服务自己的队列逻辑上。
        //
        // 另外强制把「上一首/下一首」命令标成可用：单曲播放列表 + 不循环时，
        // ExoPlayer 会把它们判为不可用，Media3 通知栏和系统媒体面板就不显示这两个按钮
        // （或显示成同一个）。补上之后通知栏就是标准的三键：上一首 / 播放暂停 / 下一首。
        val commandPlayer = object : ForwardingPlayer(player) {
            override fun getAvailableCommands(): Player.Commands =
                super.getAvailableCommands().buildUpon()
                    .addAll(
                        Player.COMMAND_SEEK_TO_PREVIOUS,
                        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_NEXT,
                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    )
                    .build()

            override fun seekToPrevious() = playPrevious()
            override fun seekToPreviousMediaItem() = playPrevious()
            override fun seekToNext() = playNext()
            override fun seekToNextMediaItem() = playNext()
        }

        val openApp = PendingIntent.getActivity(
            this,
            OPEN_APP_REQUEST,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        mediaSession = MediaSession.Builder(this, commandPlayer)
            .setCallback(sessionCallback)
            .setSessionActivity(openApp)
            .build()
    }

    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                .buildUpon()
                .add(SessionCommand(CMD_PLAY_INDEX, Bundle.EMPTY))
                .add(SessionCommand(CMD_NEXT, Bundle.EMPTY))
                .add(SessionCommand(CMD_PREVIOUS, Bundle.EMPTY))
                .add(SessionCommand(CMD_SET_MODE, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_PLAY_INDEX -> {
                    val index = args.getInt(ARG_INDEX, -1)
                    if (index >= 0) playAt(index)
                }
                CMD_NEXT -> playNext()
                CMD_PREVIOUS -> playPrevious()
                CMD_SET_MODE -> {
                    val name = args.getString(ARG_MODE)
                    val mode = PlaybackMode.entries.firstOrNull { it.name == name }
                    if (mode != null) applyMode(mode)
                }
                else -> return Futures.immediateFuture(
                    SessionResult(SessionError.ERROR_NOT_SUPPORTED),
                )
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private fun playAt(index: Int) {
        if (PlaybackHub.loading) return
        val track = PlaybackHub.queue.getOrNull(index) ?: return
        val client = PlaybackHub.client ?: return
        // 先置加载态：旧歌继续播、旧信息继续显示，等解析完成才切换。
        PlaybackHub.loading = true
        PlaybackHub.preview = false
        // 作废在途的下一首解析，避免它落到新的播放列表里。
        generation++
        nextToken++
        nextQueueIndex = null
        nextResolved = null
        val gen = generation
        Thread {
            val resolved = runCatching { client.resolveTrack(track.id) }.getOrNull()
            mainHandler.post {
                if (gen != generation) return@post
                PlaybackHub.loading = false
                if (resolved == null) return@post
                PlaybackHub.index = index
                PlaybackHub.preview = resolved.preview
                PlaybackHub.rememberQuality(track.id, resolved.quality)
                player.repeatMode = if (PlaybackHub.mode == PlaybackMode.SingleLoop) {
                    Player.REPEAT_MODE_ONE
                } else {
                    Player.REPEAT_MODE_OFF
                }
                player.setMediaItems(listOf(mediaItemFor(track, resolved.path)), 0, 0L)
                player.prepare()
                player.play()
                scheduleNext(index)
                prefetchAround(index)
                maybeAppendMore(index)
            }
        }.apply { name = "sodam-resolve" }.start()
    }

    /**
     * 后台解析下一首，成功后作为第二个媒体项入队；播完或点“下一首”直接过渡，接近无缝。
     * 播放列表只在主线程整理，且始终只有 [当前, 下一首]。
     */
    private fun scheduleNext(fromIndex: Int) {
        if (PlaybackHub.mode == PlaybackMode.SingleLoop) return
        if (PlaybackHub.queue.size <= 1) return
        val next = PlaybackHub.nextIndex(
            fromIndex,
            shuffle = PlaybackHub.mode == PlaybackMode.Shuffle,
        ) ?: return
        if (next == fromIndex) return
        val track = PlaybackHub.queue.getOrNull(next) ?: return
        val client = PlaybackHub.client ?: return
        val gen = generation
        val token = ++nextToken
        Thread {
            val resolved = runCatching { client.resolveTrack(track.id) }.getOrNull() ?: return@Thread
            mainHandler.post {
                if (gen != generation || token != nextToken) return@post
                if (PlaybackHub.index != fromIndex || nextQueueIndex != null) return@post
                if (PlaybackHub.mode == PlaybackMode.SingleLoop) return@post
                // 丢掉已经播完的历史项，保持列表不无限增长。
                val current = player.currentMediaItemIndex
                if (current > 0) player.removeMediaItems(0, current)
                player.addMediaItem(mediaItemFor(track, resolved.path))
                nextQueueIndex = next
                nextResolved = resolved
            }
        }.apply { name = "sodam-next" }.start()
    }

    /** 过渡到预解析的下一首后，把它提升为当前曲并继续向后预解析。 */
    private fun adoptNext(mediaItem: MediaItem?) {
        val index = nextQueueIndex ?: return
        val track = PlaybackHub.queue.getOrNull(index)
        if (track == null || mediaItem?.mediaId != track.id) {
            // 队列已被替换或播放项不是排好的那一首：丢掉预解析记录，清掉多余项后按当前队列重排。
            nextQueueIndex = null
            nextResolved = null
            mainHandler.post {
                val from = player.currentMediaItemIndex + 1
                if (player.mediaItemCount > from) player.removeMediaItems(from, player.mediaItemCount)
                if (PlaybackHub.index >= 0) scheduleNext(PlaybackHub.index)
            }
            return
        }
        val resolved = nextResolved
        nextQueueIndex = null
        nextResolved = null
        PlaybackHub.index = index
        PlaybackHub.preview = resolved?.preview ?: false
        if (resolved != null) PlaybackHub.rememberQuality(track.id, resolved.quality)
        mainHandler.post {
            if (PlaybackHub.index != index) return@post
            scheduleNext(index)
            prefetchAround(index)
            maybeAppendMore(index)
        }
    }

    private fun playNext() {
        if (PlaybackHub.loading) return
        // 下一首已经预解析在播放列表里：直接过渡，不再走下载。
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            return
        }
        val next = PlaybackHub.nextIndex(
            PlaybackHub.index,
            shuffle = PlaybackHub.mode == PlaybackMode.Shuffle,
        ) ?: return
        playAt(next)
    }

    private fun playPrevious() {
        if (PlaybackHub.loading) return
        // 上一首就是切歌。以前这里有个「播放超过 3 秒先回到开头」的分支，
        // 结果无论点几次都只回到本曲开头，永远切不到上一首。
        val previous = PlaybackHub.previousIndex(PlaybackHub.index)
        if (previous >= 0) playAt(previous)
    }

    private fun onTrackEnded() {
        if (PlaybackHub.loading) return
        if (PlaybackHub.mode == PlaybackMode.SingleLoop) {
            player.seekTo(0)
            player.play()
            return
        }
        // 正常情况下预解析项会自动接上；走到这里说明下一首没排上（解析失败/队列耗尽），兜底续播。
        val next = PlaybackHub.nextIndex(
            PlaybackHub.index,
            shuffle = PlaybackHub.mode == PlaybackMode.Shuffle,
        ) ?: return
        playAt(next)
    }

    /** 切换播放模式：单曲循环交给播放器自身 repeat，并清掉已排队的下一首。 */
    private fun applyMode(mode: PlaybackMode) {
        PlaybackHub.mode = mode
        player.repeatMode = if (mode == PlaybackMode.SingleLoop) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_OFF
        }
        if (mode == PlaybackMode.SingleLoop) {
            nextToken++
            nextQueueIndex = null
            nextResolved = null
            val from = player.currentMediaItemIndex + 1
            if (player.mediaItemCount > from) {
                player.removeMediaItems(from, player.mediaItemCount)
            }
        } else if (PlaybackHub.index >= 0) {
            scheduleNext(PlaybackHub.index)
        }
    }

    /** 队列剩余不多时提前续拉，不等播到最后一首。 */
    private fun maybeAppendMore(fromIndex: Int) {
        if (PlaybackHub.queue.size - 1 - fromIndex > PREFETCH_AHEAD) return
        val request = PlaybackHub.recommendRequest ?: return
        val client = PlaybackHub.client ?: return
        if (!PlaybackHub.beginAppend()) return
        val pages = PlaybackHub.pagesPerFetch.coerceAtLeast(1)
        Thread {
            val more = runCatching {
                buildList {
                    repeat(pages) { addAll(client.recommend(request.first, request.second)) }
                }
            }.getOrNull()
            mainHandler.post {
                PlaybackHub.endAppend()
                val existing = PlaybackHub.queue.mapTo(HashSet()) { it.id }
                val fresh = more.orEmpty().distinctBy { it.id }.filter { it.id !in existing }
                if (fresh.isEmpty()) return@post
                PlaybackHub.queue = PlaybackHub.queue + fresh
                // 追加进来的新歌异步预取前几首，滑过去/播到时不现下载。
                client.prefetch(fresh.take(3).map { it.id })
                if (nextQueueIndex == null && PlaybackHub.index == fromIndex) {
                    scheduleNext(fromIndex)
                }
            }
        }.apply { name = "sodam-append" }.start()
    }

    /** 预取再往后两首到本地缓存（下一首由 [scheduleNext] 预解析）。 */
    private fun prefetchAround(index: Int) {
        val client = PlaybackHub.client ?: return
        val queue = PlaybackHub.queue
        val ids = (index + 2..index + 3).mapNotNull { queue.getOrNull(it)?.id }
        if (ids.isNotEmpty()) client.prefetch(ids)
    }

    /** 媒体项带上 trackId，过渡回调据此确认落到的是预解析的那一首。 */
    private fun mediaItemFor(track: Track, path: String): MediaItem {
        val artwork = track.cover.takeIf { it.isNotBlank() }?.let(Uri::parse)
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(Uri.fromFile(File(path)))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.name)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album)
                    .setArtworkUri(artwork)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build(),
            )
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
