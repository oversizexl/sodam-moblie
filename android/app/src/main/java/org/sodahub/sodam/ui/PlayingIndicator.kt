package org.sodahub.sodam.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 列表里标记「正在播放」的三根音柱；暂停时保持静止。 */
@Composable
internal fun PlayingBars(active: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "playing")
    val first by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(420), RepeatMode.Reverse),
        label = "bar1",
    )
    val second by transition.animateFloat(
        initialValue = 0.9f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(tween(360), RepeatMode.Reverse),
        label = "bar2",
    )
    val third by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "bar3",
    )
    val heights = if (active) {
        listOf(first, second, third)
    } else {
        listOf(0.4f, 0.7f, 0.5f)
    }
    Row(
        modifier = modifier.height(16.dp).width(14.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        heights.forEach { fraction ->
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight(fraction)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.dp)),
            )
        }
    }
}
