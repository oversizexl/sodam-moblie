package org.sodahub.sodam.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.sodahub.sodam.PlaybackMode
import uniffi.sodam_ffi.Track
import java.io.File

/** 首页推荐在服务端的标识：`scene_mode_id < 0` 且子队列为空。 */
internal val HOME_RECOMMEND = -1L to ""

// 凭据文件读写。路径由宿主传进来（应用私有目录），这里绝不打印内容。
internal fun readCookie(path: String): String? =
    runCatching { File(path).takeIf { it.isFile }?.readText()?.trim() }
        .getOrNull()
        ?.takeIf { it.isNotEmpty() }

internal fun writeCookie(path: String, cookie: String) {
    runCatching {
        File(path).apply {
            parentFile?.mkdirs()
            writeText(cookie)
        }
    }
}

internal fun clearCookie(path: String) {
    runCatching { File(path).delete() }
}

internal fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/** 关注数/播放量：一万以上显示「x.x万」，一亿以上显示「x.x亿」。 */
internal fun formatCount(count: Long): String = when {
    count >= 100_000_000 -> "${trimTrailingZero(count / 100_000_000.0)}亿"
    count >= 10_000 -> "${trimTrailingZero(count / 10_000.0)}万"
    else -> count.coerceAtLeast(0).toString()
}

private fun trimTrailingZero(value: Double): String {
    val rounded = (value * 10).toLong() / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
}

internal fun nextMode(mode: PlaybackMode): PlaybackMode = when (mode) {
    PlaybackMode.ListLoop -> PlaybackMode.SingleLoop
    PlaybackMode.SingleLoop -> PlaybackMode.Shuffle
    PlaybackMode.Shuffle -> PlaybackMode.ListLoop
}

internal fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) "%.1f GB".format(mb / 1024) else "%.0f MB".format(mb)
}

internal val QUALITY_OPTIONS = listOf(
    "auto" to "自动",
    "hires" to "Hi-Res 无损",
    "lossless" to "无损",
    "dolby" to "杜比全景声",
    "highest" to "极高",
    "medium" to "较高",
    "low" to "流畅",
)

/** 每个档位的说明，与桌面端设置页一致：档位是上限，缺档时由服务端回退。 */
internal val QUALITY_HINTS = mapOf(
    "auto" to "不设上限，按账号权益和单曲可用档位取最高",
    "hires" to "优先 Hi-Res 无损；没有可用档位时由服务端回退",
    "lossless" to "优先无损；没有可用无损时由服务端回退",
    "dolby" to "优先杜比全景声；没有可用档位时由服务端回退",
    "highest" to "优先极高（≈320k）；没有可用档位时由服务端回退",
    "medium" to "优先较高；没有可用档位时由服务端回退",
    "low" to "优先流畅（≈128k）；没有可用档位时由服务端回退",
)

/** 与桌面端一致：默认「自动」，按可用权益选最高档位；嫌流量大可在设置里改「极高」。 */
private const val DEFAULT_QUALITY = "auto"

private val QUALITY_TIERS = QUALITY_OPTIONS.map { it.first }.toSet()

internal fun readQuality(path: String): String =
    runCatching { File(path).takeIf { it.isFile }?.readText()?.trim() }
        .getOrNull()
        ?.takeIf { it.isNotEmpty() && it in QUALITY_TIERS }
        ?: DEFAULT_QUALITY

internal fun writeQuality(path: String, tier: String) {
    runCatching {
        File(path).apply {
            parentFile?.mkdirs()
            writeText(tier)
        }
    }
}

internal val CACHE_LIMITS = listOf(
    500L * 1024 * 1024 to "500 MB",
    1L * 1024 * 1024 * 1024 to "1 GB",
    5L * 1024 * 1024 * 1024 to "5 GB",
    10L * 1024 * 1024 * 1024 to "10 GB",
)

private const val DEFAULT_CACHE_LIMIT = 500L * 1024 * 1024
internal const val MIN_CACHE_LIMIT_MB = 1L
internal const val MAX_CACHE_LIMIT_MB = 200L * 1024

/** 接受任意自定义上限（MB 输入），不再只认预设档位。 */
internal fun readCacheLimit(path: String): Long =
    runCatching { File(path).takeIf { it.isFile }?.readText()?.trim()?.toLong() }
        .getOrNull()
        ?.takeIf { it in (MIN_CACHE_LIMIT_MB * 1024 * 1024)..(MAX_CACHE_LIMIT_MB * 1024 * 1024) }
        ?: DEFAULT_CACHE_LIMIT

internal fun writeCacheLimit(path: String, bytes: Long) {
    runCatching {
        File(path).apply {
            parentFile?.mkdirs()
            writeText(bytes.toString())
        }
    }
}

/** 每页固定 6 首；页数决定首页一次刷新拉几页。 */
internal const val TRACKS_PER_PAGE = 6
internal const val DEFAULT_PAGE_COUNT = 1
internal const val MAX_PAGE_COUNT = 20

internal fun readPageCount(path: String): Int =
    runCatching { File(path).takeIf { it.isFile }?.readText()?.trim()?.toInt() }
        .getOrNull()
        ?.coerceIn(1, MAX_PAGE_COUNT)
        ?: DEFAULT_PAGE_COUNT

internal fun writePageCount(path: String, pages: Int) {
    runCatching {
        File(path).apply {
            parentFile?.mkdirs()
            writeText(pages.coerceIn(1, MAX_PAGE_COUNT).toString())
        }
    }
}

internal val THEME_MODES = listOf(
    "system" to "跟随系统",
    "light" to "浅色",
    "dark" to "深色",
)

private val THEME_VALUES = THEME_MODES.map { it.first }.toSet()

internal fun themeLabel(value: String): String =
    THEME_MODES.firstOrNull { it.first == value }?.second ?: "跟随系统"

internal fun readTheme(path: String): String =
    runCatching { File(path).takeIf { it.isFile }?.readText()?.trim() }
        .getOrNull()
        ?.takeIf { it in THEME_VALUES }
        ?: "system"

internal fun writeTheme(path: String, value: String) {
    runCatching {
        File(path).apply {
            parentFile?.mkdirs()
            writeText(value)
        }
    }
}

// ---- 主题色 / 界面风格 ----

internal const val DEFAULT_THEME_COLOR = "34d6c4"
internal const val DEFAULT_PALETTE_STYLE = "tonal"
internal const val DEFAULT_UI_STYLE = "material"
internal const val UI_STYLE_CLEAN = "clean"
internal const val UI_STYLE_MATERIAL = "material"

/** 预设主题色（6 位 HEX，无 #）。 */
internal val THEME_COLOR_PRESETS = listOf(
    "34d6c4" to "青绿",
    "4c6fff" to "湛蓝",
    "7c5cff" to "紫罗兰",
    "e05a87" to "胭脂",
    "e8792e" to "暖橙",
    "2e9e62" to "森绿",
    "8a8f98" to "石墨",
)

internal data class ThemeStyleOption(
    val value: String,
    val label: String,
    val description: String,
)

/** 界面风格对齐 Material 3 调色板风格（参考 NeriPlayer）。 */
internal val THEME_STYLES = listOf(
    ThemeStyleOption("tonal", "TonalSpot", "均衡柔和，默认风格"),
    ThemeStyleOption("vibrant", "Vibrant", "更高饱和度的强调色"),
    ThemeStyleOption("neutral", "Neutral", "低饱和、接近灰阶"),
    ThemeStyleOption("expressive", "Expressive", "色相偏移，更有个性"),
    ThemeStyleOption("monochrome", "Monochrome", "纯灰阶强调色"),
    ThemeStyleOption("fidelity", "Fidelity", "尽量保留主题色本来的颜色"),
)

private val THEME_STYLE_VALUES = THEME_STYLES.map { it.value }.toSet()
private val HEX_COLOR = Regex("^[0-9a-fA-F]{6}$")

internal fun themeColorLabel(hex: String): String =
    THEME_COLOR_PRESETS.firstOrNull { it.first.equals(hex, ignoreCase = true) }?.second ?: "自定义"

internal fun paletteStyleLabel(value: String): String =
    THEME_STYLES.firstOrNull { it.value == value }?.label ?: "TonalSpot"

/** 界面风格：Material You / 简洁（参考 NeriPlayer）。 */
internal val UI_STYLES = listOf(
    UI_STYLE_CLEAN to ("简洁" to "中性配色、连续圆角，长时间使用更轻松"),
    UI_STYLE_MATERIAL to ("Material You" to "经典 Material 3，强调色贯穿界面"),
)

internal fun uiStyleLabel(value: String): String =
    UI_STYLES.firstOrNull { it.first == value }?.second?.first ?: "Material You"

internal fun readUiStyle(path: String): String =
    runCatching { File(path).takeIf { it.isFile }?.readText()?.trim() }
        .getOrNull()
        ?.takeIf { it == UI_STYLE_CLEAN || it == UI_STYLE_MATERIAL }
        ?: DEFAULT_UI_STYLE

internal fun writeUiStyle(path: String, value: String) {
    if (value != UI_STYLE_CLEAN && value != UI_STYLE_MATERIAL) return
    runCatching {
        File(path).apply {
            parentFile?.mkdirs()
            writeText(value)
        }
    }
}

/** 首次使用的滑动切歌引导只展示一次。 */
internal fun readGuideSeen(path: String): Boolean =
    runCatching { File(path).isFile }.getOrDefault(false)

internal fun writeGuideSeen(path: String) {
    runCatching {
        File(path).apply {
            parentFile?.mkdirs()
            writeText("1")
        }
    }
}

/** 调试开关：开启后每次启动都弹滑动引导。 */
internal fun readGuideDebug(path: String): Boolean =
    runCatching { File(path).takeIf { it.isFile }?.readText()?.trim() }
        .getOrNull() == "1"

internal fun writeGuideDebug(path: String, enabled: Boolean) {
    runCatching {
        File(path).apply {
            parentFile?.mkdirs()
            writeText(if (enabled) "1" else "0")
        }
    }
}

internal fun readThemeColor(path: String): String =
    runCatching { File(path).takeIf { it.isFile }?.readText()?.trim() }
        .getOrNull()
        ?.takeIf { HEX_COLOR.matches(it) }
        ?.lowercase()
        ?: DEFAULT_THEME_COLOR

internal fun writeThemeColor(path: String, hex: String) {
    if (!HEX_COLOR.matches(hex)) return
    runCatching {
        File(path).apply {
            parentFile?.mkdirs()
            writeText(hex.lowercase())
        }
    }
}

internal fun readThemeStyle(path: String): String =
    runCatching { File(path).takeIf { it.isFile }?.readText()?.trim() }
        .getOrNull()
        ?.takeIf { it in THEME_STYLE_VALUES }
        ?: DEFAULT_PALETTE_STYLE

internal fun writeThemeStyle(path: String, value: String) {
    if (value !in THEME_STYLE_VALUES) return
    runCatching {
        File(path).apply {
            parentFile?.mkdirs()
            writeText(value)
        }
    }
}

/** 上次选中的循环模式。 */
internal fun readPlayMode(path: String): PlaybackMode =
    runCatching { File(path).takeIf { it.isFile }?.readText()?.trim() }
        .getOrNull()
        ?.let { name -> PlaybackMode.entries.firstOrNull { it.name == name } }
        ?: PlaybackMode.ListLoop

internal fun writePlayMode(path: String, mode: PlaybackMode) {
    runCatching {
        File(path).apply {
            parentFile?.mkdirs()
            writeText(mode.name)
        }
    }
}

/** 上次选中的听歌场景（推荐模式不落盘）。 */
internal data class SavedScene(val sceneModeId: Long, val subQueueType: String, val text: String)

internal fun readSavedScene(path: String): SavedScene? = runCatching {
    val parts = File(path).takeIf { it.isFile }?.readText()?.split('\t') ?: return null
    if (parts.size < 3) return null
    val id = parts[0].trim().toLongOrNull() ?: return null
    val type = parts[1].trim()
    val text = parts[2].trim()
    if (text.isEmpty()) null else SavedScene(id, type, text)
}.getOrNull()

internal fun writeSavedScene(path: String, sceneModeId: Long, subQueueType: String, text: String) {
    runCatching {
        File(path).apply {
            parentFile?.mkdirs()
            writeText("$sceneModeId\t$subQueueType\t$text")
        }
    }
}

internal fun clearSavedScene(path: String) {
    runCatching { File(path).delete() }
}

/** 首页队列的本地快照：下次启动先显示旧队列，不等网络。 */
internal fun readHomeQueue(path: String): List<Track> = runCatching {
    val text = File(path).takeIf { it.isFile }?.readText().orEmpty()
    if (text.isBlank()) return emptyList()
    val array = JSONArray(text)
    buildList {
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val id = obj.optString("id")
            if (id.isEmpty()) continue
            add(
                Track(
                    id = id,
                    name = obj.optString("name"),
                    artist = obj.optString("artist"),
                    album = obj.optString("album"),
                    duration = obj.optLong("duration"),
                    cover = obj.optString("cover"),
                    vip = obj.optBoolean("vip"),
                ),
            )
        }
    }
}.getOrDefault(emptyList())

internal fun writeHomeQueue(path: String, queue: List<Track>) {
    runCatching {
        val array = JSONArray()
        queue.take(120).forEach { track ->
            array.put(
                JSONObject().apply {
                    put("id", track.id)
                    put("name", track.name)
                    put("artist", track.artist)
                    put("album", track.album)
                    put("duration", track.duration)
                    put("cover", track.cover)
                    put("vip", track.vip)
                },
            )
        }
        File(path).apply {
            parentFile?.mkdirs()
            writeText(array.toString())
        }
    }
}

/**
 * 本地音频缓存索引：把缓存目录里的文件认成曲目 id，供界面显示「已缓存」标签。
 *
 * 文件名格式是 `{trackId}-{quality}.{ext}`（并行还可能有 `.quality` 旁注文件），
 * 曲目 id 为纯数字，取第一个 `-` 之前的部分即可还原 id。
 */
internal object CachedAudio {
    private var dir: String? = null

    /** 已落盘的曲目 id；读取方会因状态变化而重组。 */
    var cachedIds by mutableStateOf<Set<String>>(emptySet())
        private set

    /** 每首缓存曲目最近的落盘时间（毫秒），用于“优先播最近缓存”。 */
    var cachedAt by mutableStateOf<Map<String, Long>>(emptyMap())
        private set

    fun init(path: String) {
        dir = path
    }

    fun isCached(trackId: String): Boolean = trackId.isNotEmpty() && trackId in cachedIds

    /** 后台扫一遍缓存目录；解析/预取落盘后调用即可刷新标签。 */
    suspend fun refresh() {
        val path = dir ?: return
        val (ids, at) = withContext(Dispatchers.IO) {
            val ids = HashSet<String>()
            val at = HashMap<String, Long>()
            runCatching {
                File(path).listFiles().orEmpty().forEach { file ->
                    if (!file.isFile) return@forEach
                    val name = file.name
                    val dash = name.indexOf('-')
                    if (dash <= 0) return@forEach
                    // 只统计音频本体，跳过 sidecar 与下载/替换中的临时文件。
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext in setOf("quality", "part", "lock", "tmp", "old", "meta")) {
                        return@forEach
                    }
                    val id = name.substring(0, dash)
                    ids.add(id)
                    val modified = file.lastModified()
                    if (modified > (at[id] ?: 0L)) at[id] = modified
                }
            }
            ids to at
        }
        cachedIds = ids
        cachedAt = at
    }
}
