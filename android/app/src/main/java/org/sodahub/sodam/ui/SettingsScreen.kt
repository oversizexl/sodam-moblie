package org.sodahub.sodam.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.sodam_ffi.AccountInfo
import uniffi.sodam_ffi.SodaClient

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    client: SodaClient,
    account: AccountInfo?,
    qualityFile: String,
    cacheLimitFile: String,
    pageCount: Int,
    themeSummary: String,
    guideDebug: Boolean,
    backdropAlpha: Float = DEFAULT_BACKDROP_ALPHA,
    hapticLevel: HapticLevel = HapticLevel.Light,
    playButtonStyle: PlayButtonStyle = PlayButtonStyle.ProgressRing,
    bottomInset: Dp = 96.dp,
    onBackdropAlphaChange: (Float) -> Unit = {},
    onHapticLevelChange: (HapticLevel) -> Unit = {},
    onPlayButtonStyleChange: (PlayButtonStyle) -> Unit = {},
    onQualityChanged: () -> Unit = {},
    onPageCountChange: (Int) -> Unit,
    onOpenLogin: () -> Unit,
    onOpenTheme: () -> Unit,
    onGuideDebugChange: (Boolean) -> Unit,
    onLogout: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var quality by remember { mutableStateOf(readQuality(qualityFile)) }
    var cacheLimit by remember { mutableStateOf(readCacheLimit(cacheLimitFile)) }
    var cacheUsed by remember { mutableStateOf(0L) }
    var cacheBusy by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val isPresetLimit = remember(cacheLimit) { CACHE_LIMITS.any { it.first == cacheLimit } }
    var showCustomLimit by remember { mutableStateOf(!isPresetLimit) }
    var customMbInput by remember {
        mutableStateOf(if (isPresetLimit) "" else (cacheLimit / (1024 * 1024)).toString())
    }
    var cacheError by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }

    val dirPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            TrackDownloader.updateCustomDir(uri.toString())
            status = "下载目录已更新"
        }
    }

    LaunchedEffect(Unit) {
        cacheUsed = withContext(Dispatchers.IO) { client.cacheSizeBytes() }
    }

    // 档位/自定义改动后立刻生效，不用等重启。
    LaunchedEffect(cacheLimit) {
        withContext(Dispatchers.IO) { client.setCacheLimit(cacheLimit) }
    }

    LaunchedEffect(confirmClear) {
        if (confirmClear) {
            delay(3_500)
            confirmClear = false
        }
    }

    LaunchedEffect(status) {
        if (status.isNotEmpty()) {
            delay(3_000)
            status = ""
        }
    }

    LaunchedEffect(Unit) {
        TrackDownloader.events.collect { status = it }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            ScreenHeader("设置")

            Spacer(Modifier.height(4.dp))

            // 1. 个人账户卡片
            AccountIslandCard(
                account = account,
                onOpenLogin = onOpenLogin,
                onLogout = onLogout,
            )

            Spacer(Modifier.height(18.dp))

            // 2. 音频与音质卡片（即时向底层 SodaClient 与后台播放服务同步生效）
            SectionLabel("音频与音质", subtitle = "切换后下一首立即生效")
            IslandCard {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    QUALITY_OPTIONS.forEach { (tier, label) ->
                        SelectablePill(
                            label = label,
                            selected = quality == tier,
                            onClick = {
                                quality = tier
                                writeQuality(qualityFile, tier)
                                scope.launch {
                                    withContext(Dispatchers.IO) { client.setQualityPreference(tier) }
                                    onQualityChanged()
                                    status = "音质已切换为：$label（立即生效）"
                                }
                            },
                        )
                    }
                }
                val hint = QUALITY_HINTS[quality].orEmpty()
                if (hint.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // 3. 歌曲下载目录管理
            val customDir = TrackDownloader.customDirUri
            SectionLabel("歌曲下载", subtitle = "导出解密音频至本地目录")
            IslandCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
                IslandNavRow(
                    title = "下载保存位置",
                    subtitle = formatDownloadDirLabel(customDir),
                    onClick = { dirPickerLauncher.launch(null) },
                )
                if (!customDir.isNullOrBlank()) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 6.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "已使用自定义文件夹",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        SelectablePill(
                            label = "恢复默认",
                            selected = false,
                            onClick = {
                                TrackDownloader.updateCustomDir(null)
                                status = "已恢复默认下载目录 (Music/Sodam)"
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // 4. 存储与缓存可视化卡片
            val usageRatio = if (cacheLimit > 0L) {
                (cacheUsed.toFloat() / cacheLimit.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            val animatedRatio by animateFloatAsState(targetValue = usageRatio, label = "cache_ratio")
            val barColor by animateColorAsState(
                targetValue = if (usageRatio >= 0.85f) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                label = "cache_bar_color",
            )

            SectionLabel(
                title = "存储与缓存",
                subtitle = "已用 ${formatBytes(cacheUsed)} / ${formatBytes(cacheLimit)}",
            )
            IslandCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "本地试听缓存占用",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    )
                    Text(
                        text = "${(usageRatio * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = barColor,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 14.dp)
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(animatedRatio.coerceAtLeast(0.02f))
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(barColor),
                    )
                }

                Text(
                    text = "缓存上限（超出后自动清理较早歌曲）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    CACHE_LIMITS.forEach { (bytes, label) ->
                        SelectablePill(
                            label = label,
                            selected = !showCustomLimit && cacheLimit == bytes,
                            onClick = {
                                showCustomLimit = false
                                cacheError = ""
                                cacheLimit = bytes
                                writeCacheLimit(cacheLimitFile, bytes)
                                status = "缓存上限已设为 $label"
                            },
                        )
                    }
                    SelectablePill(
                        label = if (!isPresetLimit) {
                            "自定义 (${cacheLimit / (1024 * 1024)} MB)"
                        } else {
                            "自定义"
                        },
                        selected = showCustomLimit || !isPresetLimit,
                        onClick = { showCustomLimit = !showCustomLimit },
                    )
                }

                AnimatedVisibility(visible = showCustomLimit) {
                    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            OutlinedTextField(
                                value = customMbInput,
                                onValueChange = { customMbInput = it },
                                label = { Text("上限（MB）") },
                                singleLine = true,
                                isError = cacheError.isNotEmpty(),
                                shape = RoundedCornerShape(14.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(
                                shape = RoundedCornerShape(14.dp),
                                onClick = {
                                    val mb = customMbInput.trim().toLongOrNull()
                                    if (mb == null || mb !in MIN_CACHE_LIMIT_MB..MAX_CACHE_LIMIT_MB) {
                                        cacheError = "请输入 ${MIN_CACHE_LIMIT_MB}-${MAX_CACHE_LIMIT_MB} MB"
                                    } else {
                                        cacheError = ""
                                        val bytes = mb * 1024 * 1024
                                        cacheLimit = bytes
                                        writeCacheLimit(cacheLimitFile, bytes)
                                        showCustomLimit = false
                                        status = "缓存上限已设为 $mb MB"
                                    }
                                },
                            ) {
                                Text("应用")
                            }
                        }
                        if (cacheError.isNotEmpty()) {
                            Text(
                                text = cacheError,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                val clearBg by animateColorAsState(
                    targetValue = if (confirmClear) {
                        MaterialTheme.colorScheme.error.copy(alpha = 0.16f)
                    } else {
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
                    },
                    label = "clear_bg",
                )
                val clearFg by animateColorAsState(
                    targetValue = if (confirmClear) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    label = "clear_fg",
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(clearBg)
                        .border(
                            width = 0.8.dp,
                            color = if (confirmClear) {
                                MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                            } else {
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                            },
                            shape = RoundedCornerShape(14.dp),
                        )
                        .bounceClick(enabled = !cacheBusy, scaleDown = 0.97f) {
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
                        }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = when {
                            cacheBusy -> "正在清理缓存…"
                            confirmClear -> "再点一次确认清空本地缓存"
                            else -> "清空本地缓存"
                        },
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                        color = clearFg,
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            // 5. 外观与个性化卡片
            SectionLabel("外观与个性化")
            IslandCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
                IslandNavRow(
                    title = "主题与界面风格",
                    subtitle = themeSummary,
                    onClick = onOpenTheme,
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                )
                Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "播放页动态取色浓度",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        )
                        Text(
                            text = if (backdropAlpha <= 0.01f) "关闭" else "${(backdropAlpha * 100).toInt()}%",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        text = "调节首页动态封面流光背景与高斯模糊的融合透明度",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Slider(
                        value = backdropAlpha,
                        onValueChange = onBackdropAlphaChange,
                        valueRange = 0f..1f,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                )
                Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
                    var previewPlaying by remember { mutableStateOf(true) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = "播放按钮动效样式",
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                            )
                            Text(
                                text = playButtonStyle.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        MorphingPlayButton(
                            isPlaying = previewPlaying,
                            style = playButtonStyle,
                            progress = 0.65f,
                            size = 44.dp,
                            iconSize = 19.dp,
                            onClick = { previewPlaying = !previewPlaying },
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    ) {
                        PlayButtonStyle.entries.forEach { styleItem ->
                            SelectablePill(
                                label = styleItem.label,
                                selected = playButtonStyle == styleItem,
                                onClick = {
                                    onPlayButtonStyleChange(styleItem)
                                    status = "播放按钮已切换：${styleItem.label}"
                                },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // 6. 通用与实验室卡片
            SectionLabel("通用与偏好")
            IslandCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "首页每次刷新加载",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        )
                        Text(
                            text = "单页 $TRACKS_PER_PAGE 首，每次约加载 ${pageCount * TRACKS_PER_PAGE} 首",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    StepperControl(
                        valueText = "$pageCount 页",
                        canDecrease = pageCount > 1,
                        canIncrease = pageCount < MAX_PAGE_COUNT,
                        onDecrease = {
                            val next = (pageCount - 1).coerceAtLeast(1)
                            onPageCountChange(next)
                            status = "已设为每次加载 $next 页（约 ${next * TRACKS_PER_PAGE} 首）"
                        },
                        onIncrease = {
                            val next = (pageCount + 1).coerceAtMost(MAX_PAGE_COUNT)
                            onPageCountChange(next)
                            status = "已设为每次加载 $next 页（约 ${next * TRACKS_PER_PAGE} 首）"
                        },
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                )

                Column(Modifier.fillMaxWidth()) {
                    Text(
                        text = "切歌震动触感",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    )
                    Text(
                        text = "上下滑动切换曲目落定时的微震反馈强度",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        HapticLevel.entries.forEach { level ->
                            SelectablePill(
                                label = level.label,
                                selected = hapticLevel == level,
                                onClick = {
                                    onHapticLevelChange(level)
                                    performSodamHaptic(context, level)
                                    status = "切歌触感已设为：${level.label}"
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onGuideDebugChange(!guideDebug) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "启动时显示滑动引导",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        )
                        Text(
                            text = "调试选项：每次启动都弹出上下切歌提示",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Switch(
                        checked = guideDebug,
                        onCheckedChange = onGuideDebugChange,
                    )
                }
            }

            Spacer(Modifier.height(bottomInset))
        }

        FloatingStatusPill(
            message = status,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomInset + 8.dp),
        )
    }
}

@Composable
private fun AccountIslandCard(
    account: AccountInfo?,
    onOpenLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val gradient = Brush.linearGradient(
        colors = listOf(
            primary.copy(alpha = 0.16f),
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(gradient)
            .border(
                width = 0.9.dp,
                color = primary.copy(alpha = 0.25f),
                shape = RoundedCornerShape(24.dp),
            )
            .padding(16.dp),
    ) {
        if (account != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .border(2.dp, primary.copy(alpha = 0.55f), CircleShape)
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    if (account.avatar.isNotBlank()) {
                        AsyncImage(
                            model = account.avatar,
                            contentDescription = "头像",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                        )
                    } else {
                        Text(
                            text = account.nickname.take(1).ifBlank { "S" },
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = primary,
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 14.dp),
                ) {
                    Text(
                        text = account.nickname.ifBlank { "已登录账号" },
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                        ),
                    )
                    Spacer(Modifier.height(4.dp))
                    val badgeColor = if (account.vip) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(badgeColor.copy(alpha = 0.16f))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = if (account.vip) "VIP 尊享会员" else "标准用户",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = badgeColor,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
                        .border(
                            0.8.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            RoundedCornerShape(14.dp),
                        )
                        .bounceClick(scaleDown = 0.94f, onClick = onLogout)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Text(
                        text = "退出",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "S",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = primary,
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 14.dp),
                ) {
                    Text(
                        text = "未登录",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    )
                    Text(
                        text = "登录后同步歌单、收藏与无损音质",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Button(
                    onClick = onOpenLogin,
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = primary),
                ) {
                    Text("登录", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
