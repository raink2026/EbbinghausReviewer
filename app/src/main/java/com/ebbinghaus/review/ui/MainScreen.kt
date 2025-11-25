package com.ebbinghaus.review.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Home : Screen("home", "复习", Icons.Filled.Home)
    object Plan : Screen("plan", "计划", Icons.Filled.DateRange)
    object Profile : Screen("profile", "我的", Icons.Filled.Person)
}

val items = listOf(
    Screen.Home,
    Screen.Plan,
    Screen.Profile,
)

@Composable
fun MainScreen(activity: MainActivity) {
    val navController = rememberNavController()
    val viewModel: MainViewModel = viewModel()
    val dueItems by viewModel.dueItems.collectAsState()
    val todayReviewedItems by viewModel.todayReviewedItems.collectAsState()

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                items.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = null) },
                        label = { Text(screen.label) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
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
        }
    ) { innerPadding ->
        NavHost(navController, startDestination = Screen.Home.route, Modifier.padding(innerPadding)) {
            composable(Screen.Home.route) {
                HomeScreen(
                    navController = navController,
                    viewModel = viewModel,
                    dueItems = dueItems,
                    todayReviewedItems = todayReviewedItems
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
            composable("history") {
                HistoryScreen(navController, viewModel)
            }
            composable("trash") {
                TrashScreen(navController, viewModel)
            }
        }
    }
}
