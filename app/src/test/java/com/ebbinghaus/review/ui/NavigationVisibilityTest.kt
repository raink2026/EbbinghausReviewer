package com.ebbinghaus.review.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationVisibilityTest {
    @Test
    fun rootDestinationsShowBottomBar() {
        assertTrue(shouldShowBottomBar("home"))
        assertTrue(shouldShowBottomBar("plan"))
        assertTrue(shouldShowBottomBar("profile"))
    }

    @Test
    fun secondaryDestinationsHideBottomBar() {
        listOf(
            null,
            "plan_stats",
            "add",
            "review/{itemId}",
            "markdown/{noteId}",
            "edit_markdown/{noteId}",
            "history",
            "trash",
            "repository_settings",
            "conflicts",
            "conflict/{noteId}"
        ).forEach { route ->
            assertFalse("Expected $route to hide the bottom bar", shouldShowBottomBar(route))
        }
    }
}
