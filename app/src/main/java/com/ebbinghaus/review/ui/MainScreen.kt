package com.ebbinghaus.review.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ebbinghaus.review.MainActivity
import com.ebbinghaus.review.ui.add.AddItemScreen
import com.ebbinghaus.review.ui.add.EditMarkdownScreen
import com.ebbinghaus.review.ui.home.HomeScreen
import com.ebbinghaus.review.ui.conflict.ConflictListScreen
import com.ebbinghaus.review.ui.conflict.ConflictResolutionScreen
import com.ebbinghaus.review.ui.review.ReviewScreen
import com.ebbinghaus.review.ui.review.MarkdownReviewScreen
import com.ebbinghaus.review.ui.theme.AppIcons
import com.ebbinghaus.review.ui.components.AppBottomDock
import com.ebbinghaus.review.ui.components.BottomDockItem

sealed class Screen(val route: String, val label: String) {
    object Home : Screen("home", "复习")
    object Plan : Screen("plan", "计划")
    object Profile : Screen("profile", "我的")
}

val items = listOf(
    Screen.Home,
    Screen.Plan,
    Screen.Profile,
)

fun shouldShowBottomBar(route: String?): Boolean = items.any { it.route == route }

@Composable
fun MainScreen(activity: MainActivity) {
    val navController = rememberNavController()
    val viewModel: MainViewModel = viewModel()
    val dueItems by viewModel.dueItems.collectAsState()
    val todayReviewedItems by viewModel.todayReviewedItems.collectAsState()
    val dueSyncedNotes by viewModel.dueSyncedNotes.collectAsState()
    val todaySyncedNotes by viewModel.todaySyncedNotes.collectAsState()
    val conflictedSyncedNotes by viewModel.conflictedSyncedNotes.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val showBottomBar = currentDestination?.hierarchy?.any { shouldShowBottomBar(it.route) } == true

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar) {
                val dockItems = items.map { screen ->
                    val iconVector = if (currentUser != null) {
                            when (screen) {
                                Screen.Home -> AppIcons.getIcon(currentUser!!.homeIcon, Icons.Filled.Home)
                                Screen.Plan -> AppIcons.getIcon(currentUser!!.planIcon, Icons.Filled.DateRange)
                                Screen.Profile -> AppIcons.getIcon(currentUser!!.profileIcon, Icons.Filled.Person)
                            }
                        } else {
                            when (screen) {
                                Screen.Home -> Icons.Filled.Home
                                Screen.Plan -> Icons.Filled.DateRange
                                Screen.Profile -> Icons.Filled.Person
                            }
                        }
                    BottomDockItem(screen.route, screen.label, iconVector)
                }
                AppBottomDock(
                    items = dockItems,
                    selectedRoute = currentDestination?.route,
                    showLabels = currentUser?.showMenuLabels ?: true,
                    onSelect = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        NavHost(navController, startDestination = Screen.Home.route, Modifier.padding(innerPadding)) {
            composable(Screen.Home.route) {
                HomeScreen(
                    navController = navController,
                    viewModel = viewModel,
                    dueItems = dueItems,
                    todayReviewedItems = todayReviewedItems,
                    dueSyncedNotes = dueSyncedNotes,
                    todaySyncedNotes = todaySyncedNotes,
                    conflictedSyncedNotes = conflictedSyncedNotes
                )
            }
            composable(Screen.Plan.route) {
                PlanScreen(
                    onNavigateToStats = { navController.navigate("plan_stats") }
                )
            }
            composable("plan_stats") {
                val planViewModel: PlanViewModel = viewModel()
                PlanStatsScreen(
                    viewModel = planViewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Profile.route) {
                ProfileScreen(
                    navController = navController,
                    onExport = { activity.launchExport() },
                    onImport = { activity.launchImport() }
                )
            }
            composable("add") {
                AddItemScreen(navController, viewModel)
            }
            composable(
                route = "review/{itemId}",
                arguments = listOf(navArgument("itemId") { type = NavType.LongType })
            ) { backStackEntry ->
                val itemId = backStackEntry.arguments?.getLong("itemId") ?: 0L
                ReviewScreen(navController, viewModel, itemId)
            }
            composable(
                route = "markdown/{noteId}",
                arguments = listOf(navArgument("noteId") { type = NavType.StringType })
            ) { backStackEntry ->
                MarkdownReviewScreen(
                    navController,
                    viewModel,
                    backStackEntry.arguments?.getString("noteId").orEmpty()
                )
            }
            composable(
                route = "edit_markdown/{noteId}",
                arguments = listOf(navArgument("noteId") { type = NavType.StringType })
            ) { backStackEntry ->
                EditMarkdownScreen(
                    navController,
                    viewModel,
                    backStackEntry.arguments?.getString("noteId").orEmpty()
                )
            }
            composable("history") {
                HistoryScreen(navController, viewModel)
            }
            composable("trash") {
                TrashScreen(navController, viewModel)
            }
            composable("repository_settings") {
                RepositorySettingsScreen(onBack = { navController.popBackStack() })
            }
            composable("conflicts") {
                ConflictListScreen(navController, conflictedSyncedNotes)
            }
            composable(
                route = "conflict/{noteId}",
                arguments = listOf(navArgument("noteId") { type = NavType.StringType })
            ) { backStackEntry ->
                ConflictResolutionScreen(
                    navController,
                    viewModel,
                    backStackEntry.arguments?.getString("noteId").orEmpty()
                )
            }
        }
    }
}
