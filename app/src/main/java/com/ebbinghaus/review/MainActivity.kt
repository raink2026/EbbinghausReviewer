package com.ebbinghaus.review

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.ebbinghaus.review.ui.MainScreen
import com.ebbinghaus.review.ui.MainViewModel
import com.ebbinghaus.review.ui.theme.EbbinghausReviewTheme
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val mainViewModel: MainViewModel by viewModels()

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
            val currentUser by mainViewModel.currentUser.collectAsState()

            EbbinghausReviewTheme(fontScale = currentUser?.fontScale ?: 1.0f) {
                MainScreen(activity = this)
            }
        }
    }
}
