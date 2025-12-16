package com.ebbinghaus.review.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ebbinghaus.review.R
import com.ebbinghaus.review.data.ReviewItem
import com.ebbinghaus.review.data.ReviewLog
import com.ebbinghaus.review.ui.MainViewModel
import com.ebbinghaus.review.ui.components.HistoryLogsDialog
import com.ebbinghaus.review.ui.components.ReviewItemCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: MainViewModel,
    dueItems: List<ReviewItem>,
    todayReviewedItems: List<ReviewItem>
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.review_list)) },
                actions = {
                    // 回收站入口
                    IconButton(onClick = { navController.navigate("trash") }) {
                        Icon(Icons.Default.Delete, contentDescription = "Trash")
                    }
                    IconButton(onClick = { navController.navigate("history") }) {
                        Icon(Icons.Default.DateRange, contentDescription = "History")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { navController.navigate("add") }) {
                Icon(Icons.Default.Add, contentDescription = "Add")
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 1. 待复习列表
            item {
                Text(
                    text = "${stringResource(R.string.to_review)} (${dueItems.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (dueItems.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.List,
                            contentDescription = null,
                            modifier = Modifier.size(72.dp),
                            tint = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.no_review_tasks),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            } else {
                items(dueItems, key = { it.id }) { item ->
                    ReviewItemCardWrapper(
                        item = item,
                        viewModel = viewModel,
                        onClick = { navController.navigate("review/${item.id}") }
                    )
                }
            }

            item {
                Divider(modifier = Modifier.padding(vertical = 8.dp))
            }

            // 2. 今日已完成列表
            item {
                Text(
                    text = "${stringResource(R.string.studied_today)} (${todayReviewedItems.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (todayReviewedItems.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.no_study_today),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                items(todayReviewedItems, key = { it.id }) { item ->
                    ReviewItemCardWrapper(
                        item = item,
                        viewModel = viewModel,
                        onClick = { navController.navigate("review/${item.id}") }
                    )
                }
            }

            // Add some bottom padding for the FAB
            item {
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
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
        onDelete = {
            viewModel.moveToTrash(item)
        }
    )
}
