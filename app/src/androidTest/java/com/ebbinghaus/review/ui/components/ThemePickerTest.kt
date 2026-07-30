package com.ebbinghaus.review.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.ebbinghaus.review.ui.theme.AppThemeConfig
import com.ebbinghaus.review.ui.theme.AppThemeFont
import com.ebbinghaus.review.ui.theme.AppThemePreset
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ThemePickerTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun presetPreviewAppliesCompletePreset() {
        var selected = AppThemeConfig()
        composeRule.setContent {
            MaterialTheme {
                ThemePicker(config = selected, onThemeChange = { selected = it })
            }
        }

        composeRule.onNodeWithContentDescription("选择 Twitter 主题").performClick()

        assertEquals(AppThemePreset.TWITTER, selected.preset)
    }

    @Test
    fun customEditorSavesFontSelection() {
        var selected = AppThemeConfig()
        composeRule.setContent {
            MaterialTheme {
                ThemePicker(config = selected, onThemeChange = { selected = it })
            }
        }

        composeRule.onNodeWithContentDescription("选择 自定义 主题").performClick()
        composeRule.onNodeWithTag("custom_theme_editor_list").performScrollToNode(hasText("阅读衬线"))
        composeRule.onNodeWithText("阅读衬线").performClick()
        composeRule.onNodeWithText("保存主题").performClick()

        assertEquals(AppThemePreset.CUSTOM, selected.preset)
        assertEquals(AppThemeFont.SERIF, selected.customFont)
    }
}
