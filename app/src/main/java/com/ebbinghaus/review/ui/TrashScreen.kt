package com.ebbinghaus.review.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ebbinghaus.review.data.ReviewItem
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    navController: NavController,
    viewModel: MainViewModel
) {
    val deletedItems by viewModel.deletedItems.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("回收站") }) }
    ) { innerPadding ->
        if (deletedItems.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text("回收站是空的", color = Color.Gray)
            }
        } else {
            LazyColumn(contentPadding = innerPadding) {
                // 提示信息
                item {
                    Text(
                        "超过 15 天的项目将被自动清除",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                items(deletedItems) { item ->
                    TrashItemCard(
                        item = item,
                        onRestore = { viewModel.restoreFromTrash(item) },
                        onDelete = { viewModel.deletePermanently(item) }
                    )
                }
            }
        }
    }
}

@Composable
fun TrashItemCard(item: ReviewItem, onRestore: () -> Unit, onDelete: () -> Unit) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("彻底删除") },
            text = { Text("确定要彻底删除这个知识点吗？此操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteConfirm = false
                }) { Text("删除", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = item.title, fontWeight = FontWeight.Bold)
                val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                val deleteTimeStr = item.deletedTime?.let { formatter.format(Date(it)) } ?: ""
                Text("删除于: $deleteTimeStr", style = MaterialTheme.typography.bodySmall, color = Color.Red)
            }

            // 恢复按钮
            IconButton(onClick = onRestore) {
                Icon(Icons.Default.Refresh, contentDescription = "Restore", tint = MaterialTheme.colorScheme.primary)
            }
            // 彻底删除按钮
            IconButton(onClick = { showDeleteConfirm = true }) {
                Icon(Icons.Default.Delete, contentDescription = "Delete Forever", tint = Color.Gray)
            }
        }
    }
}