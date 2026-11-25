package org.sodahub.sodam.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 主题二级页（参考 NeriPlayer 分组岛屿卡片）：明暗模式、界面风格、主题色、调色板风格。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemeScreen(
    themeMode: String,
    themeColorHex: String,
    paletteStyle: String,
    uiStyle: String,
    onModeChange: (String) -> Unit,
    onColorChange: (String) -> Unit,
    onPaletteStyleChange: (String) -> Unit,
    onUiStyleChange: (String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        ScreenHeader("主题与配色", onBack = onBack)

        Spacer(Modifier.height(4.dp))

        // 1. 外观模式
        SectionLabel("外观模式")
        IslandCard {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                THEME_MODES.forEach { (value, label) ->
                    SelectablePill(
                        label = label,
                        selected = themeMode == value,
                        onClick = { onModeChange(value) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        // 2. 界面风格
        SectionLabel("界面风格")
        IslandCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                UI_STYLES.forEach { (value, info) ->
                    val (label, description) = info
                    val selected = uiStyle == value
                    StyleOptionTile(
                        title = label,
                        description = description,
                        selected = selected,
                        onClick = { onUiStyleChange(value) },
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        // 3. 主题色盘
        SectionLabel("主题色", subtitle = themeColorLabel(themeColorHex))
        IslandCard {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                THEME_COLOR_PRESETS.forEach { (hex, label) ->
                    val selected = themeColorHex.equals(hex, ignoreCase = true)
                    val swatch = Color(0xFF000000 or hex.toLong(16))
                    val checkTint = if (swatch.luminance() > 0.55f) Color.Black else Color.White

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.bounceClick(scaleDown = 0.9f) { onColorChange(hex) },
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(swatch)
                                .border(
                                    width = if (selected) 2.5.dp else 1.dp,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                    },
                                    shape = CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = "已选",
                                    tint = checkTint,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            ),
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(top = 5.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        // 4. 调色板风格
        SectionLabel("调色板算法", subtitle = "影响全局饱和度与层次")
        IslandCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                THEME_STYLES.forEach { option ->
                    StyleOptionTile(
                        title = option.label,
                        description = option.description,
                        selected = paletteStyle == option.value,
                        onClick = { onPaletteStyleChange(option.value) },
                    )
                }
            }
        }

        Spacer(Modifier.height(36.dp))
    }
}

@Composable
private fun StyleOptionTile(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bgColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
        },
        label = "tile_bg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        },
        label = "tile_border",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .bounceClick(scaleDown = 0.98f, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                ),
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}
