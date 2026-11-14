package com.example.myplayer.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.myplayer.data.youtube.YouTubeIntegrationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun YouTubeLoginDialog(
    youtubeManager: YouTubeIntegrationManager,
    onDismiss: () -> Unit,
    onLoginSuccess: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Login via Browser, 1 = Import Playlist Link, 2 = Paste Cookie
    var statusText by remember { mutableStateOf<String?>(null) }
    val tokenFetchStarted = remember { AtomicBoolean(false) }

    var loginError by remember { mutableStateOf<String?>(null) }
    var lastCookieAttempt by remember { mutableStateOf<String?>(null) }
    var browserLaunchFailed by remember { mutableStateOf(false) }

    var cookieInput by remember { mutableStateOf("") }
    var playlistUrlInput by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current

    val ytRed = Color(0xFFFF0033)
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface

    fun startCookieLogin(rawCookies: String, loadingMessage: String) {
        if (!tokenFetchStarted.compareAndSet(false, true)) return
        loginError = null
        statusText = loadingMessage
        scope.launch {
            try {
                youtubeManager.completeLoginWithCookies(rawCookies)
                    .onSuccess { accountName ->
                        Toast.makeText(
                            context,
                            "YouTube Music Connected ($accountName)! Syncing playlists & AI recommendations...",
                            Toast.LENGTH_LONG
                        ).show()
                        onLoginSuccess(accountName)
                        onDismiss()
                    }
                    .onFailure { err ->
                        statusText = null
                        loginError = err.message ?: "YouTube login failed"
                        Toast.makeText(
                            context,
                            "Login error: ${err.message ?: "Unknown"}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
            } catch (e: Exception) {
                statusText = null
                loginError = e.message ?: "Unknown login error"
            } finally {
                tokenFetchStarted.set(false)
            }
        }
    }

    fun startPlaylistUrlImport(urlOrId: String) {
        if (!tokenFetchStarted.compareAndSet(false, true)) return
        loginError = null
        statusText = "Importing YouTube playlist & training AI..."
        scope.launch {
            try {
                youtubeManager.importPlaylistByUrl(urlOrId)
                    .onSuccess { (title, count) ->
                        Toast.makeText(
                            context,
                            "Imported '$title' ($count songs) from YouTube!",
                            Toast.LENGTH_LONG
                        ).show()
                        onLoginSuccess("$title ($count songs)")
                        onDismiss()
                    }
                    .onFailure { err ->
                        statusText = null
                        loginError = err.message ?: "Playlist import failed"
                    }
            } catch (e: Exception) {
                statusText = null
                loginError = e.message ?: "Playlist import failed"
            } finally {
                tokenFetchStarted.set(false)
            }
        }
    }

    fun openLoginInBrowser() {
        scope.launch(Dispatchers.IO) {
            val opened = try {
                val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
                if (desktop != null && desktop.isSupported(Desktop.Action.BROWSE)) {
                    desktop.browse(URI(YouTubeIntegrationManager.LOGIN_URL))
                    true
                } else {
                    false
                }
            } catch (_: Exception) {
                false
            }
            if (!opened) {
                withContext(Dispatchers.Main) { browserLaunchFailed = true }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .width(540.dp)
                .padding(8.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(surfaceColor)
        ) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Connect YouTube Music",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = onSurfaceColor
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = onSurfaceColor)
                }
            }

            // Mode Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0; loginError = null },
                    label = { Text("Login via Browser", fontWeight = FontWeight.SemiBold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = ytRed,
                        selectedLabelColor = Color.White
                    )
                )
                FilterChip(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1; loginError = null },
                    label = { Text("Playlist Link", fontWeight = FontWeight.SemiBold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = ytRed,
                        selectedLabelColor = Color.White
                    )
                )
                FilterChip(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2; loginError = null },
                    label = { Text("Paste Cookie", fontWeight = FontWeight.SemiBold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = ytRed,
                        selectedLabelColor = Color.White
                    )
                )
            }

            if (statusText != null) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = ytRed
                )
            }

            when (selectedTab) {
                0 -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    "Sign in to YouTube Music in your browser:",
                                    fontWeight = FontWeight.Bold,
                                    color = onSurfaceColor,
                                    fontSize = 14.sp
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "1. Click 'Open YouTube Music in Browser' and sign in to your Google account.\n" +
                                    "2. Press F12 (Inspect) > Application > Cookies > https://music.youtube.com (or Network > browse > Cookie header).\n" +
                                    "3. Copy your Cookie header (or the value of 'SAPISID') and paste it below to sync your playlists & AI recommendations!",
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Button(
                            onClick = { openLoginInBrowser() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ytRed)
                        ) {
                            Text("Open YouTube Music in Browser", fontWeight = FontWeight.Bold, color = Color.White)
                        }

                        if (browserLaunchFailed) {
                            Text(
                                "Browser could not be opened automatically. Open this URL in your browser:",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            SelectionContainer {
                                Text(
                                    text = YouTubeIntegrationManager.LOGIN_URL,
                                    fontSize = 12.sp,
                                    color = ytRed,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        OutlinedTextField(
                            value = cookieInput,
                            onValueChange = { cookieInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("YouTube Music Cookie Header or SAPISID") },
                            placeholder = { Text("SAPISID=...; __Secure-3PAPISID=... (or raw SAPISID token)") },
                            singleLine = false,
                            maxLines = 3,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ytRed,
                                cursorColor = ytRed
                            )
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    clipboardManager.getText()?.text?.let { text ->
                                        cookieInput = text.trim()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Paste from Clipboard")
                            }

                            Button(
                                onClick = {
                                    val clean = cookieInput.trim()
                                    if (clean.isNotBlank()) {
                                        lastCookieAttempt = clean
                                        startCookieLogin(clean, "Validating YouTube session & training AI Brain...")
                                    }
                                },
                                enabled = cookieInput.isNotBlank() && statusText == null,
                                colors = ButtonDefaults.buttonColors(containerColor = ytRed),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Connect", fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }

                        if (statusText != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                CircularProgressIndicator(color = ytRed, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(statusText!!, fontSize = 12.sp, color = onSurfaceColor)
                            }
                        }

                        loginError?.let { errMsg ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Login failed: $errMsg",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(
                                        onClick = {
                                            lastCookieAttempt?.let { raw ->
                                                startCookieLogin(raw, "Syncing YouTube Music profile...")
                                            }
                                        }
                                    ) { Text("Try Again", fontSize = 12.sp, color = ytRed) }
                                    TextButton(onClick = { loginError = null }) {
                                        Text("Close", fontSize = 12.sp, color = onSurfaceColor)
                                    }
                                }
                            }
                        }
                    }
                }

                1 -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    "Import Any YouTube or YouTube Music Playlist:",
                                    fontWeight = FontWeight.Bold,
                                    color = onSurfaceColor,
                                    fontSize = 14.sp
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "1. Copy any playlist link from YouTube or YouTube Music (e.g. https://music.youtube.com/playlist?list=PL...).\n" +
                                    "2. Paste it below and click 'Import Playlist'.\n" +
                                    "3. All songs will be imported into your Library Playlists & used to train your AI recommendations!",
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        OutlinedTextField(
                            value = playlistUrlInput,
                            onValueChange = { playlistUrlInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("YouTube / YT Music Playlist URL or ID") },
                            placeholder = { Text("https://music.youtube.com/playlist?list=PL...") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ytRed,
                                cursorColor = ytRed
                            )
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    clipboardManager.getText()?.text?.let { text ->
                                        playlistUrlInput = text.trim()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Paste Link")
                            }

                            Button(
                                onClick = {
                                    if (playlistUrlInput.isNotBlank()) {
                                        startPlaylistUrlImport(playlistUrlInput.trim())
                                    }
                                },
                                enabled = playlistUrlInput.isNotBlank() && statusText == null,
                                colors = ButtonDefaults.buttonColors(containerColor = ytRed),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Import Playlist", fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }

                        if (statusText != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                CircularProgressIndicator(color = ytRed, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(statusText!!, fontSize = 12.sp, color = onSurfaceColor)
                            }
                        }

                        loginError?.let { errMsg ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        errMsg,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = { loginError = null }) {
                                        Text("Close", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                2 -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    "How to get your YouTube Music cookie:",
                                    fontWeight = FontWeight.Bold,
                                    color = onSurfaceColor,
                                    fontSize = 14.sp
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "1. Open your browser and log in to music.youtube.com.\n" +
                                    "2. Open Developer Tools (F12) > Application > Cookies > https://music.youtube.com.\n" +
                                    "3. Copy the value of 'SAPISID' (or the full Cookie header from Network tab) and paste it below.",
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        OutlinedTextField(
                            value = cookieInput,
                            onValueChange = { cookieInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("YouTube Cookie or SAPISID") },
                            placeholder = { Text("SAPISID=...; __Secure-3PAPISID=...") },
                            singleLine = false,
                            maxLines = 4,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ytRed,
                                cursorColor = ytRed
                            )
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    clipboardManager.getText()?.text?.let { text ->
                                        cookieInput = text.trim()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Paste from Clipboard")
                            }

                            Button(
                                onClick = {
                                    val clean = cookieInput.trim()
                                    if (clean.isNotBlank()) {
                                        lastCookieAttempt = clean
                                        startCookieLogin(clean, "Validating cookie & training AI Brain...")
                                    }
                                },
                                enabled = cookieInput.isNotBlank() && statusText == null,
                                colors = ButtonDefaults.buttonColors(containerColor = ytRed),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Connect", fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }

                        if (statusText != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                CircularProgressIndicator(color = ytRed, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(statusText!!, fontSize = 12.sp, color = onSurfaceColor)
                            }
                        }

                        loginError?.let { errMsg ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Validation failed: $errMsg",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = { loginError = null }) {
                                        Text("Close", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
