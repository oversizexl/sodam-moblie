package org.sodahub.sodam.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin
import org.sodahub.sodam.R

/**
 * 丝滑按压弹性缩放手势（NeriPlayer 风格微交互）：
 * 按下时轻微内缩并给出回弹阻尼，松手恢复。
 */
internal fun Modifier.bounceClick(
    enabled: Boolean = true,
    scaleDown: Float = 0.96f,
    onClick: () -> Unit,
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) scaleDown else 1f,
        animationSpec = spring(
            dampingRatio = 0.65f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "bounce_scale",
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
}

/**
 * 岛屿分组卡片容器（Grouped Island Card）：
 * 大圆角 + 柔和容器底色 + 极细微高光描边，取代平铺列表与硬分割线。
 */
@Composable
internal fun IslandCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
    contentPadding: PaddingValues = PaddingValues(16.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val baseModifier = modifier
        .fillMaxWidth()
        .clip(shape)
        .border(
            width = 0.8.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
            shape = shape,
        )
        .let { m ->
            if (onClick != null) m.bounceClick(scaleDown = 0.98f, onClick = onClick) else m
        }

    Surface(
        modifier = baseModifier,
        shape = shape,
        color = containerColor,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            content = content,
        )
    }
}

/**
 * 分组标题：带有主色调标识的小号标题与可选右侧补充文案。
 */
@Composable
internal fun SectionLabel(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.4.sp,
            ),
            color = MaterialTheme.colorScheme.primary,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 卡片内选项药丸（Selectable Pill）：支持主题色高光动画过渡。
 */
@Composable
internal fun SelectablePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bgColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
        },
        label = "pill_bg",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "pill_fg",
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .border(
                width = if (selected) 0.dp else 0.8.dp,
                color = if (selected) Color.Transparent else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(14.dp),
            )
            .bounceClick(scaleDown = 0.94f, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = contentColor,
            maxLines = 1,
        )
    }
}

/**
 * 现代设置导航行：居中排布的标题/副标题 + 右侧值预览与箭头。
 */
@Composable
internal fun IslandNavRow(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    showArrow: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (trailing != null) {
            Box(Modifier.padding(start = 8.dp)) {
                trailing()
            }
        }
        if (showArrow) {
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(20.dp),
            )
        }
    }
}

/**
 * 胶囊步进调节器（Stepper）：取代丑陋的数字输入框 + 保存按钮，点击即生效。
 */
@Composable
internal fun StepperControl(
    valueText: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
            .border(
                width = 0.8.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(16.dp),
            )
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StepperButton(label = "−", enabled = canDecrease, onClick = onDecrease)
        Text(
            text = valueText,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        StepperButton(label = "+", enabled = canIncrease, onClick = onIncrease)
    }
}

@Composable
private fun StepperButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(
                if (enabled) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                },
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
            },
        )
    }
}

/**
 * 悬浮胶囊状态反馈条（Floating Status Pill）：带淡入滑出动效，不再以普通文字生硬占位。
 */
@Composable
internal fun FloatingStatusPill(
    message: String,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = message.isNotEmpty(),
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.92f),
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

private const val PLAY_PAUSE_MORPH_DURATION_MS = 380

private fun lerpFloat(start: Float, end: Float, fraction: Float): Float =
    start + (end - start) * fraction

/**
 * 可在设置页切换的 4 种高质感播放/暂停按钮动效样式（内置 Play / Pause 几何 Path Morph 形变）：
 * - [PlayButtonStyle.ProgressRing]：环绕歌曲进度微光圆环
 * - [PlayButtonStyle.FrostedPulse]：半透明磨砂玻璃外环与呼吸脉冲
 * - [PlayButtonStyle.CapsuleMorph]：正圆与宽椭圆胶囊弹性舒展
 * - [PlayButtonStyle.MinimalContrast]：极简高反差圆与瞬态扩散水波纹
 */
@Composable
internal fun MorphingPlayButton(
    isPlaying: Boolean,
    style: PlayButtonStyle = PlayButtonStyle.ProgressRing,
    progress: Float = 0f,
    enabled: Boolean = true,
    size: Dp = 62.dp,
    iconSize: Dp = 26.dp,
    onClick: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary

    // 单一形变进度源：同步驱动几何 Path Morph 与容器色调/轮廓过渡
    val morphProgress by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = tween(
            durationMillis = PLAY_PAUSE_MORPH_DURATION_MS,
            easing = FastOutSlowInEasing,
        ),
        label = "play_pause_morph",
    )

    val discColor = lerp(
        start = lerp(primary, MaterialTheme.colorScheme.surfaceContainerHigh, 0.16f),
        stop = primary,
        fraction = morphProgress,
    )

    when (style) {
        PlayButtonStyle.ProgressRing -> {
            val outerSize = size + 10.dp
            val animatedProgress by animateFloatAsState(
                targetValue = progress.coerceIn(0f, 1f),
                animationSpec = tween(180),
                label = "ring_progress",
            )
            val trackBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            Box(
                modifier = Modifier.size(outerSize),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.size(outerSize)) {
                    val strokePx = 2.2.dp.toPx()
                    drawCircle(
                        color = trackBg,
                        radius = (this.size.minDimension - strokePx) / 2f,
                        style = Stroke(width = strokePx),
                    )
                    if (animatedProgress > 0.002f) {
                        drawArc(
                            color = lerp(primary.copy(alpha = 0.55f), primary, morphProgress),
                            startAngle = -90f,
                            sweepAngle = 360f * animatedProgress,
                            useCenter = false,
                            style = Stroke(width = strokePx, cap = StrokeCap.Round),
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(size)
                        .shadow(
                            elevation = lerpFloat(4f, 10f, morphProgress).dp,
                            shape = CircleShape,
                            spotColor = primary.copy(alpha = 0.4f),
                        )
                        .clip(CircleShape)
                        .background(discColor)
                        .bounceClick(enabled = enabled, scaleDown = 0.89f, onClick = onClick),
                    contentAlignment = Alignment.Center,
                ) {
                    PlayPauseMorphIcon(
                        morphProgress = morphProgress,
                        iconSize = iconSize,
                        tint = onPrimary,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                    )
                }
            }
        }

        PlayButtonStyle.FrostedPulse -> {
            val infiniteTransition = rememberInfiniteTransition(label = "frosted_pulse")
            val rawPulse by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "pulse_val",
            )
            val pulseScale = lerpFloat(1f, 1.03f + rawPulse * 0.12f, morphProgress)
            val haloAlpha = lerpFloat(0.10f, 0.28f - rawPulse * 0.14f, morphProgress)
            val outerSize = size + 12.dp
            Box(
                modifier = Modifier.size(outerSize),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(size + 7.dp)
                        .graphicsLayer {
                            scaleX = pulseScale
                            scaleY = pulseScale
                        }
                        .clip(CircleShape)
                        .background(primary.copy(alpha = haloAlpha))
                        .border(0.75.dp, Color.White.copy(alpha = lerpFloat(0.14f, 0.24f, morphProgress)), CircleShape),
                )
                Box(
                    modifier = Modifier
                        .size(size)
                        .shadow(10.dp, CircleShape, spotColor = primary.copy(alpha = 0.45f))
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(lerp(discColor, Color.White, 0.10f), discColor),
                            ),
                        )
                        .border(0.75.dp, Color.White.copy(alpha = 0.22f), CircleShape)
                        .bounceClick(enabled = enabled, scaleDown = 0.89f, onClick = onClick),
                    contentAlignment = Alignment.Center,
                ) {
                    PlayPauseMorphIcon(
                        morphProgress = morphProgress,
                        iconSize = iconSize,
                        tint = onPrimary,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                    )
                }
            }
        }

        PlayButtonStyle.CapsuleMorph -> {
            val targetWidth = size * lerpFloat(1f, 1.38f, morphProgress)
            val elevation = lerpFloat(6f, 12f, morphProgress).dp
            Box(
                modifier = Modifier
                    .width(targetWidth)
                    .height(size)
                    .shadow(elevation, CircleShape, spotColor = primary.copy(alpha = 0.48f))
                    .clip(CircleShape)
                    .background(discColor)
                    .border(0.75.dp, Color.White.copy(alpha = lerpFloat(0.14f, 0.24f, morphProgress)), CircleShape)
                    .bounceClick(enabled = enabled, scaleDown = 0.90f, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                PlayPauseMorphIcon(
                    morphProgress = morphProgress,
                    iconSize = iconSize,
                    tint = onPrimary,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                )
            }
        }

        PlayButtonStyle.MinimalContrast -> {
            val rippleAnim = remember { Animatable(0f) }
            LaunchedEffect(isPlaying) {
                if (isPlaying) {
                    rippleAnim.snapTo(0f)
                    rippleAnim.animateTo(1f, tween(480, easing = FastOutSlowInEasing))
                }
            }
            val discBg = MaterialTheme.colorScheme.onBackground
            val discFg = MaterialTheme.colorScheme.background
            val outerSize = size + 12.dp
            Box(
                modifier = Modifier.size(outerSize),
                contentAlignment = Alignment.Center,
            ) {
                if (rippleAnim.value in 0.01f..0.99f) {
                    Box(
                        modifier = Modifier
                            .size(size)
                            .graphicsLayer {
                                val s = 1f + rippleAnim.value * 0.40f
                                scaleX = s
                                scaleY = s
                                alpha = (1f - rippleAnim.value) * 0.42f
                            }
                            .border(1.4.dp, discBg, CircleShape),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(size)
                        .shadow(10.dp, CircleShape, spotColor = discBg.copy(alpha = 0.28f))
                        .clip(CircleShape)
                        .background(discBg)
                        .bounceClick(enabled = enabled, scaleDown = 0.89f, onClick = onClick),
                    contentAlignment = Alignment.Center,
                ) {
                    PlayPauseMorphIcon(
                        morphProgress = morphProgress,
                        iconSize = iconSize,
                        tint = discFg,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                    )
                }
            }
        }
    }
}

/**
 * 基于 Canvas 几何顶点逐点插值（Path Morph）的 Play / Pause 图标：
 * 参考 Play-Pause Morph 原理，将播放三角形拆分为左右两个四边形，
 * 直接在 Canvas 上线性插值变形为暂停双竖条，消除静态图标切换的割裂感。
 */
@Composable
private fun PlayPauseMorphIcon(
    morphProgress: Float,
    iconSize: Dp,
    tint: Color,
    contentDescription: String,
) {
    val t = morphProgress.coerceIn(0f, 1f)
    val paint = remember { Paint().apply { isAntiAlias = true } }
    val playPath = remember { Path() }
    val leftPath = remember { Path() }
    val rightPath = remember { Path() }

    Canvas(
        modifier = Modifier.size(iconSize),
        contentDescription = contentDescription,
    ) {
        val w = this.size.minDimension
        val h = w

        paint.color = tint
        val cornerRadius = lerpFloat(w * 0.085f, w * 0.07f, t)
        paint.pathEffect = if (cornerRadius > 0.5f) {
            PathEffect.cornerPathEffect(cornerRadius)
        } else {
            null
        }

        // 中途形变瞬间轻微收束弹性，增强图标变换的实体张力
        val midPulseScale = 1f - 0.065f * sin(PI * t).toFloat()

        scale(scaleX = midPulseScale, scaleY = midPulseScale) {
            if (t <= 0.01f) {
                // Play 静止态：绘制单个圆润完整三角形，杜绝中间接缝
                playPath.reset()
                playPath.moveTo(w * 0.24f, h * 0.14f)
                playPath.lineTo(w * 0.88f, h * 0.50f)
                playPath.lineTo(w * 0.24f, h * 0.86f)
                playPath.close()

                drawIntoCanvas { canvas ->
                    canvas.drawPath(playPath, paint)
                }
            } else {
                // 分裂初期保留微弱内缘交叠，避免顶/底倒角在劈开初期出现凹口
                val overlap = lerpFloat(w * 0.018f, 0f, (t / 0.25f).coerceIn(0f, 1f))

                // 左半边：Play 左侧梯形 -> Pause 左竖条
                val lTopLeftX = lerpFloat(w * 0.24f, w * 0.20f, t)
                val lTopLeftY = lerpFloat(h * 0.14f, h * 0.16f, t)
                val lTopRightX = lerpFloat(w * 0.56f, w * 0.41f, t) + overlap
                val lTopRightY = lerpFloat(h * 0.32f, h * 0.16f, t)
                val lBotRightX = lerpFloat(w * 0.56f, w * 0.41f, t) + overlap
                val lBotRightY = lerpFloat(h * 0.68f, h * 0.84f, t)
                val lBotLeftX = lerpFloat(w * 0.24f, w * 0.20f, t)
                val lBotLeftY = lerpFloat(h * 0.86f, h * 0.84f, t)

                leftPath.reset()
                leftPath.moveTo(lTopLeftX, lTopLeftY)
                leftPath.lineTo(lTopRightX, lTopRightY)
                leftPath.lineTo(lBotRightX, lBotRightY)
                leftPath.lineTo(lBotLeftX, lBotLeftY)
                leftPath.close()

                // 右半边：Play 右侧尖三角 -> Pause 右竖条
                val rTopLeftX = lerpFloat(w * 0.56f, w * 0.59f, t) - overlap
                val rTopLeftY = lerpFloat(h * 0.32f, h * 0.16f, t)
                val rTopRightX = lerpFloat(w * 0.88f, w * 0.80f, t)
                val rTopRightY = lerpFloat(h * 0.50f, h * 0.16f, t)
                val rBotRightX = lerpFloat(w * 0.88f, w * 0.80f, t)
                val rBotRightY = lerpFloat(h * 0.50f, h * 0.84f, t)
                val rBotLeftX = lerpFloat(w * 0.56f, w * 0.59f, t) - overlap
                val rBotLeftY = lerpFloat(h * 0.68f, h * 0.84f, t)

                rightPath.reset()
                rightPath.moveTo(rTopLeftX, rTopLeftY)
                rightPath.lineTo(rTopRightX, rTopRightY)
                rightPath.lineTo(rBotRightX, rBotRightY)
                rightPath.lineTo(rBotLeftX, rBotLeftY)
                rightPath.close()

                drawIntoCanvas { canvas ->
                    canvas.drawPath(leftPath, paint)
                    canvas.drawPath(rightPath, paint)
                }
            }
        }
    }
}
