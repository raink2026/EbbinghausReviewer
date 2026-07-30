package com.ebbinghaus.review.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class WechatComponentsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun reviewStageRailExposesProgressSemantics() {
        composeRule.setContent {
            MaterialTheme {
                ReviewStageRail(stage = 3)
            }
        }

        composeRule.onNodeWithContentDescription("复习阶段 3/8").assertExists()
    }

    @Test
    fun emptyStateActionInvokesCallback() {
        var clicks = 0
        composeRule.setContent {
            MaterialTheme {
                EmptyState(
                    title = "暂无任务",
                    actionLabel = "新建",
                    onAction = { clicks++ }
                )
            }
        }

        composeRule.onNodeWithText("暂无任务").assertExists()
        composeRule.onNodeWithText("新建").performClick()
        assertEquals(1, clicks)
    }
}
