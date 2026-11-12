package com.example.myplayer.ui.components


import com.example.myplayer.ui.theme.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sin

/**
 * Modern Waveform Audio Seeker matching Image 1 Left.
 * Features an interactive amplitude-bar waveform with elapsed and total time indicators.
 */
@Composable
fun WaveformAudioSeeker(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val duration = if (durationMs > 0L) durationMs else 1L
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }

    val rawProgress = if (durationMs > 0L) (positionMs.toFloat() / duration.toFloat()) else 0f
    val actualProgress = if (rawProgress.isNaN() || rawProgress.isInfinite()) 0f else rawProgress.coerceIn(0f, 1f)
    val safeDragProgress = if (dragProgress.isNaN() || dragProgress.isInfinite()) 0f else dragProgress.coerceIn(0f, 1f)
    val currentProgress = (if (isDragging) safeDragProgress else actualProgress).coerceIn(0f, 1f)

    val barCount = 42

    // Pre-calculate smooth visual wave heights
    val normalizedHeights = remember {
        FloatArray(barCount) { index ->
            val angle = (index.toFloat() / barCount.toFloat()) * (2f * Math.PI.toFloat())
            val base = 0.35f + 0.35f * sin(angle * 2.5f) + 0.15f * sin(angle * 5f)
            base.coerceIn(0.18f, 1.0f)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .pointerInput(duration) {
                    detectTapGestures { offset ->
                        if (size.width <= 0) return@detectTapGestures
                        val rawProg = offset.x / size.width
                        val newProgress = if (rawProg.isNaN() || rawProg.isInfinite()) 0f else rawProg.coerceIn(0f, 1f)
                        if (durationMs > 0L) onSeek((newProgress * duration).toLong())
                    }
                }
                .pointerInput(duration) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            if (size.width <= 0) return@detectDragGestures
                            isDragging = true
                            val rawDrag = offset.x / size.width
                            dragProgress = if (rawDrag.isNaN() || rawDrag.isInfinite()) 0f else rawDrag.coerceIn(0f, 1f)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            if (size.width > 0) {
                                val delta = change.position.x / size.width
                                if (!delta.isNaN() && !delta.isInfinite()) {
                                    dragProgress = delta.coerceIn(0f, 1f)
                                }
                            }
                        },
                        onDragEnd = {
                            val safeProgress = if (dragProgress.isNaN() || dragProgress.isInfinite()) 0f else dragProgress.coerceIn(0f, 1f)
                            if (durationMs > 0L) onSeek((safeProgress * duration).toLong())
                            isDragging = false
                        },
                        onDragCancel = {
                            isDragging = false
                        }
                    )
                }
        ) {
            if (size.width <= 0f || size.height <= 0f) return@Canvas
            val totalWidth = size.width
            val totalHeight = size.height
            val barWidth = 3.5.dp.toPx()
            val totalBarsWidth = barCount * barWidth
            val spacing = ((totalWidth - totalBarsWidth) / (barCount - 1)).let { if (it.isNaN() || it.isInfinite()) 1.5.dp.toPx() else it.coerceAtLeast(1.5.dp.toPx()) }

            for (i in 0 until barCount) {
                val fraction = i.toFloat() / (barCount - 1).toFloat()
                val isPlayed = fraction <= currentProgress

                val barHeight = normalizedHeights[i] * (totalHeight * 0.85f)
                val x = i * (barWidth + spacing)
                val y = (totalHeight - barHeight) / 2f

                drawRoundRect(
                    color = if (isPlayed) Primary else Color(0xFFD6DBE4),
                    topLeft = Offset(x, y),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // Time indicators (Elapsed on left, Total on right)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val displayMs = if (isDragging) (dragProgress * duration).toLong() else positionMs
            Text(
                text = formatDuration(displayMs),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp
                ),
                color = MaterialTheme.colorScheme.secondary
            )
            Text(
                text = formatDuration(duration),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp
                ),
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}


