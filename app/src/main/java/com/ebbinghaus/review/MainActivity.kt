package com.ebbinghaus.review

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ebbinghaus.review.ui.AddItemScreen
import com.ebbinghaus.review.ui.HistoryScreen
import com.ebbinghaus.review.ui.HomeScreen
import com.ebbinghaus.review.ui.MainViewModel
import com.ebbinghaus.review.ui.ReviewScreen
import com.ebbinghaus.review.ui.TrashScreen
import com.ebbinghaus.review.ui.theme.EbbinghausReviewTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        uri?.let {
            lifecycleScope.launch {
                val success = com.ebbinghaus.review.backup.BackupManager.exportData(applicationContext, it)
                val message = if (success) "导出成功" else "导出失败"
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            lifecycleScope.launch {
                val success = com.ebbinghaus.review.backup.BackupManager.importData(applicationContext, it)
                val message = if (success) "导入成功，即将重启" else "导入失败"
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                
                if (success) {
                    finish()
                    startActivity(intent)
                }
            }
        }
    }
    
    fun launchExport() {
        val fileName = "ebbinghaus_backup_${System.currentTimeMillis()}.zip"
        exportLauncher.launch(fileName)
    }

    fun launchImport() {
        importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
        }

        setContent {
            AppMaterialTheme {
                val navController = rememberNavController()
                val viewModel: MainViewModel = viewModel()
                AppNavigation(navController, viewModel, this) 
            }
        }
    }
}

@Composable
fun AppNavigation(navController: NavHostController, viewModel: MainViewModel, activity: MainActivity) {
    val dueItems by viewModel.dueItems.collectAsState()
    val todayReviewedItems by viewModel.todayReviewedItems.collectAsState()

    NavHost(navController = navController, startDestination = "home") {
        
        composable("home") {
            HomeScreen(
                navController = navController, 
                viewModel = viewModel, 
                dueItems = dueItems, 
                todayReviewedItems = todayReviewedItems, 
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

        // 【新增】回收站路由
        composable("trash") {
            TrashScreen(navController, viewModel)
        }
    }
}

@Composable
fun AppMaterialTheme(content: @Composable () -> Unit) {
    androidx.compose.material3.MaterialTheme(
        colorScheme = androidx.compose.material3.dynamicLightColorScheme(LocalContext.current),
        content = content
    )
}
