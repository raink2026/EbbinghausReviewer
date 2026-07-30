package com.ebbinghaus.review.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppThemeConfigTest {
    @Test
    fun weChatThemeStaysLightAndUsesGreenPalette() {
        val resolved = resolveAppTheme(
            systemDark = true,
            config = AppThemeConfig(preset = AppThemePreset.WECHAT)
        )

        assertFalse(resolved.isDark)
        assertEquals(Color(0xFFF5F5F5), resolved.colorScheme.background)
        assertEquals(Color.White, resolved.colorScheme.surface)
        assertEquals(Color(0xFF07C160), resolved.colorScheme.primary)
    }

    @Test
    fun twitterThemeStaysDarkAndUsesBlackWhitePalette() {
        val resolved = resolveAppTheme(
            systemDark = false,
            config = AppThemeConfig(preset = AppThemePreset.TWITTER)
        )

        assertTrue(resolved.isDark)
        assertEquals(Color.Black, resolved.colorScheme.background)
        assertEquals(Color(0xFF16181C), resolved.colorScheme.surface)
        assertEquals(Color.White, resolved.colorScheme.primary)
    }

    @Test
    fun minimalThemeFollowsSystemMode() {
        assertFalse(resolveAppTheme(false, AppThemeConfig(AppThemePreset.MINIMAL)).isDark)
        assertTrue(resolveAppTheme(true, AppThemeConfig(AppThemePreset.MINIMAL)).isDark)
    }

    @Test
    fun customThemeUsesSelectedRolesAndFont() {
        val config = AppThemeConfig(
            preset = AppThemePreset.CUSTOM,
            customDark = true,
            customFont = AppThemeFont.SERIF,
            customBackground = 0xFF101820,
            customSurface = 0xFF1C2732,
            customPrimary = 0xFFFFB000,
            customText = 0xFFF8F9FA
        )

        val resolved = resolveAppTheme(systemDark = false, config = config)

        assertTrue(resolved.isDark)
        assertEquals(AppThemeFont.SERIF, resolved.font)
        assertEquals(Color(0xFF101820), resolved.colorScheme.background)
        assertEquals(Color(0xFF1C2732), resolved.colorScheme.surface)
        assertEquals(Color(0xFFFFB000), resolved.colorScheme.primary)
        assertEquals(Color(0xFFF8F9FA), resolved.colorScheme.onBackground)
    }

    @Test
    fun unknownStoredValuesFallBackToSafeDefaults() {
        assertEquals(AppThemePreset.WECHAT, AppThemePreset.fromStorage("missing"))
        assertEquals(AppThemeFont.SYSTEM, AppThemeFont.fromStorage("missing"))
    }
}
