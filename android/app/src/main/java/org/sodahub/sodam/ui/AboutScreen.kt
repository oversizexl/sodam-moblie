package org.sodahub.sodam.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.sodahub.sodam.R
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val REPO_URL = "https://github.com/oversizexl/sodam-moblie"
private const val LATEST_RELEASE_API = "https://api.github.com/repos/oversizexl/sodam-moblie/releases/latest"
private const val LATEST_RELEASE_WEB = "https://github.com/oversizexl/sodam-moblie/releases/latest"

private data class ReleaseCheckResult(
    val versionTag: String,
    val title: String,
    val notes: String,
    val apkUrl: String?,
    val htmlUrl: String,
    val hasUpdate: Boolean,
)

/**
 * 关于页面：展示项目标识、开源仓库链接、当前版本、更新代理选择与在线检查更新。
 */
@Composable
fun AboutScreen(
    appVersion: String,
    updateProxy: UpdateProxy,
    onUpdateProxyChange: (UpdateProxy) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var proxyMenuExpanded by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var checkError by remember { mutableStateOf("") }
    var releaseInfo by remember { mutableStateOf<ReleaseCheckResult?>(null) }

    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(-1f) }
    var downloadedApk by remember { mutableStateOf<File?>(null) }
    var status by remember { mutableStateOf("") }

    LaunchedEffect(status) {
        if (status.isNotEmpty()) {
            delay(3_200)
            status = ""
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            ScreenHeader("关于", onBack = onBack)

            Spacer(Modifier.height(6.dp))

            // 1. 品牌与版本卡片
            IslandCard(
                contentPadding = PaddingValues(20.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_logo),
                        contentDescription = "SodaM Mobile Logo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(60.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .border(
                                0.8.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                RoundedCornerShape(16.dp),
                            ),
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 16.dp),
                    ) {
                        Text(
                            text = "SodaM Mobile",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                            ),
                        )
                        Text(
                            text = "第三方汽水音乐 Android 客户端",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                                .padding(horizontal = 10.dp, vertical = 3.dp),
                        ) {
                            Text(
                                text = "当前版本 v${appVersion.removePrefix("v")}",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // 2. 项目仓库信息
            SectionLabel("开源仓库")
            IslandCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
                IslandNavRow(
                    title = "GitHub 仓库",
                    subtitle = REPO_URL,
                    onClick = { openUrlInBrowser(context, REPO_URL) },
                )
            }

            Spacer(Modifier.height(18.dp))

            // 3. 更新与代理配置
            SectionLabel("应用更新")
            IslandCard {
                // 更新代理下拉选择行（仅显示名称，不展示 URL）
                Box(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { proxyMenuExpanded = true }
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "更新代理",
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                            )
                            Text(
                                text = "用于检查 GitHub Release 与下载安装包",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
                                .border(
                                    0.8.dp,
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    RoundedCornerShape(12.dp),
                                )
                                .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                        ) {
                            Text(
                                text = updateProxy.label,
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "选择更新代理",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = proxyMenuExpanded,
                        onDismissRequest = { proxyMenuExpanded = false },
                    ) {
                        UpdateProxy.entries.forEach { option ->
                            val selected = option == updateProxy
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            text = option.label,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (selected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                        )
                                        if (selected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    proxyMenuExpanded = false
                                    onUpdateProxyChange(option)
                                    status = "更新代理已设为：${option.label}"
                                },
                            )
                        }
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 10.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                )

                // 版本检查按钮行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "版本检查",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        )
                        Text(
                            text = when {
                                checking -> "正在查询 GitHub 最新版本…"
                                releaseInfo?.hasUpdate == true -> "发现新版本：${releaseInfo?.versionTag}"
                                releaseInfo != null -> "当前已是最新版本（v${appVersion.removePrefix("v")}）"
                                checkError.isNotEmpty() -> checkError
                                else -> "当前版本 v${appVersion.removePrefix("v")}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (checkError.isNotEmpty()) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Button(
                        enabled = !checking && !downloading,
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp),
                        onClick = {
                            checking = true
                            checkError = ""
                            downloadedApk = null
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    fetchLatestRelease(appVersion, updateProxy)
                                }
                                checking = false
                                result.fold(
                                    onSuccess = { info ->
                                        releaseInfo = info
                                        status = if (info.hasUpdate) {
                                            "发现新版本 ${info.versionTag}"
                                        } else {
                                            "当前已是最新版本"
                                        }
                                    },
                                    onFailure = { err ->
                                        checkError = "检查失败：${err.message ?: "网络异常"}"
                                        status = "检查更新失败"
                                    },
                                )
                            }
                        },
                    ) {
                        if (checking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(if (checking) "检查中" else "检查更新", fontWeight = FontWeight.SemiBold)
                    }
                }

                // 发现新版本详情卡片
                val latest = releaseInfo
                AnimatedVisibility(visible = latest != null && latest.hasUpdate) {
                    if (latest != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 14.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
                                .border(
                                    0.8.dp,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                    RoundedCornerShape(16.dp),
                                )
                                .padding(14.dp),
                        ) {
                            Text(
                                text = latest.title.ifBlank { "发现新版本 ${latest.versionTag}" },
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary,
                            )
                            if (latest.notes.isNotBlank()) {
                                Text(
                                    text = latest.notes.trim(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }

                            if (downloading) {
                                Spacer(Modifier.height(12.dp))
                                if (downloadProgress in 0f..1f) {
                                    LinearProgressIndicator(
                                        progress = { downloadProgress },
                                        modifier = Modifier.fillMaxWidth().clip(CircleShape),
                                    )
                                    Text(
                                        text = "正在下载更新包 ${(downloadProgress * 100).toInt()}%…",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                } else {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clip(CircleShape))
                                    Text(
                                        text = "正在下载更新包…",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                            }

                            Spacer(Modifier.height(12.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (latest.apkUrl != null) {
                                    Button(
                                        enabled = !downloading,
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary,
                                        ),
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            val readyFile = downloadedApk
                                            if (readyFile != null && readyFile.isFile) {
                                                installApk(context, readyFile) { msg -> status = msg }
                                                return@Button
                                            }
                                            downloading = true
                                            downloadProgress = -1f
                                            scope.launch {
                                                val dlResult = withContext(Dispatchers.IO) {
                                                    downloadUpdateApk(
                                                        context = context,
                                                        rawUrl = latest.apkUrl,
                                                        proxy = updateProxy,
                                                        versionTag = latest.versionTag,
                                                        onProgress = { p -> downloadProgress = p },
                                                    )
                                                }
                                                downloading = false
                                                dlResult.fold(
                                                    onSuccess = { apk ->
                                                        downloadedApk = apk
                                                        installApk(context, apk) { msg -> status = msg }
                                                    },
                                                    onFailure = { err ->
                                                        status = "下载失败：${err.message ?: "请尝试更换更新代理"}"
                                                    },
                                                )
                                            }
                                        },
                                    ) {
                                        Text(
                                            text = when {
                                                downloading -> "下载中…"
                                                downloadedApk != null -> "点击安装"
                                                else -> "下载更新"
                                            },
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                                OutlinedButton(
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        openUrlInBrowser(context, latest.htmlUrl)
                                    },
                                ) {
                                    Text("打开发布页")
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }

        FloatingStatusPill(
            message = status,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        )
    }
}

private fun openUrlInBrowser(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}

private fun installApk(
    context: Context,
    apkFile: File,
    onFeedback: (String) -> Unit,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
        onFeedback("请先允许安装未知来源应用，授权后再点一次安装")
        runCatching {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
        }
        return
    }
    runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure {
        onFeedback("唤起安装器失败：${it.message ?: "请手动授权"}")
    }
}

private fun fetchLatestRelease(
    currentVersion: String,
    proxy: UpdateProxy,
): Result<ReleaseCheckResult> = runCatching {
    // 开启代理时优先尝试通过代理请求 GitHub API；若代理不支持 API，回退直连或通过代理查询 releases/latest 重定向。
    val apiCandidateUrls = buildList {
        if (proxy != UpdateProxy.Off) {
            add(proxy.wrapUrl(LATEST_RELEASE_API))
        }
        add(LATEST_RELEASE_API)
    }

    var json: JSONObject? = null
    var lastError: Throwable? = null

    for (endpoint in apiCandidateUrls) {
        val attempt = runCatching {
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                connectTimeout = 7_000
                readTimeout = 9_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "SodaM-Android")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    throw IllegalStateException("HTTP $code")
                }
                val text = conn.inputStream.bufferedReader().use { it.readText() }.trim()
                if (!text.startsWith("{")) {
                    throw IllegalStateException("返回内容非 JSON")
                }
                JSONObject(text)
            } finally {
                conn.disconnect()
            }
        }
        if (attempt.isSuccess) {
            json = attempt.getOrNull()
            break
        } else {
            lastError = attempt.exceptionOrNull()
        }
    }

    if (json != null) {
        val tagName = json.optString("tag_name").trim()
        if (tagName.isBlank()) throw IllegalStateException("未读取到 Release 版本号")
        val title = json.optString("name").trim()
        val body = json.optString("body").trim()
        val htmlUrl = json.optString("html_url").ifBlank { "$REPO_URL/releases/tag/$tagName" }
        var apkUrl: String? = null
        val assets = json.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val dl = asset.optString("browser_download_url")
                if (dl.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = dl
                    break
                }
            }
        }
        return@runCatching ReleaseCheckResult(
            versionTag = tagName,
            title = title,
            notes = body,
            apkUrl = apkUrl,
            htmlUrl = htmlUrl,
            hasUpdate = compareVersions(tagName, currentVersion) > 0,
        )
    }

    // 兜底：若 API 均被阻断，在开启代理时尝试通过 releases/latest 跳转链接解析最新 tag。
    if (proxy != UpdateProxy.Off) {
        val webConn = (URL(proxy.wrapUrl(LATEST_RELEASE_WEB)).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "SodaM-Android")
        }
        try {
            val code = webConn.responseCode
            val finalPath = webConn.url.toString()
            val html = if (code in 200..299) {
                webConn.inputStream.bufferedReader().use { it.readText() }
            } else {
                ""
            }
            val match = Regex("""/releases/tag/([^"'/?#<>\s]+)""").find(finalPath)
                ?: Regex("""/oversizexl/sodam-moblie/releases/tag/([^"'/?#<>\s]+)""").find(html)
            val tag = match?.groupValues?.getOrNull(1)?.trim()
            if (!tag.isNullOrBlank()) {
                val cleanVer = tag.removePrefix("v").removePrefix("V")
                return@runCatching ReleaseCheckResult(
                    versionTag = tag,
                    title = "SodaM Android $tag",
                    notes = "",
                    apkUrl = "$REPO_URL/releases/download/$tag/sodam_${cleanVer}_arm64.apk",
                    htmlUrl = "$REPO_URL/releases/tag/$tag",
                    hasUpdate = compareVersions(tag, currentVersion) > 0,
                )
            }
        } finally {
            webConn.disconnect()
        }
    }

    throw (lastError ?: IllegalStateException("无法连接 GitHub Releases"))
}

private fun downloadUpdateApk(
    context: Context,
    rawUrl: String,
    proxy: UpdateProxy,
    versionTag: String,
    onProgress: (Float) -> Unit,
): Result<File> = runCatching {
    val downloadUrl = proxy.wrapUrl(rawUrl)
    val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
    val safeTag = versionTag.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val target = File(updatesDir, "sodam-$safeTag.apk")
    val part = File(updatesDir, "sodam-$safeTag.apk.part")
    part.delete()

    val conn = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "SodaM-Android")
    }
    try {
        val code = conn.responseCode
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        val total = conn.contentLengthLong
        var readTotal = 0L
        conn.inputStream.use { input ->
            part.outputStream().use { output ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                    readTotal += n
                    if (total > 0L) {
                        onProgress((readTotal.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                    }
                }
            }
        }
        if (readTotal <= 0L) throw IllegalStateException("下载文件为空")
        target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        target
    } finally {
        conn.disconnect()
    }
}

internal fun compareVersions(latest: String, current: String): Int {
    fun parts(v: String): List<Int> =
        v.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
            .split('.')
            .map { it.toIntOrNull() ?: 0 }

    val a = parts(latest)
    val b = parts(current)
    val maxLen = maxOf(a.size, b.size)
    for (i in 0 until maxLen) {
        val ai = a.getOrElse(i) { 0 }
        val bi = b.getOrElse(i) { 0 }
        if (ai != bi) return ai.compareTo(bi)
    }
    return 0
}
