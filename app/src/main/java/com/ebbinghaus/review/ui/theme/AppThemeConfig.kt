package com.ebbinghaus.review.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import com.ebbinghaus.review.data.User

enum class AppThemePreset(val storageId: String, val displayName: String) {
    WECHAT("wechat", "微信"),
    TWITTER("twitter", "Twitter"),
    MINIMAL("minimal", "简约"),
    CUSTOM("custom", "自定义");

    companion object {
        fun fromStorage(value: String?): AppThemePreset =
            entries.firstOrNull { it.storageId == value } ?: WECHAT
    }
}

enum class AppThemeFont(val storageId: String, val displayName: String) {
    SYSTEM("system", "系统默认"),
    SANS_SERIF("sans_serif", "现代黑体"),
    SERIF("serif", "阅读衬线"),
    MONOSPACE("monospace", "等宽字体");

    val family: FontFamily
        get() = when (this) {
            SYSTEM -> FontFamily.Default
            SANS_SERIF -> FontFamily.SansSerif
            SERIF -> FontFamily.Serif
            MONOSPACE -> FontFamily.Monospace
        }

    companion object {
        fun fromStorage(value: String?): AppThemeFont =
            entries.firstOrNull { it.storageId == value } ?: SYSTEM
    }
}

data class AppThemeConfig(
    val preset: AppThemePreset = AppThemePreset.WECHAT,
    val customDark: Boolean = false,
    val customFont: AppThemeFont = AppThemeFont.SYSTEM,
    val customBackground: Long? = null,
    val customSurface: Long? = null,
    val customPrimary: Long? = null,
    val customText: Long? = null
)

data class ResolvedAppTheme(
    val isDark: Boolean,
    val colorScheme: ColorScheme,
    val font: AppThemeFont
)

fun User.toAppThemeConfig(): AppThemeConfig = AppThemeConfig(
    preset = AppThemePreset.fromStorage(themePreset),
    customDark = customThemeDark,
    customFont = AppThemeFont.fromStorage(customThemeFont),
    customBackground = customThemeBackground ?: themeColor,
    customSurface = customThemeSurface,
    customPrimary = customThemePrimary,
    customText = customThemeText
)

fun resolveAppTheme(systemDark: Boolean, config: AppThemeConfig): ResolvedAppTheme = when (config.preset) {
    AppThemePreset.WECHAT -> ResolvedAppTheme(false, weChatColorScheme(), AppThemeFont.SANS_SERIF)
    AppThemePreset.TWITTER -> ResolvedAppTheme(true, twitterColorScheme(), AppThemeFont.SANS_SERIF)
    AppThemePreset.MINIMAL -> ResolvedAppTheme(
        isDark = systemDark,
        colorScheme = minimalColorScheme(systemDark),
        font = AppThemeFont.SYSTEM
    )
    AppThemePreset.CUSTOM -> ResolvedAppTheme(
        isDark = config.customDark,
        colorScheme = customColorScheme(config),
        font = config.customFont
    )
}

private fun weChatColorScheme(): ColorScheme = lightColorScheme(
    primary = WeChatGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9F9EF),
    onPrimaryContainer = Color(0xFF064F27),
    secondary = AppLinkBlue,
    tertiary = Color(0xFFB36B00),
    background = AppCanvasLight,
    onBackground = AppTextPrimaryLight,
    surface = AppSurfaceLight,
    onSurface = AppTextPrimaryLight,
    surfaceVariant = Color(0xFFF7F7F7),
    onSurfaceVariant = AppTextSecondaryLight,
    outline = Color(0xFFB7BABD),
    outlineVariant = AppDividerLight,
    error = AppDanger,
    onError = Color.White
)

private fun twitterColorScheme(): ColorScheme = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFFE7E9EA),
    onPrimaryContainer = Color(0xFF0F1419),
    secondary = Color(0xFF8B98A5),
    tertiary = Color(0xFFD6D9DB),
    background = Color.Black,
    onBackground = Color(0xFFE7E9EA),
    surface = Color(0xFF16181C),
    onSurface = Color(0xFFE7E9EA),
    surfaceVariant = Color(0xFF202327),
    onSurfaceVariant = Color(0xFF8B98A5),
    outline = Color(0xFF536471),
    outlineVariant = Color(0xFF2F3336),
    error = Color(0xFFF4212E),
    onError = Color.White
)

private fun minimalColorScheme(dark: Boolean): ColorScheme = if (dark) {
    darkColorScheme(
        primary = Color(0xFFE8E8E8),
        onPrimary = Color(0xFF171717),
        primaryContainer = Color(0xFF333333),
        onPrimaryContainer = Color(0xFFF5F5F5),
        secondary = Color(0xFFBDBDBD),
        background = Color(0xFF121212),
        onBackground = Color(0xFFF5F5F5),
        surface = Color(0xFF1B1B1B),
        onSurface = Color(0xFFF5F5F5),
        surfaceVariant = Color(0xFF242424),
        onSurfaceVariant = Color(0xFFB8B8B8),
        outline = Color(0xFF6A6A6A),
        outlineVariant = Color(0xFF303030),
        error = AppDanger,
        onError = Color.White
    )
} else {
    lightColorScheme(
        primary = Color(0xFF2F3337),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFECEDEF),
        onPrimaryContainer = Color(0xFF202225),
        secondary = Color(0xFF64686C),
        background = Color(0xFFFAFAFA),
        onBackground = Color(0xFF1C1D1F),
        surface = Color.White,
        onSurface = Color(0xFF1C1D1F),
        surfaceVariant = Color(0xFFF3F3F3),
        onSurfaceVariant = Color(0xFF666A6D),
        outline = Color(0xFFA8ABAE),
        outlineVariant = Color(0xFFE4E5E6),
        error = AppDanger,
        onError = Color.White
    )
}

private fun customColorScheme(config: AppThemeConfig): ColorScheme {
    val dark = config.customDark
    val background = config.customBackground?.let(::Color)
        ?: if (dark) Color(0xFF101214) else Color(0xFFF5F5F5)
    val surface = config.customSurface?.let(::Color)
        ?: if (dark) Color(0xFF1B1E21) else Color.White
    val primary = config.customPrimary?.let(::Color)
        ?: if (dark) Color(0xFFF2F2F2) else WeChatGreen
    val text = config.customText?.let(::Color)
        ?: if (dark) Color(0xFFF2F2F2) else Color(0xFF111111)
    val primaryContainer = lerp(background, primary, if (dark) 0.22f else 0.13f)
    val surfaceVariant = lerp(surface, text, if (dark) 0.10f else 0.045f)
    val mutedText = lerp(text, background, if (dark) 0.38f else 0.46f)
    val outline = lerp(text, background, 0.62f)
    val outlineVariant = lerp(surface, text, if (dark) 0.14f else 0.10f)

    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary,
        onPrimary = readableOn(primary),
        primaryContainer = primaryContainer,
        onPrimaryContainer = readableOn(primaryContainer),
        secondary = mutedText,
        tertiary = primary,
        background = background,
        onBackground = text,
        surface = surface,
        onSurface = text,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = mutedText,
        outline = outline,
        outlineVariant = outlineVariant,
        error = AppDanger,
        onError = Color.White
    )
}

private fun readableOn(color: Color): Color =
    if (color.luminance() > 0.45f) Color(0xFF111111) else Color.White
