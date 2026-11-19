package com.example.myplayer.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myplayer.BuildConfig
import com.example.myplayer.R
import com.example.myplayer.data.update.InAppDownloadState
import com.example.myplayer.data.update.InAppUpdateManager
import com.example.myplayer.data.update.UpdateChecker
import com.example.myplayer.data.update.UpdateInfo
import java.awt.Desktop
import java.net.URI

private val SheetBg = Color(0xFF121318)
private val CardBg = Color(0xFF1C1E26)
private val CardBorder = Color(0xFF2B2E3B)
private val AccentGreen = Color(0xFF9CF58A)
private val AccentBlue = Color(0xFF5E9EFF)

/**
 * Desktop-optimized In-App Update Dialog for MyPlayer Windows.
 */
@Composable
fun MyPlayerInAppUpdateDialog(
    updateInfo: UpdateInfo,
    inAppUpdateManager: InAppUpdateManager,
    onDismiss: () -> Unit,
    onSnooze24h: () -> Unit
) {
    val downloadState by inAppUpdateManager.downloadState.collectAsStateWithLifecycle()

    LaunchedEffect(updateInfo.tagName) {
        inAppUpdateManager.syncExistingDownloadState(updateInfo)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .widthIn(min = 440.dp, max = 560.dp)
                .wrapContentHeight()
                .clip(RoundedCornerShape(24.dp))
                .border(1.dp, CardBorder, RoundedCornerShape(24.dp)),
            color = SheetBg,
            tonalElevation = 12.dp,
            shadowElevation = 28.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
                    .animateContentSize()
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(AccentGreen.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SystemUpdate,
                                contentDescription = null,
                                tint = AccentGreen,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Update Available",
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    color = if (updateInfo.isPreRelease) Color(0xFFFFB74D).copy(alpha = 0.2f) else AccentGreen.copy(alpha = 0.18f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = if (updateInfo.isPreRelease) "BETA" else "STABLE",
                                        color = if (updateInfo.isPreRelease) Color(0xFFFFB74D) else AccentGreen,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = "v${BuildConfig.VERSION_NAME}  →  ${updateInfo.versionName}",
                                color = Color.White.copy(alpha = 0.65f),
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White.copy(alpha = 0.7f))
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Package Info Card
                Surface(
                    color = CardBg,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, CardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource(R.drawable.app_icon_spark),
                            contentDescription = "MyPlayer",
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = updateInfo.apkName ?: "MyPlayer-${updateInfo.tagName}.msi",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Windows x64 • ${InAppUpdateManager.formatBytes(updateInfo.apkSizeBytes)}",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                if (updateInfo.releaseNotes.isNotBlank()) {
                    Spacer(Modifier.height(14.dp))
                    Surface(
                        color = CardBg,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, CardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "What's Changed",
                                color = AccentBlue,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = updateInfo.releaseNotes.take(650),
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 12.5.sp,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                // Primary Animated Action Button
                when (val state = downloadState) {
                    is InAppDownloadState.Downloading -> {
                        val animProgress by animateFloatAsState(
                            targetValue = state.progress.coerceIn(0f, 1f),
                            animationSpec = tween(180),
                            label = "downloadProgress"
                        )
                        val pct = (state.progress * 100f).toInt().coerceIn(0, 100)
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(CardBg)
                                    .border(1.dp, AccentGreen.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(animProgress)
                                        .background(AccentGreen.copy(alpha = 0.25f))
                                )
                                Row(
                                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Downloading… $pct%",
                                        color = AccentGreen,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = "${InAppUpdateManager.formatBytes(state.downloadedBytes)} / ${InAppUpdateManager.formatBytes(state.totalBytes)}",
                                        color = Color.White.copy(alpha = 0.75f),
                                        fontSize = 12.sp
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { animProgress },
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                                color = AccentGreen,
                                trackColor = CardBorder
                            )
                            Spacer(Modifier.height(8.dp))
                            TextButton(
                                onClick = { inAppUpdateManager.cancelDownload() },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("Cancel Download", color = Color(0xFFFF8A80))
                            }
                        }
                    }

                    is InAppDownloadState.ReadyToInstall -> {
                        Button(
                            onClick = { inAppUpdateManager.launchInstaller(state.installerFile, state.versionName) },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = Color(0xFF0A140A))
                        ) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Launch Installer Now", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }

                    is InAppDownloadState.Error -> {
                        Surface(
                            color = Color(0xFFFF5252).copy(alpha = 0.14f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = Color(0xFFFF8A80), modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(state.message, color = Color(0xFFFFCDD2), fontSize = 12.sp)
                            }
                        }
                        Button(
                            onClick = { inAppUpdateManager.startDownloadAndInstall(updateInfo) },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = Color(0xFF0A140A))
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Retry Download", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }

                    is InAppDownloadState.Idle -> {
                        Button(
                            onClick = { inAppUpdateManager.startDownloadAndInstall(updateInfo) },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = Color(0xFF0A140A))
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Download & Install Inside App", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Secondary Actions: Website & 24h Snooze
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            runCatching {
                                if (Desktop.isDesktopSupported()) {
                                    Desktop.getDesktop().browse(URI(updateInfo.releaseUrl.ifBlank { UpdateChecker.UPDATE_WEBSITE_URL }))
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Filled.OpenInNew, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open Website", color = AccentBlue, fontSize = 13.sp)
                    }

                    TextButton(onClick = onSnooze24h) {
                        Text("Remind in 24h", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
