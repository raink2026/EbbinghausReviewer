package com.ebbinghaus.review.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AppBottomDockTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dockExposesSelectionAndReturnsRoute() {
        var route = "home"
        composeRule.setContent {
            MaterialTheme {
                AppBottomDock(
                    items = listOf(
                        BottomDockItem("home", "复习", Icons.Default.Home),
                        BottomDockItem("plan", "计划", Icons.Default.DateRange)
                    ),
                    selectedRoute = "home",
                    showLabels = true,
                    onSelect = { route = it }
                )
            }
        }

        composeRule.onNodeWithContentDescription("复习").assertIsSelected()
        composeRule.onNodeWithContentDescription("计划").performClick()
        assertEquals("plan", route)
    }
}
