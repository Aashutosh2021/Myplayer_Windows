package com.example.myplayer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val EditorialLightColorScheme = lightColorScheme(
    primary            = CharcoalPrimary,
    onPrimary          = PureWhite,
    primaryContainer   = SoftGraySurface,
    onPrimaryContainer = CharcoalPrimary,
    secondary          = SlateSecondary,
    onSecondary        = PureWhite,
    secondaryContainer = SoftGraySurface,
    onSecondaryContainer = CharcoalPrimary,
    tertiary           = SilverMuted,
    onTertiary         = PureWhite,
    background         = PureWhite,
    onBackground       = CharcoalPrimary,
    surface            = PureWhite,
    onSurface          = CharcoalPrimary,
    surfaceVariant     = SoftGraySurface,
    onSurfaceVariant   = SlateSecondary,
    outline            = SilverMuted,
    outlineVariant     = CardBorderOlive,
    error              = Color(0xFFD32F2F),
    onError            = PureWhite
)

@Composable
fun MyPlayerTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = EditorialLightColorScheme,
        typography  = Typography,
        content     = content
    )
}