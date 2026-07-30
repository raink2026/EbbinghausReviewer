package com.ebbinghaus.review.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ebbinghaus.review.data.PlanItem
import com.ebbinghaus.review.data.PlanStatus
import com.ebbinghaus.review.ui.components.AppTopBar
import com.ebbinghaus.review.ui.components.EmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(viewModel: PlanViewModel = viewModel(), onNavigateToStats: () -> Unit) {
    val plans by viewModel.todayPlans.collectAsState()
    var showOverdueDialog by remember { mutableStateOf(false) }
    var overdueTasks by remember { mutableStateOf<List<PlanItem>>(emptyList()) }
    var showAddDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val tasks = viewModel.checkOverdueTasks()
        if (tasks.isNotEmpty()) {
            overdueTasks = tasks
            showOverdueDialog = true
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(title = "计划") {
                IconButton(onClick = onNavigateToStats) {
                    Icon(Icons.Default.DateRange, contentDescription = "计划统计")
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = MaterialTheme.shapes.medium
            ) { Icon(Icons.Default.Add, contentDescription = "新建计划") }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(bottom = 88.dp)
        ) {
            item { PlanProgressSummary(plans) }
            item {
                Text(
                    "今天",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            if (plans.isEmpty()) {
                item {
                    EmptyState(
                        title = "今天还没有计划",
                        supporting = "把最重要的一件事先安排下来",
                        actionLabel = "新建计划",
                        onAction = { showAddDialog = true }
                    )
                }
            } else {
                items(plans, key = PlanItem::id) { item ->
                    PlanItemRow(
                        item = item,
                        onToggleStatus = { viewModel.toggleStatus(item) },
                        onGiveUp = { viewModel.giveUpTask(item) }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddPlanDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = {
                viewModel.addPlan(it)
                showAddDialog = false
            }
        )
    }

    if (showOverdueDialog && overdueTasks.isNotEmpty()) {
        val currentTask = overdueTasks.first()
        AlertDialog(
            onDismissRequest = {},
            title = { Text("昨日遗留任务") },
            text = { Text("你之前计划了“${currentTask.content}”但没有完成。请选择处理方式。") },
            confirmButton = {
                Button(onClick = {
                    viewModel.carryOverTask(currentTask)
                    overdueTasks = overdueTasks.drop(1)
                    if (overdueTasks.isEmpty()) showOverdueDialog = false
                }) { Text("顺延到今天") }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.giveUpTask(currentTask)
                    overdueTasks = overdueTasks.drop(1)
                    if (overdueTasks.isEmpty()) showOverdueDialog = false
                }) { Text("彻底放弃", color = MaterialTheme.colorScheme.error) }
            }
        )
    }
}

@Composable
private fun PlanProgressSummary(plans: List<PlanItem>) {
    val done = plans.count { it.status == PlanStatus.DONE }
    val progress = if (plans.isEmpty()) 0f else done.toFloat() / plans.size
    Surface(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("今日完成度", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text("$done / ${plans.size}", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(5.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

@Composable
private fun PlanItemRow(item: PlanItem, onToggleStatus: () -> Unit, onGiveUp: () -> Unit) {
    val isDone = item.status == PlanStatus.DONE
    val isGivenUp = item.status == PlanStatus.GIVEN_UP
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = isDone, onCheckedChange = { onToggleStatus() }, enabled = !isGivenUp)
            Column(modifier = Modifier.weight(1f).padding(horizontal = 6.dp)) {
                Text(
                    item.content,
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (isDone || isGivenUp) TextDecoration.LineThrough else null,
                    color = if (isDone || isGivenUp) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface
                )
                if (isDone || isGivenUp) {
                    Text(
                        if (isDone) "已完成" else "已放弃",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isDone) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            }
            if (!isDone && !isGivenUp) {
                IconButton(onClick = onGiveUp) {
                    Icon(Icons.Default.Close, contentDescription = "放弃计划", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun AddPlanDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新计划") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("计划内容") },
                singleLine = true
            )
        },
        confirmButton = {
            Button(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }, enabled = text.isNotBlank()) {
                Text("添加")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
