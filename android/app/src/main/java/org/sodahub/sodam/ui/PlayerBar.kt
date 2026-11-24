package org.sodahub.sodam.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import uniffi.sodam_ffi.Track

/**
 * 底部播放条：三个 Tab 共用。整块可点进详情。
 */
@Composable
internal fun PlayerBar(
    track: Track,
    isPlaying: Boolean,
    positionMs: State<Long>,
    durationMs: State<Long>,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpen: () -> Unit,
) {
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val position = positionMs.value
    val duration = durationMs.value
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onOpen),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = track.cover,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                contentScale = ContentScale.Crop,
            )
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(track.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    track.artist,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            TextButton(onClick = onPrevious) { Text("上一首") }
            Button(onClick = onToggle) { Text(if (isPlaying) "暂停" else "播放") }
            TextButton(onClick = onNext) { Text("下一首") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = dragValue ?: position.toFloat(),
                onValueChange = { dragValue = it },
                onValueChangeFinished = {
                    dragValue?.let { onSeek(it.toLong()) }
                    dragValue = null
                },
                valueRange = 0f..(if (duration > 0) duration.toFloat() else 1f),
                modifier = Modifier.weight(1f),
            )
            Text(
                "${formatDuration(position)}/${formatDuration(duration)}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
