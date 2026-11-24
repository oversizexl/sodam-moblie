package org.sodahub.sodam.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 主题二级页（参考 NeriPlayer）：明暗模式、界面风格（简洁 / Material You）、
 * 主题色、调色板风格。
 * 选中即生效并持久化。
 */
@OptIn(ExperimentalMaterial3Api::class)
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
        ScreenHeader("主题设置", onBack = onBack)

        Text("外观模式", style = MaterialTheme.typography.titleSmall)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            THEME_MODES.forEach { (value, label) ->
                FilterChip(
                    selected = themeMode == value,
                    onClick = { onModeChange(value) },
                    label = { Text(label) },
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        Text("界面风格", style = MaterialTheme.typography.titleSmall)
        Text(
            "简洁：中性底色与更大圆角；Material You：强调色贯穿界面",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
        )
        UI_STYLES.forEach { (value, info) ->
            val (label, description) = info
            ListItem(
                headlineContent = { Text(label) },
                supportingContent = { Text(description) },
                trailingContent = {
                    if (uiStyle == value) {
                        Text("已选", color = MaterialTheme.colorScheme.primary)
                    }
                },
                modifier = Modifier.clickable { onUiStyleChange(value) },
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        Text(
            "主题色 · ${themeColorLabel(themeColorHex)}",
            style = MaterialTheme.typography.titleSmall,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 10.dp),
        ) {
            THEME_COLOR_PRESETS.forEach { (hex, label) ->
                val selected = themeColorHex.equals(hex, ignoreCase = true)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF000000 or hex.toLong(16)))
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                                shape = CircleShape,
                            )
                            .clickable { onColorChange(hex) },
                    )
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        Text("调色板风格", style = MaterialTheme.typography.titleSmall)
        Text(
            "影响强调色的饱和度和明暗关系",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
        )
        THEME_STYLES.forEach { option ->
            ListItem(
                headlineContent = { Text(option.label) },
                supportingContent = { Text(option.description) },
                trailingContent = {
                    if (paletteStyle == option.value) {
                        Text("已选", color = MaterialTheme.colorScheme.primary)
                    }
                },
                modifier = Modifier.clickable { onPaletteStyleChange(option.value) },
            )
        }

        Text("", modifier = Modifier.padding(bottom = 24.dp))
    }
}
