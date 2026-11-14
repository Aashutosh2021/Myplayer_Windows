package com.example.myplayer.ui.screens.nowplaying

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myplayer.data.lyrics.LyricsScript
import com.example.myplayer.ui.myplayer.MyPlayerAccentGreen
import com.example.myplayer.ui.myplayer.MyPlayerAccentPurple
import com.example.myplayer.ui.myplayer.rememberMyPlayerUiStateHolder
import com.example.myplayer.ui.theme.*

/**
 * Lyrics tab for NowPlayingScreen.
 * Displays synced lyrics with real-time line highlighting, Lyrics Shader gradient,
 * and High Quality Bloom glow from Appearance settings.
 */
@Composable
fun LyricsTab(
    lyricsState: LyricsUiState,
    positionMs: State<Long>,
    onSeek: (Long) -> Unit = {},
    onAddCustomLyrics: () -> Unit = {},
    onRetryLyrics: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isDark = ThemeState.isDarkMode
    val textColor = if (isDark) Color.White else CharcoalPrimary
    val textMuted = if (isDark) Color.White.copy(alpha = 0.7f) else SlateSecondary
    val iconBg = if (isDark) Color.White.copy(alpha = 0.08f) else CharcoalPrimary.copy(alpha = 0.06f)
    val iconTint = if (isDark) Color.White.copy(alpha = 0.6f) else CharcoalPrimary.copy(alpha = 0.6f)
    val btnBg = if (isDark) Color.White.copy(alpha = 0.15f) else CharcoalPrimary
    val btnText = if (isDark) Color.White else PureWhite

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent),
        contentAlignment = Alignment.TopCenter
    ) {
        when (lyricsState) {
            is LyricsUiState.Idle,
            is LyricsUiState.Loading -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (lyricsState is LyricsUiState.Loading) {
                        CircularProgressIndicator(color = textColor, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("Fetching lyrics...", style = MaterialTheme.typography.bodyMedium, color = textMuted)
                    } else {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(iconBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.MusicNote, null, tint = iconTint, modifier = Modifier.size(32.dp))
                        }
                        Spacer(Modifier.height(16.dp))
                        Text("Play a song to see lyrics", style = MaterialTheme.typography.bodyMedium, color = textMuted)
                    }
                }
            }

            is LyricsUiState.NotFound -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(iconBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.MusicNote, null, tint = iconTint, modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("No lyrics found", style = MaterialTheme.typography.titleMedium, color = textColor)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Lyrics not available online for this song",
                        style = MaterialTheme.typography.bodySmall,
                        color = textMuted
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = onRetryLyrics,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = btnBg,
                                contentColor = btnText
                            ),
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Retry Lyrics",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Retry",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = btnText
                            )
                        }

                        OutlinedButton(
                            onClick = onAddCustomLyrics,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = textColor
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, textColor.copy(alpha = 0.3f)),
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                Icons.Filled.EditNote,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Add Custom",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = textColor
                            )
                        }
                    }
                }
            }

            is LyricsUiState.Found -> {
                if (lyricsState.syncedLines.isNotEmpty()) {
                    SyncedLyricsView(
                        lines = lyricsState.syncedLines,
                        positionMs = positionMs,
                        onSeek = onSeek
                    )
                } else {
                    PlainLyricsView(text = lyricsState.result.plainLyrics ?: "")
                }

                LyricsScriptPicker(modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 8.dp, end = 48.dp))

                IconButton(
                    onClick = onAddCustomLyrics,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(32.dp)
                ) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "Edit Lyrics",
                        tint = textMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncedLyricsView(
    lines: List<Pair<Long, String>>,
    positionMs: State<Long>,
    onSeek: (Long) -> Unit
) {
    val isDark = ThemeState.isDarkMode
    val baseColor = if (isDark) Color.White else CharcoalPrimary
    val myplayerState = rememberMyPlayerUiStateHolder()

    val infinite = rememberInfiniteTransition(label = "lyricsShaderAnim")
    val shaderShift by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 520f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "lyricShaderShift"
    )

    val activeIndexState = remember(lines) {
        derivedStateOf {
            val pos = positionMs.value
            var lo = 0
            var hi = lines.size - 1
            var idx = -1
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                if (lines[mid].first <= pos) { idx = mid; lo = mid + 1 } else hi = mid - 1
            }
            idx
        }
    }
    val activeIndex by activeIndexState

    val listState = rememberLazyListState()

    LaunchedEffect(lines) {
        snapshotFlow { activeIndexState.value }.collect { index ->
            if (index >= 0) {
                val target = (index - 2).coerceAtLeast(0)
                if (myplayerState.smoothAnimations) {
                    listState.animateScrollToItem(index = target)
                } else {
                    listState.scrollToItem(index = target)
                }
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        itemsIndexed(lines, key = { index, _ -> "synced_$index" }) { index, (timestampMs, lyricLine) ->
            val isActive = index == activeIndex
            val isPast = index < activeIndex

            val scale by animateFloatAsState(
                targetValue = if (isActive) 1.15f else 1f,
                animationSpec = if (myplayerState.smoothAnimations) {
                    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
                } else {
                    snap()
                },
                label = "lyricScale"
            )
            val color by animateColorAsState(
                targetValue = when {
                    isActive -> baseColor
                    isPast -> baseColor.copy(alpha = 0.55f)
                    else -> baseColor.copy(alpha = 0.35f)
                },
                animationSpec = if (myplayerState.smoothAnimations) tween(300) else snap(),
                label = "lyricColor"
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .then(
                        if (isActive && myplayerState.highQualityBloom) {
                            Modifier.background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        MyPlayerAccentGreen.copy(alpha = 0.18f),
                                        MyPlayerAccentPurple.copy(alpha = 0.16f),
                                        Color.Transparent
                                    )
                                )
                            )
                        } else Modifier
                    )
                    .clickable { onSeek(timestampMs) }
                    .padding(vertical = 6.dp, horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                val activeShadow = if (isActive && myplayerState.highQualityBloom) {
                    Shadow(
                        color = MyPlayerAccentGreen.copy(alpha = 0.72f),
                        offset = Offset.Zero,
                        blurRadius = 26f
                    )
                } else null

                val textStyle = if (isActive && myplayerState.lyricsShader) {
                    MaterialTheme.typography.bodyLarge.copy(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                baseColor,
                                MyPlayerAccentGreen,
                                Color(0xFF38BDF8),
                                baseColor
                            ),
                            start = Offset(shaderShift, 0f),
                            end = Offset(shaderShift + 240f, 60f)
                        ),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 17.sp,
                        lineHeight = 26.sp,
                        shadow = activeShadow
                    )
                } else {
                    MaterialTheme.typography.bodyLarge.copy(
                        color = color,
                        fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Normal,
                        fontSize = 16.sp,
                        lineHeight = 26.sp,
                        shadow = activeShadow
                    )
                }

                Text(
                    text = lyricLine.ifBlank { "•" },
                    style = textStyle,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                )
            }
        }
    }
}

@Composable
private fun PlainLyricsView(text: String) {
    val isDark = ThemeState.isDarkMode
    val textColor = if (isDark) Color.White else CharcoalPrimary

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val linesOfText = text.lines()
        items(linesOfText.size, key = { "plain_$it" }) { i ->
            Text(
                text = linesOfText[i],
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp, lineHeight = 26.sp),
                color = textColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** Original / Hinglish / हिन्दी / اردو chips; hidden for English lyrics. */
@Composable
private fun LyricsScriptPicker(modifier: Modifier = Modifier, viewModel: LyricsViewModel = hiltViewModel()) {
    val canConvert by viewModel.canConvertScript.collectAsStateWithLifecycle()
    if (!canConvert) return
    val mode by viewModel.scriptMode.collectAsStateWithLifecycle()
    val converting by viewModel.converting.collectAsStateWithLifecycle()
    val isDark = ThemeState.isDarkMode
    val fg = if (isDark) Color.White else CharcoalPrimary

    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LyricsScript.values().forEach { option ->
            val selected = option == mode
            Text(
                text = option.label,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) (if (isDark) CharcoalPrimary else PureWhite) else fg.copy(alpha = 0.75f),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) fg else fg.copy(alpha = 0.10f))
                    .clickable { viewModel.setScriptMode(option) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
        if (converting) CircularProgressIndicator(color = fg, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
    }
}
