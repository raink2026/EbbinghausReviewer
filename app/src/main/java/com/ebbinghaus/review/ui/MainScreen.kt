package com.ebbinghaus.review.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
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
import com.ebbinghaus.review.ui.home.HomeScreen
import com.ebbinghaus.review.ui.review.ReviewScreen
import com.ebbinghaus.review.ui.theme.AppIcons

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

@Composable
fun MainScreen(activity: MainActivity) {
    val navController = rememberNavController()
    val viewModel: MainViewModel = viewModel()
    val dueItems by viewModel.dueItems.collectAsState()
    val todayReviewedItems by viewModel.todayReviewedItems.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()

    Scaffold(
        bottomBar = {
            val showLabels = currentUser?.showMenuLabels ?: true
            val navBarHeight = if (showLabels) 80.dp else 64.dp

            NavigationBar(
                modifier = Modifier.height(navBarHeight)
            ) {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                items.forEach { screen ->
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

                    val showLabel = currentUser?.showMenuLabels ?: true

                    NavigationBarItem(
                        icon = { Icon(iconVector, contentDescription = null) },
                        label = if (showLabel) { { Text(screen.label) } } else null,
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
