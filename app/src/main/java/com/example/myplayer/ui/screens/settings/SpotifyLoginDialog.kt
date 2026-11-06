package com.example.myplayer.ui.screens.settings

// shortcut: no embedded browser on desktop; WebView auto-capture unavailable, user copies
// sp_dc cookie; upgrade by adding JCEF site embedding
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
import com.example.myplayer.data.spotify.SpotifyIntegrationManager
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun SpotifyLoginDialog(
    spotifyManager: SpotifyIntegrationManager,
    onDismiss: () -> Unit,
    onLoginSuccess: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Login via Browser, 1 = Cookie Paste
    var statusText by remember { mutableStateOf<String?>(null) }
    val tokenFetchStarted = remember { AtomicBoolean(false) }

    // Error state kept SEPARATE from statusText: previously any failure wrote into
    // statusText, which kept the full-screen loading overlay up forever with a
    // still-spinning loader and no dismiss button ("stuck on loading").
    var loginError by remember { mutableStateOf<String?>(null) }

    // Cookie pair of the last capture attempt, so the error card can offer "Try Again"
    var lastLoginAttempt by remember { mutableStateOf<Pair<String, String>?>(null) }

    // Desktop-only state: whether the default browser could be opened automatically
    var browserLaunchFailed by remember { mutableStateOf(false) }

    var cookieInput by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current

    val spotifyGreen = Color(0xFF1DB954)
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface

    /**
     * Single entry point for the completeLoginWithCookies chain (cookie paste and
     * error-card retry). The tokenFetchStarted atomic guards against double
     * launches. completeLoginWithCookies now returns in <1s (heavy sync moved to
     * a background scope), so we confirm with a Toast and let the dialog close.
     */
    fun startLogin(spDc: String, spKey: String, loadingMessage: String) {
        if (!tokenFetchStarted.compareAndSet(false, true)) return
        loginError = null
        statusText = loadingMessage
        scope.launch {
            spotifyManager.completeLoginWithCookies(spDc, spKey)
                .onSuccess { username ->
                    Toast.makeText(
                        context,
                        "Spotify Connected ($username)! Syncing library in background...",
                        Toast.LENGTH_LONG
                    ).show()
                    onLoginSuccess(username)
                    onDismiss()
                }
                .onFailure { err ->
                    statusText = null
                    loginError = err.message ?: "Unknown login error"
                    Toast.makeText(
                        context,
                        "Login error: ${err.message ?: "Unknown"}",
                        Toast.LENGTH_SHORT
                    ).show()
                    tokenFetchStarted.set(false)
                }
        }
    }

    /**
     * Opens the Spotify login page in the user's default browser via AWT Desktop.
     * shortcut: replaced Android's in-dialog WebView tab with a system-browser tab;
     * on any failure (headless, unconfigured, blocked) we surface the URL to copy.
     */
    fun openLoginInBrowser() {
        scope.launch(Dispatchers.IO) {
            val opened = try {
                val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
                if (desktop != null && desktop.isSupported(Desktop.Action.BROWSE)) {
                    desktop.browse(URI(SpotifyIntegrationManager.LOGIN_URL))
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
                .width(520.dp)
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
                    text = "Connect Spotify",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = onSurfaceColor
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = onSurfaceColor)
                }
            }

            // Mode Tabs: Browser Sign-In vs Cookie Paste
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    label = { Text("Login via Browser", fontWeight = FontWeight.SemiBold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = spotifyGreen,
                        selectedLabelColor = Color.White
                    )
                )
                FilterChip(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    label = { Text("Paste Cookie", fontWeight = FontWeight.SemiBold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = spotifyGreen,
                        selectedLabelColor = Color.White
                    )
                )
            }

            if (statusText != null) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = spotifyGreen
                )
            }

            if (selectedTab == 0) {
                // Tab 0: Login via system default browser + inline sp_dc capture
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
                                "Sign in to Spotify in your browser:",
                                fontWeight = FontWeight.Bold,
                                color = onSurfaceColor,
                                fontSize = 14.sp
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "1. Click 'Open Browser & Sign In' — the Spotify login page opens in your default browser.\n" +
                                "2. Log in (via Google, Apple, Facebook, or email).\n" +
                                "3. After login, open Inspect / Developer Tools > Application > Cookies > open.spotify.com.\n" +
                                "4. Copy the value of 'sp_dc' and paste it below.",
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
                        colors = ButtonDefaults.buttonColors(containerColor = spotifyGreen)
                    ) {
                        Text("Open Browser & Sign In", fontWeight = FontWeight.Bold, color = Color.White)
                    }

                    if (browserLaunchFailed) {
                        Text(
                            "Browser could not be opened automatically. Paste this URL into any browser:",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SelectionContainer {
                            Text(
                                text = SpotifyIntegrationManager.LOGIN_URL,
                                fontSize = 12.sp,
                                color = spotifyGreen,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    OutlinedTextField(
                        value = cookieInput,
                        onValueChange = { cookieInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Spotify sp_dc Cookie") },
                        placeholder = { Text("AQB... (long token)") },
                        singleLine = false,
                        maxLines = 3,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = spotifyGreen,
                            cursorColor = spotifyGreen
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
                                val cleanCookie = cookieInput.trim()
                                if (cleanCookie.isNotBlank()) {
                                    lastLoginAttempt = cleanCookie to ""
                                    startLogin(cleanCookie, "", "Validating cookie & training AI Brain...")
                                }
                            },
                            enabled = cookieInput.isNotBlank() && statusText == null,
                            colors = ButtonDefaults.buttonColors(containerColor = spotifyGreen),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Connect", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }

                    if (statusText != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            CircularProgressIndicator(color = spotifyGreen, modifier = Modifier.size(20.dp))
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
                                        lastLoginAttempt?.let { (dc, key) ->
                                            startLogin(dc, key, "Syncing your Spotify taste profile...")
                                        }
                                    }
                                ) { Text("Try Again", fontSize = 12.sp, color = spotifyGreen) }
                                TextButton(
                                    onClick = { loginError = null }
                                ) { Text("Close", fontSize = 12.sp, color = onSurfaceColor) }
                            }
                        }
                    }
                }
            } else {
                // Tab 1: Paste sp_dc Cookie Mode
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
                                "How to get your sp_dc cookie:",
                                fontWeight = FontWeight.Bold,
                                color = onSurfaceColor,
                                fontSize = 14.sp
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "1. Open your browser and log in to open.spotify.com (via Google, Apple, or Facebook).\n" +
                                "2. Inspect / Developer Tools > Application/Storage > Cookies > open.spotify.com.\n" +
                                "3. Copy the value of 'sp_dc' and paste it below.",
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
                        label = { Text("Spotify sp_dc Cookie") },
                        placeholder = { Text("AQB... (long token)") },
                        singleLine = false,
                        maxLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = spotifyGreen,
                            cursorColor = spotifyGreen
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
                                val cleanCookie = cookieInput.trim()
                                if (cleanCookie.isNotBlank()) {
                                    lastLoginAttempt = cleanCookie to ""
                                    startLogin(cleanCookie, "", "Validating cookie & training AI Brain...")
                                }
                            },
                            enabled = cookieInput.isNotBlank() && statusText == null,
                            colors = ButtonDefaults.buttonColors(containerColor = spotifyGreen),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Connect", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }

                    if (statusText != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            CircularProgressIndicator(color = spotifyGreen, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(statusText!!, fontSize = 12.sp, color = onSurfaceColor)
                        }
                    }

                    // Cookie-paste failure card with Retry (button re-enables as well
                    // because statusText resets to null on failure now).
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
                                    "Cookie validation failed: $errMsg",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = {
                                        startLogin(cookieInput.trim(), "", "Validating cookie & training AI Brain...")
                                    }
                                ) { Text("Retry", fontSize = 12.sp) }
                                TextButton(
                                    onClick = { loginError = null }
                                ) { Text("Close", fontSize = 12.sp) }
                            }
                        }
                    }
                }
            }
        }
    }
}
