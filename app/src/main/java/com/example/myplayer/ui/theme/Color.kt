package com.example.myplayer.ui.theme

import androidx.compose.ui.graphics.Color

// ── Inverting Day (Light) and Night (Dark) Color Tokens ─────────────────────
val CharcoalPrimary: Color
    get() = if (ThemeManager.isDark) Color(0xFFFFFFFF) else Color(0xFF171C26)

val SlateSecondary: Color
    get() = if (ThemeManager.isDark) Color(0xFFA4AAB7) else Color(0xFF6C7689)

val SilverMuted: Color
    get() = if (ThemeManager.isDark) Color(0xFF7E8798) else Color(0xFFA4AAB7)

val PureWhite: Color
    get() = if (ThemeManager.isDark) Color(0xFF14171F) else Color(0xFFFFFFFF)

val SoftGraySurface: Color
    get() = if (ThemeManager.isDark) Color(0xFF1F2532) else Color(0xFFF3F5F8)

val PureBlack: Color
    get() = if (ThemeManager.isDark) Color(0xFFFFFFFF) else Color(0xFF000000)

val CloudBlueBackground: Color
    get() = if (ThemeManager.isDark) Color(0xFF0F1218) else Color(0xFFFFFFFF)

val CardBorderOlive: Color
    get() = if (ThemeManager.isDark) Color(0xFF262D3D) else Color(0xFFEBEEF3)

val GlassBorderLight: Color
    get() = if (ThemeManager.isDark) Color(0xFFFFFFFF).copy(alpha = 0.12f) else Color(0xFF000000).copy(alpha = 0.08f)

val GlassBorderHover: Color
    get() = if (ThemeManager.isDark) Color(0xFFFFFFFF).copy(alpha = 0.28f) else Color(0xFF000000).copy(alpha = 0.22f)

val GlassAmbientOrb1: Color
    get() = if (ThemeManager.isDark) Color(0xFF283548).copy(alpha = 0.50f) else Color(0xFFE0E7FF).copy(alpha = 0.55f)

val GlassAmbientOrb2: Color
    get() = if (ThemeManager.isDark) Color(0xFF1E2836).copy(alpha = 0.45f) else Color(0xFFF1F5F9).copy(alpha = 0.60f)

val GlassAmbientOrb3: Color
    get() = if (ThemeManager.isDark) Color(0xFF17202C).copy(alpha = 0.40f) else Color(0xFFE2E8F0).copy(alpha = 0.50f)


// Mapped theme tokens
val Primary: Color              get() = CharcoalPrimary
val OnPrimary: Color            get() = PureWhite
val PrimaryContainer: Color     get() = SoftGraySurface
val OnPrimaryContainer: Color   get() = CharcoalPrimary

val Secondary: Color            get() = SlateSecondary
val OnSecondary: Color          get() = PureWhite
val SecondaryContainer: Color   get() = SoftGraySurface
val OnSecondaryContainer: Color get() = CharcoalPrimary

val Tertiary: Color             get() = SilverMuted
val OnTertiary: Color           get() = PureWhite
val TertiaryContainer: Color    get() = SoftGraySurface
val OnTertiaryContainer: Color  get() = CharcoalPrimary

val Background: Color           get() = PureWhite
val OnBackground: Color         get() = CharcoalPrimary
val Surface: Color              get() = PureWhite
val SurfaceVariant: Color       get() = SoftGraySurface
val Outline: Color              get() = SilverMuted
val OutlineVariant: Color       get() = CardBorderOlive
val Error: Color                = Color(0xFFD32F2F)
val OnError: Color              get() = PureWhite
val ErrorContainer: Color       = Color(0xFFFFDAD6)
val OnErrorContainer: Color     = Color(0xFF93000A)

val NeonLimePrimary: Color      get() = CharcoalPrimary
val OnNeonLime: Color           get() = PureWhite
val ClayPrimary: Color          get() = CharcoalPrimary
val OnClayPrimary: Color        get() = PureWhite
val ClaySecondary: Color        get() = SlateSecondary
val ClayTertiary: Color         get() = SilverMuted

// ── Backgrounds & Surfaces ────────────────────────────────────────────────
val DeepOliveBackground: Color     get() = PureWhite
val SurfaceLight: Color            get() = PureWhite
val SurfaceContainerLowest: Color  get() = PureWhite
val SurfaceContainerLow: Color     get() = if (ThemeManager.isDark) Color(0xFF161A22) else Color(0xFFF7F8FA)
val SurfaceContainer: Color        get() = SoftGraySurface
val SurfaceContainerHigh: Color    get() = if (ThemeManager.isDark) Color(0xFF232A38) else Color(0xFFECEFF4)
val SurfaceContainerHighest: Color get() = if (ThemeManager.isDark) Color(0xFF2C3446) else Color(0xFFE2E6ED)

// ── Text & Content ────────────────────────────────────────────────────────
val OnSurface: Color        get() = CharcoalPrimary
val OnSurfaceVariant: Color get() = SlateSecondary
val TextMuted: Color        get() = SilverMuted

// ── Borders & Shadows ─────────────────────────────────────────────────────
val NeonLimeGlow: Color     get() = CharcoalPrimary

val ClayShadowOuter: Color      get() = if (ThemeManager.isDark) Color(0xFF000000).copy(alpha = 0.35f) else Color(0xFF171C26).copy(alpha = 0.06f)
val ClayShadowInnerLight: Color get() = if (ThemeManager.isDark) Color(0xFFFFFFFF).copy(alpha = 0.08f) else Color(0xFFFFFFFF).copy(alpha = 0.80f)
val ClayShadowInnerDark: Color  get() = if (ThemeManager.isDark) Color(0xFF000000).copy(alpha = 0.40f) else Color(0xFF171C26).copy(alpha = 0.05f)

val GradientPrimarySecondary: List<Color> get() = listOf(CharcoalPrimary, SlateSecondary)
val GradientPrimaryTertiary: List<Color>  get() = listOf(CharcoalPrimary, SilverMuted)
val GradientBannerCard: List<Color>       get() = listOf(SoftGraySurface, PureWhite)