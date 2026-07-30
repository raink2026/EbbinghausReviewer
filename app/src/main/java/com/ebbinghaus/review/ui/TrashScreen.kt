package com.ebbinghaus.review.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ebbinghaus.review.data.ReviewItem
import com.ebbinghaus.review.data.sync.Note
import com.ebbinghaus.review.ui.components.AppTopBar
import com.ebbinghaus.review.ui.components.EmptyState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(navController: NavController, viewModel: MainViewModel) {
    val deletedItems by viewModel.deletedItems.collectAsState()
    val deletedSyncedNotes by viewModel.deletedSyncedNotes.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(title = "回收站", onBack = { navController.popBackStack() }) }
    ) { innerPadding ->
        if (deletedItems.isEmpty() && deletedSyncedNotes.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                EmptyState(title = "回收站是空的", supporting = "删除的内容会在这里保留 15 天")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                item {
                    Text(
                        "超过 15 天的项目将自动清除",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                items(deletedSyncedNotes, key = { "sync-${it.noteId}" }) { note ->
                    SyncedTrashItemCard(note) { viewModel.restoreMarkdownNote(note.noteId) }
                }
                items(deletedItems, key = ReviewItem::id) { item ->
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
private fun TrashItemCard(item: ReviewItem, onRestore: () -> Unit, onDelete: () -> Unit) {
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
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") } }
        )
    }
    TrashCard(
        title = item.title,
        supporting = item.deletedTime?.let {
            "删除于 ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it))}"
        }.orEmpty(),
        onRestore = onRestore,
        onDelete = { showDeleteConfirm = true }
    )
}

@Composable
private fun SyncedTrashItemCard(note: Note, onRestore: () -> Unit) {
    TrashCard(title = note.title, supporting = "已同步删除", onRestore = onRestore)
}

@Composable
private fun TrashCard(
    title: String,
    supporting: String,
    onRestore: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            IconButton(onClick = onRestore) {
                Icon(Icons.Default.Refresh, contentDescription = "恢复", tint = MaterialTheme.colorScheme.primary)
            }
            onDelete?.let {
                IconButton(onClick = it) {
                    Icon(Icons.Default.Delete, contentDescription = "彻底删除", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
