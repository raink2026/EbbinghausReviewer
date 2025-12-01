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
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.res.stringResource
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
    Scaffold(
        topBar = {
            TopAppBar(
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { navController.navigate("add") }) {
                Icon(Icons.Default.Add, contentDescription = "Add")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 1. 待复习列表
            Text(
                text = "${stringResource(R.string.to_review)} (${dueItems.size})",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(16.dp)
            )

            if (dueItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.no_review_tasks), color = Color.Gray)
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(dueItems, key = { it.id }) { item ->
                        ReviewItemCardWrapper(
                            item = item,
                            viewModel = viewModel,
                            onClick = { navController.navigate("review/${item.id}") }
                        )
                    }
                }
            }

            Divider()

            // 2. 今日已完成列表
            Text(
                text = "${stringResource(R.string.studied_today)} (${todayReviewedItems.size})",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(16.dp)
            )

            if (todayReviewedItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Text(stringResource(R.string.no_study_today), color = Color.Gray, modifier = Modifier.padding(top = 32.dp))
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(todayReviewedItems, key = { it.id }) { item ->
                        ReviewItemCardWrapper(
                            item = item,
                            viewModel = viewModel,
                            onClick = { navController.navigate("review/${item.id}") }
                        )
                    }
                }
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
