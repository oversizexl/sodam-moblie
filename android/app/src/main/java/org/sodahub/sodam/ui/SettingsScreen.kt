package org.sodahub.sodam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.sodam_ffi.AccountInfo
import uniffi.sodam_ffi.SodaClient

@Composable
fun SettingsScreen(
    client: SodaClient,
    account: AccountInfo?,
    qualityFile: String,
    cacheLimitFile: String,
    pageCount: Int,
    themeSummary: String,
    guideDebug: Boolean,
    onPageCountChange: (Int) -> Unit,
    onOpenLogin: () -> Unit,
    onOpenTheme: () -> Unit,
    onGuideDebugChange: (Boolean) -> Unit,
    onLogout: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var quality by remember { mutableStateOf(readQuality(qualityFile)) }
    var cacheLimit by remember { mutableStateOf(readCacheLimit(cacheLimitFile)) }
    var cacheUsed by remember { mutableStateOf(0L) }
    var cacheBusy by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var pagesInput by remember(pageCount) { mutableStateOf(pageCount.toString()) }
    var pagesError by remember { mutableStateOf("") }
    var customMbInput by remember {
        // 自定义上限回显上次保存的 MB 数；预设档位则留空。
        val preset = CACHE_LIMITS.any { it.first == cacheLimit }
        mutableStateOf(if (preset) "" else (cacheLimit / (1024 * 1024)).toString())
    }
    var cacheError by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        cacheUsed = withContext(Dispatchers.IO) { client.cacheSizeBytes() }
    }

    // 档位/自定义改动后立刻生效，不用等重启。
    LaunchedEffect(cacheLimit) {
        withContext(Dispatchers.IO) { client.setCacheLimit(cacheLimit) }
    }

    // 提示 3 秒后自动消失。
    LaunchedEffect(status) {
        if (status.isNotEmpty()) {
            delay(3_000)
            status = ""
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        ScreenHeader("设置")

        if (account != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (account.avatar.isNotBlank()) {
                    AsyncImage(
                        model = account.avatar,
                        contentDescription = "头像",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp).clip(CircleShape),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            account.nickname.take(1).ifBlank { "?" },
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(account.nickname.ifBlank { "已登录" }, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (account.vip) "VIP" else "普通用户",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onLogout) { Text("退出登录") }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("未登录", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Button(onClick = onOpenLogin) { Text("登录") }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        Text("音质", style = MaterialTheme.typography.titleSmall)
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 4.dp),
        ) {
            QUALITY_OPTIONS.forEach { (tier, label) ->
                FilterChip(
                    selected = quality == tier,
                    onClick = {
                        quality = tier
                        writeQuality(qualityFile, tier)
                        status = "音质已切换（新歌生效）：$label"
                    },
                    label = { Text(label) },
                )
            }
        }
        Text(
            QUALITY_HINTS[quality].orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        Text("缓存", style = MaterialTheme.typography.titleSmall)
        Text(
            "已用 ${formatBytes(cacheUsed)}",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            "上限（超出后自动清理较早缓存）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 4.dp),
        ) {
            CACHE_LIMITS.forEach { (bytes, label) ->
                FilterChip(
                    selected = cacheLimit == bytes,
                    onClick = {
                        cacheLimit = bytes
                        writeCacheLimit(cacheLimitFile, bytes)
                    },
                    label = { Text(label) },
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            OutlinedTextField(
                value = customMbInput,
                onValueChange = { customMbInput = it },
                label = { Text("自定义上限（MB）") },
                singleLine = true,
                isError = cacheError.isNotEmpty(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    val mb = customMbInput.trim().toLongOrNull()
                    if (mb == null || mb !in MIN_CACHE_LIMIT_MB..MAX_CACHE_LIMIT_MB) {
                        cacheError = "请输入 ${MIN_CACHE_LIMIT_MB}-${MAX_CACHE_LIMIT_MB} MB"
                    } else {
                        cacheError = ""
                        val bytes = mb * 1024 * 1024
                        cacheLimit = bytes
                        writeCacheLimit(cacheLimitFile, bytes)
                        status = "缓存上限已设为 ${mb} MB"
                    }
                },
                modifier = Modifier.padding(start = 8.dp),
            ) { Text("保存") }
        }
        if (cacheError.isNotEmpty()) {
            Text(cacheError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        }
        TextButton(
            onClick = {
                if (!confirmClear) {
                    confirmClear = true
                } else {
                    confirmClear = false
                    cacheBusy = true
                    scope.launch {
                        val freed = withContext(Dispatchers.IO) { client.clearCache() }
                        cacheUsed = withContext(Dispatchers.IO) { client.cacheSizeBytes() }
                        cacheBusy = false
                        status = "已清空缓存（释放 ${formatBytes(freed)}）"
                    }
                }
            },
            enabled = !cacheBusy,
        ) {
            Text(
                if (confirmClear) "再点一次确认清空" else "清空缓存",
                color = if (confirmClear) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        Text("每次加载页数", style = MaterialTheme.typography.titleSmall)
        Text(
            "每页 $TRACKS_PER_PAGE 首，当前刷新加载约 ${pageCount * TRACKS_PER_PAGE} 首；保存后点首页刷新生效。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            OutlinedTextField(
                value = pagesInput,
                onValueChange = { pagesInput = it },
                label = { Text("页数（1-$MAX_PAGE_COUNT）") },
                singleLine = true,
                isError = pagesError.isNotEmpty(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    val n = pagesInput.trim().toIntOrNull()
                    if (n == null || n !in 1..MAX_PAGE_COUNT) {
                        pagesError = "请输入 1-$MAX_PAGE_COUNT"
                    } else {
                        pagesError = ""
                        onPageCountChange(n)
                        status = "页数已保存：$n 页（刷新时加载 ${n * TRACKS_PER_PAGE} 首）"
                    }
                },
                modifier = Modifier.padding(start = 8.dp),
            ) { Text("保存") }
        }
        if (pagesError.isNotEmpty()) {
            Text(pagesError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        ListItem(
            headlineContent = { Text("主题设置") },
            supportingContent = { Text(themeSummary) },
            modifier = Modifier.clickable(onClick = onOpenTheme),
        )

        ListItem(
            headlineContent = { Text("每次启动显示滑动引导（调试）") },
            supportingContent = { Text("打开后每次打开 App 都弹出上滑/下滑切歌提示") },
            trailingContent = {
                Switch(checked = guideDebug, onCheckedChange = onGuideDebugChange)
            },
            modifier = Modifier.clickable { onGuideDebugChange(!guideDebug) },
        )

        if (status.isNotEmpty()) {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }

        Text("", modifier = Modifier.padding(bottom = 24.dp))
    }
}
