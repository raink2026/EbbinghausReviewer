package com.ebbinghaus.review.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ebbinghaus.review.R
import com.ebbinghaus.review.data.ReviewItem
import com.ebbinghaus.review.data.ReviewLog
import com.ebbinghaus.review.data.sync.Note
import com.ebbinghaus.review.ui.MainViewModel
import com.ebbinghaus.review.ui.components.AppTopBar
import com.ebbinghaus.review.ui.components.EmptyState
import com.ebbinghaus.review.ui.components.HistoryLogsDialog
import com.ebbinghaus.review.ui.components.MarkdownNoteCard
import com.ebbinghaus.review.ui.components.ReviewItemCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: MainViewModel,
    dueItems: List<ReviewItem>,
    todayReviewedItems: List<ReviewItem>,
    dueSyncedNotes: List<Note>,
    todaySyncedNotes: List<Note>,
    conflictedSyncedNotes: List<Note>
) {
    var isRefreshing by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    val refreshScope = rememberCoroutineScope()
    val requestRefresh: () -> Unit = {
        isRefreshing = true
        viewModel.requestProfileSync()
        refreshScope.launch {
            delay(750)
            isRefreshing = false
        }
    }
    val pullRefreshState = rememberPullRefreshState(isRefreshing, requestRefresh)
    val dueCount = dueItems.size + dueSyncedNotes.size
    val completedCount = todayReviewedItems.size + todaySyncedNotes.size

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(title = stringResource(R.string.review_list)) {
                if (conflictedSyncedNotes.isNotEmpty()) {
                    IconButton(onClick = { navController.navigate("conflicts") }) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "待处理冲突 ${conflictedSyncedNotes.size}",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
                IconButton(onClick = requestRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "同步")
                }
                Box {
                    IconButton(onClick = { showMore = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                        DropdownMenuItem(
                            text = { Text("复习日历") },
                            leadingIcon = { Icon(Icons.Default.DateRange, contentDescription = null) },
                            onClick = {
                                showMore = false
                                navController.navigate("history")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("回收站") },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                            onClick = {
                                showMore = false
                                navController.navigate("trash")
                            }
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { navController.navigate("add") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = MaterialTheme.shapes.medium
            ) { Icon(Icons.Default.Add, contentDescription = "新建笔记") }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(innerPadding).pullRefresh(pullRefreshState)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp)
            ) {
                item { ReviewSummary(dueCount, completedCount) }
                item { SectionHeader("现在开始", "$dueCount 项待复习") }
                if (dueCount == 0) {
                    item {
                        EmptyState(
                            title = stringResource(R.string.no_review_tasks),
                            supporting = "新增笔记后，复习安排会自动出现在这里",
                            actionLabel = "新建笔记",
                            onAction = { navController.navigate("add") }
                        )
                    }
                } else {
                    items(dueSyncedNotes, key = { "sync-${it.noteId}" }) { note ->
                        MarkdownNoteCard(note) { navController.navigate("markdown/${note.noteId}") }
                    }
                    items(dueItems, key = ReviewItem::id) { item ->
                        ReviewItemCardWrapper(item, viewModel) {
                            navController.navigate("review/${item.id}")
                        }
                    }
                }
                item { SectionHeader("今日已完成", "$completedCount 项") }
                if (completedCount == 0) {
                    item { EmptyState(title = stringResource(R.string.no_study_today)) }
                } else {
                    items(todaySyncedNotes, key = { "sync-${it.noteId}" }) { note ->
                        MarkdownNoteCard(note) { navController.navigate("markdown/${note.noteId}") }
                    }
                    items(todayReviewedItems, key = ReviewItem::id) { item ->
                        ReviewItemCardWrapper(item, viewModel) {
                            navController.navigate("review/${item.id}")
                        }
                    }
                }
            }
            PullRefreshIndicator(
                refreshing = isRefreshing,
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }
}

@Composable
private fun ReviewSummary(dueCount: Int, completedCount: Int) {
    val total = dueCount + completedCount
    val progress = if (total == 0) 0f else completedCount.toFloat() / total
    Surface(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("今天的复习", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text("$dueCount 项待完成", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(5.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant
            )
            Spacer(Modifier.height(6.dp))
            Text("今日已完成 $completedCount 项", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SectionHeader(title: String, meta: String) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ReviewItemCardWrapper(
    item: ReviewItem,
    viewModel: MainViewModel,
    onClick: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var showHistoryDialog by remember { mutableStateOf(false) }
    var historyLogs by remember { mutableStateOf<List<ReviewLog>>(emptyList()) }

    if (showHistoryDialog) {
        HistoryLogsDialog(item, historyLogs) { showHistoryDialog = false }
    }

    ReviewItemCard(
        item = item,
        onClick = onClick,
        onLongClick = {
            coroutineScope.launch {
                historyLogs = viewModel.getItemLogs(item.id)
                showHistoryDialog = true
            }
        },
        onDelete = { viewModel.moveToTrash(item) }
    )
}
