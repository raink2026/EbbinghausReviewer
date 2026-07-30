package com.ebbinghaus.review.ui.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MarkdownRendererTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersTextOnlyMarkdownPreview() {
        composeRule.setContent {
            MaterialTheme {
                MarkdownRenderer(
                    markdownBody = "# Title\n\n**Bold** text\n",
                    resolvedAssets = emptyList()
                )
            }
        }

        composeRule.onNodeWithText("Title").assertExists()
        composeRule.onNodeWithText("Bold text").assertExists()
    }

    @Test
    fun mixedMarkdownShowsRepairableMissingAssetStateWithoutCrashing() {
        var repairRequested = false
        val hash = "a".repeat(64)
        composeRule.setContent {
            MaterialTheme {
                MarkdownRenderer(
                    markdownBody = "Before\n\n![diagram](../assets/$hash.png)\n\nAfter\n",
                    resolvedAssets = emptyList(),
                    onRepairAsset = { repairRequested = true }
                )
            }
        }

        composeRule.onNodeWithText("Before").assertExists()
        composeRule.onNodeWithText("图片尚未缓存").assertExists()
        composeRule.onNodeWithContentDescription("重新同步图片").performClick()
        composeRule.runOnIdle { assertTrue(repairRequested) }
        composeRule.onNodeWithText("After").assertExists()
    }
}
