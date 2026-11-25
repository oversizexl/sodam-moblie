package org.sodahub.sodam.ui

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.sodahub.sodam.R
import uniffi.sodam_ffi.SodaClient
import uniffi.sodam_ffi.Track
import java.io.File

private data class DownloadRecord(
    val track: Track,
    val targetUri: String,
    val fileName: String,
)

/**
 * 全局歌曲下载器：
 * - 通过 [SodaClient.resolveTrack] 获取并解密音频文件，导出至自定义 SAF 目录或默认 `Music/Sodam`；
 * - 记录每个导出文件的真实 URI / 路径，并在切回前台或打开发现页时调用 [reconcileLocalFiles] 核验物理文件存在性；
 * - 若用户在本地文件夹手动删除文件，列表与已下载状态会自动同步剔除。
 */
internal object TrackDownloader {
    private var appContext: Context? = null
    private var client: SodaClient? = null
    private var dirConfigFile: String? = null
    private var recordsFile: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val reconcileMutex = Mutex()

    private var records = emptyList<DownloadRecord>()

    var downloadingIds by mutableStateOf<Set<String>>(emptySet())
        private set

    var downloadedIds by mutableStateOf<Set<String>>(emptySet())
        private set

    var downloadedTracks by mutableStateOf<List<Track>>(emptyList())
        private set

    var batchBusy by mutableStateOf(false)
        private set

    var customDirUri by mutableStateOf<String?>(null)
        private set

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val events = _events.asSharedFlow()

    var eventSeq by mutableLongStateOf(0L)
        private set

    fun init(context: Context, sodaClient: SodaClient?, downloadDirFile: String) {
        val appCtx = context.applicationContext
        appContext = appCtx
        client = sodaClient
        dirConfigFile = downloadDirFile
        customDirUri = readDownloadDir(downloadDirFile)

        val historyPath = File(appCtx.filesDir, "downloaded_tracks.json").absolutePath
        recordsFile = historyPath
        val loaded = readDownloadedRecords(historyPath)
        applyRecords(loaded)
        scope.launch { reconcileLocalFiles() }
    }

    fun updateCustomDir(uriString: String?) {
        val path = dirConfigFile ?: return
        writeDownloadDir(path, uriString)
        customDirUri = uriString?.takeIf { it.isNotBlank() }
    }

    fun isDownloading(trackId: String): Boolean = trackId in downloadingIds
    fun isDownloaded(trackId: String): Boolean = trackId in downloadedIds

    /**
     * 核对所有本地导出记录的物理文件是否真实存在：
     * 如果用户在手机文件管理器中手动删除了音频文件，立刻从清单与已下载状态中移除。
     */
    suspend fun reconcileLocalFiles() {
        val ctx = appContext ?: return
        val path = recordsFile ?: return
        reconcileMutex.withLock {
            val snapshot = records
            if (snapshot.isEmpty()) return@withLock

            val alive = withContext(Dispatchers.IO) {
                snapshot.filter { record -> isRecordAliveOnDisk(ctx, record) }
            }
            if (alive.size != snapshot.size) {
                applyRecords(alive)
                withContext(Dispatchers.IO) { writeDownloadedRecords(path, alive) }
            }
        }
    }

    private fun isRecordAliveOnDisk(ctx: Context, record: DownloadRecord): Boolean {
        val target = record.targetUri
        if (target.isNotBlank()) {
            return if (target.startsWith("content://")) {
                runCatching {
                    ctx.contentResolver.openAssetFileDescriptor(Uri.parse(target), "r")?.use { fd ->
                        fd.length != 0L
                    } ?: false
                }.getOrDefault(false)
            } else {
                val f = File(target)
                f.isFile && f.length() > 0L
            }
        }
        // 兼容旧版未保存 targetUri 的历史记录：按默认名称到自定义目录或系统音乐目录探测一遍
        val baseArtist = sanitizeFileName(record.track.artist).ifBlank { "未知歌手" }
        val baseTitle = sanitizeFileName(record.track.name).ifBlank { record.track.id }
        val prefix = "$baseArtist - $baseTitle."

        val treeStr = customDirUri
        if (!treeStr.isNullOrBlank()) {
            val treeUri = Uri.parse(treeStr)
            val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            if (treeDocId != null && hasMatchingChildDocument(ctx, treeUri, treeDocId, prefix)) {
                return true
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val foundInMediaStore = runCatching {
                ctx.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Audio.Media._ID),
                    "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ? AND ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?",
                    arrayOf("$prefix%", "%${Environment.DIRECTORY_MUSIC}/Sodam%"),
                    null,
                )?.use { cursor -> cursor.moveToFirst() } ?: false
            }.getOrDefault(false)
            if (foundInMediaStore) return true
        } else {
            val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Sodam")
            if (dir.listFiles()?.any { it.isFile && it.name.startsWith(prefix) } == true) return true
        }
        return false
    }

    private fun hasMatchingChildDocument(
        ctx: Context,
        treeUri: Uri,
        parentDocId: String,
        prefix: String,
    ): Boolean {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return runCatching {
            ctx.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIdx)?.startsWith(prefix) == true) return@use true
                }
                false
            } ?: false
        }.getOrDefault(false)
    }

    fun download(
        track: Track,
        force: Boolean = false,
        onComplete: (String) -> Unit = {},
    ) {
        val ctx = appContext ?: return
        val soda = client ?: return
        if (track.id.isBlank()) return
        if (track.id in downloadingIds) return
        if (!force && track.id in downloadedIds) return

        downloadingIds = downloadingIds + track.id
        val msgStart = if (force) "正在重新下载：${track.name}" else "开始下载：${track.name}"
        emitNotice(msgStart)
        onComplete(msgStart)

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                exportSingleTrack(ctx, soda, track)
            }
            downloadingIds = downloadingIds - track.id
            val doneMsg = result.fold(
                onSuccess = { exported ->
                    recordDownloaded(exported)
                    launch { CachedAudio.refresh() }
                    "下载完成：${exported.fileName}"
                },
                onFailure = { err ->
                    "下载失败：${err.message ?: "请检查网络或下载目录权限"}"
                },
            )
            emitNotice(doneMsg)
            onComplete(doneMsg)
        }
    }

    /** 批量下载歌单/专辑内尚未下载的曲目（串行逐首处理，防止并发拥堵）。 */
    fun downloadAll(tracks: List<Track>) {
        val ctx = appContext ?: return
        val soda = client ?: return
        if (batchBusy) return
        val pending = tracks.filter { it.id.isNotBlank() && it.id !in downloadedIds }
        if (pending.isEmpty()) {
            emitNotice("列表内曲目已全部下载")
            return
        }

        batchBusy = true
        emitNotice("开始批量下载 ${pending.size} 首歌曲…")
        scope.launch {
            var successCount = 0
            for (track in pending) {
                if (track.id in downloadingIds) continue
                downloadingIds = downloadingIds + track.id
                val exported = withContext(Dispatchers.IO) {
                    exportSingleTrack(ctx, soda, track).getOrNull()
                }
                downloadingIds = downloadingIds - track.id
                if (exported != null) {
                    recordDownloaded(exported)
                    successCount++
                }
            }
            batchBusy = false
            launch { CachedAudio.refresh() }
            emitNotice("批量下载完成：成功 $successCount / ${pending.size} 首")
        }
    }

    private fun applyRecords(next: List<DownloadRecord>) {
        records = next
        downloadedTracks = next.map { it.track }
        downloadedIds = next.mapTo(HashSet()) { it.track.id }
    }

    private fun recordDownloaded(record: DownloadRecord) {
        val updated = listOf(record) + records.filter { it.track.id != record.track.id }
        applyRecords(updated)
        val path = recordsFile ?: return
        scope.launch(Dispatchers.IO) {
            writeDownloadedRecords(path, updated)
        }
    }

    private fun exportSingleTrack(
        ctx: Context,
        soda: SodaClient,
        track: Track,
    ): Result<DownloadRecord> = runCatching {
        val resolved = soda.resolveTrack(track.id)
        val source = File(resolved.path)
        if (!source.isFile || source.length() == 0L) {
            error("无法读取已解密的音频文件")
        }
        val ext = source.extension.lowercase().ifBlank { "m4a" }
        val mime = mimeTypeFor(ext)
        val baseArtist = sanitizeFileName(track.artist).ifBlank { "未知歌手" }
        val baseTitle = sanitizeFileName(track.name).ifBlank { track.id }
        val displayName = "$baseArtist - $baseTitle.$ext"

        val savedUri = customDirUri
        val targetPath = if (!savedUri.isNullOrBlank()) {
            saveToSafTree(ctx, Uri.parse(savedUri), source, displayName, mime)
        } else {
            saveToDefaultMusicDir(ctx, source, displayName, mime)
        }
        DownloadRecord(track = track, targetUri = targetPath, fileName = displayName)
    }

    private fun emitNotice(msg: String) {
        eventSeq++
        _events.tryEmit(msg)
    }

    private fun saveToSafTree(
        ctx: Context,
        treeUri: Uri,
        source: File,
        displayName: String,
        mime: String,
    ): String {
        val resolver = ctx.contentResolver
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)

        val existingUri = findExistingDocument(ctx, treeUri, treeDocId, displayName)
        if (existingUri != null) {
            runCatching { DocumentsContract.deleteDocument(resolver, existingUri) }
        }

        val targetUri = DocumentsContract.createDocument(resolver, parentDocUri, mime, displayName)
            ?: error("无法在所选目录创建文件，请重新选择下载路径")
        resolver.openOutputStream(targetUri)?.use { out ->
            source.inputStream().use { input -> input.copyTo(out) }
        } ?: error("无法打开文件写入流")
        return targetUri.toString()
    }

    private fun findExistingDocument(
        ctx: Context,
        treeUri: Uri,
        parentDocId: String,
        targetName: String,
    ): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        return runCatching {
            ctx.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIdx) == targetName) {
                        return@use DocumentsContract.buildDocumentUriUsingTree(
                            treeUri,
                            cursor.getString(idIdx),
                        )
                    }
                }
                null
            }
        }.getOrNull()
    }

    private fun saveToDefaultMusicDir(
        ctx: Context,
        source: File,
        displayName: String,
        mime: String,
    ): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = ctx.contentResolver
            val relative = "${Environment.DIRECTORY_MUSIC}/Sodam"
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, relative)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("无法写入系统音乐目录")
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    source.inputStream().use { input -> input.copyTo(out) }
                } ?: error("写入音频数据失败")
                val doneValues = ContentValues().apply {
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                }
                resolver.update(uri, doneValues, null, null)
                return uri.toString()
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }
        } else {
            val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Sodam")
            if (!dir.exists()) dir.mkdirs()
            val dest = File(dir, displayName)
            source.copyTo(dest, overwrite = true)
            return dest.absolutePath
        }
    }

    private fun mimeTypeFor(ext: String): String = when (ext) {
        "flac" -> "audio/flac"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "aac" -> "audio/aac"
        else -> "audio/mp4"
    }

    private fun sanitizeFileName(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_").trim().take(80)

    private fun readDownloadedRecords(path: String): List<DownloadRecord> = runCatching {
        val text = File(path).takeIf { it.isFile }?.readText().orEmpty()
        if (text.isBlank()) return emptyList()
        val array = JSONArray(text)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.optString("id")
                if (id.isEmpty()) continue
                add(
                    DownloadRecord(
                        track = Track(
                            id = id,
                            name = obj.optString("name"),
                            artist = obj.optString("artist"),
                            album = obj.optString("album"),
                            duration = obj.optLong("duration"),
                            cover = obj.optString("cover"),
                            vip = obj.optBoolean("vip"),
                        ),
                        targetUri = obj.optString("targetUri"),
                        fileName = obj.optString("fileName"),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun writeDownloadedRecords(path: String, items: List<DownloadRecord>) {
        runCatching {
            val array = JSONArray()
            items.forEach { rec ->
                array.put(
                    JSONObject().apply {
                        put("id", rec.track.id)
                        put("name", rec.track.name)
                        put("artist", rec.track.artist)
                        put("album", rec.track.album)
                        put("duration", rec.track.duration)
                        put("cover", rec.track.cover)
                        put("vip", rec.track.vip)
                        put("targetUri", rec.targetUri)
                        put("fileName", rec.fileName)
                    },
                )
            }
            File(path).apply {
                parentFile?.mkdirs()
                writeText(array.toString())
            }
        }
    }
}

/** 通用歌曲下载图标按键：下载中转圈，已下载显示对勾且点击不再重复触发或弹提示。 */
@Composable
internal fun DownloadButton(
    track: Track,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onFeedback: (String) -> Unit = {},
) {
    val busy = TrackDownloader.isDownloading(track.id)
    val done = TrackDownloader.isDownloaded(track.id)
    IconButton(
        onClick = {
            if (!busy && !done) {
                TrackDownloader.download(track, force = false, onComplete = onFeedback)
            }
        },
        enabled = !busy,
    ) {
        when {
            busy -> Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            }
            done -> Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "已下载",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            else -> Icon(
                painter = painterResource(R.drawable.ic_download),
                contentDescription = "下载歌曲",
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
