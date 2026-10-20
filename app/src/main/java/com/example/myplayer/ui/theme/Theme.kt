package com.example.myplayer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import java.time.LocalTime

object ThemeManager {
    private val isNightTime: Boolean
        get() {
            val hour = LocalTime.now().hour
            return hour < 6 || hour >= 18
        }

    var isDarkMode by mutableStateOf(isNightTime)
    val isDark: Boolean get() = isDarkMode
    var onThemeChanged: ((Boolean) -> Unit)? = null

    fun toggle() {
        setDark(!isDarkMode)
    }

    fun setDark(enabled: Boolean) {
        if (isDarkMode != enabled) {
            isDarkMode = enabled
            onThemeChanged?.invoke(enabled)
        }
    }
}

val ThemeState = ThemeManager

private val EditorialLightColorScheme = lightColorScheme(
    primary              = Color(0xFF171C26),
    onPrimary            = Color(0xFFFFFFFF),
    primaryContainer     = Color(0xFFF3F5F8),
    onPrimaryContainer   = Color(0xFF171C26),
    secondary            = Color(0xFF6C7689),
    onSecondary          = Color(0xFFFFFFFF),
    secondaryContainer   = Color(0xFFF3F5F8),
    onSecondaryContainer = Color(0xFF171C26),
    tertiary             = Color(0xFFA4AAB7),
    onTertiary           = Color(0xFFFFFFFF),
    background           = Color(0xFFFFFFFF),
    onBackground         = Color(0xFF171C26),
    surface              = Color(0xFFFFFFFF),
    onSurface            = Color(0xFF171C26),
    surfaceVariant       = Color(0xFFF3F5F8),
    onSurfaceVariant     = Color(0xFF6C7689),
    outline              = Color(0xFFA4AAB7),
    outlineVariant       = Color(0xFFEBEEF3),
    error                = Color(0xFFD32F2F),
    onError              = Color(0xFFFFFFFF)
)

private val EditorialDarkColorScheme = darkColorScheme(
    primary              = Color(0xFFFFFFFF),
    onPrimary            = Color(0xFF171C26),
    primaryContainer     = Color(0xFF1F2532),
    onPrimaryContainer   = Color(0xFFFFFFFF),
    secondary            = Color(0xFFA4AAB7),
    onSecondary          = Color(0xFF171C26),
    secondaryContainer   = Color(0xFF1F2532),
    onSecondaryContainer = Color(0xFFFFFFFF),
    tertiary             = Color(0xFF7E8798),
    onTertiary           = Color(0xFFFFFFFF),
    background           = Color(0xFF0F1218),
    onBackground         = Color(0xFFFFFFFF),
    surface              = Color(0xFF14171F),
    onSurface            = Color(0xFFFFFFFF),
    surfaceVariant       = Color(0xFF1F2532),
    onSurfaceVariant     = Color(0xFFA4AAB7),
    outline              = Color(0xFF7E8798),
    outlineVariant       = Color(0xFF262D3D),
    error                = Color(0xFFFFB4AB),
    onError              = Color(0xFF690005)
)

@Composable
fun MyPlayerTheme(
    darkTheme: Boolean = ThemeManager.isDark,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) EditorialDarkColorScheme else EditorialLightColorScheme,
        typography  = Typography,
        content     = content
    )
}