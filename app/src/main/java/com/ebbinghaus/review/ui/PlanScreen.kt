
    package com.ebbinghaus.review.ui

    import androidx.compose.foundation.background
    import androidx.compose.foundation.layout.*
    import androidx.compose.foundation.lazy.LazyColumn
    import androidx.compose.foundation.lazy.items
    import androidx.compose.material.icons.Icons
    import androidx.compose.material.icons.filled.Add
    import androidx.compose.material.icons.filled.Check
    import androidx.compose.material.icons.filled.Close
    import androidx.compose.material3.*
    import androidx.compose.runtime.*
    import androidx.compose.ui.Alignment
    import androidx.compose.ui.Modifier
    import androidx.compose.ui.graphics.Color
    import androidx.compose.ui.text.style.TextDecoration
    import androidx.compose.ui.unit.dp
    import androidx.lifecycle.viewmodel.compose.viewModel
    import com.ebbinghaus.review.data.PlanItem
    import com.ebbinghaus.review.data.PlanStatus

    @Composable
    fun PlanScreen(viewModel: PlanViewModel = viewModel()) {
        val plans by viewModel.todayPlans.collectAsState()

        // 状态：是否显示清理弹窗
        var showOverdueDialog by remember { mutableStateOf(false) }
        var overdueTasks by remember { mutableStateOf<List<PlanItem>>(emptyList()) }

        // 进入页面时检查旧账
        LaunchedEffect(Unit) {
            val tasks = viewModel.checkOverdueTasks()
            if (tasks.isNotEmpty()) {
                overdueTasks = tasks
                showOverdueDialog = true
            }
        }

        Scaffold(
            floatingActionButton = {
                // 简单的添加按钮逻辑 (实际开发可配合 Dialog 或底部输入栏)
                var showAddDialog by remember { mutableStateOf(false) }
                if (showAddDialog) {
                    AddPlanDialog(
                        onDismiss = { showAddDialog = false },
                        onConfirm = { content ->
                            viewModel.addPlan(content)
                            showAddDialog = false
                        }
                    )
                }
                FloatingActionButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add Plan")
                }
            }
        ) { innerPadding ->
            Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                // 顶部：进度条 (正向反馈)
                val total = plans.size
                val done = plans.count { it.status == PlanStatus.DONE }
                if (total > 0) {
                    LinearProgressIndicator(
                        progress = done.toFloat() / total,
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Text(
                        "今日完成度: $done / $total",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(8.dp).align(Alignment.CenterHorizontally),
                        color = Color.Gray
                    )
                } else {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        Text("制定今天的计划吧！", color = Color.Gray)
                    }
                }

                // 列表
                LazyColumn {
                    items(plans, key = { it.id }) { item ->
                        PlanItemCard(
                            item = item,
                            onToggleStatus = { viewModel.toggleStatus(item) },
                            onGiveUp = { viewModel.giveUpTask(item) }
                        )
                    }
                }
            }
        }

        // 旧账清理弹窗 (防拖延核心机制)
        if (showOverdueDialog && overdueTasks.isNotEmpty()) {
            val currentTask = overdueTasks.first() // 每次只处理一个，强制逐个决策
            AlertDialog(
                onDismissRequest = {}, // 禁止点击外部关闭，强制决策
                title = { Text("昨日遗留任务") },
                text = { Text("你之前计划了 \"${currentTask.content}\" 但没完成。\n\n要做什么处理？") },
                confirmButton = {
                    Button(onClick = {
                        viewModel.carryOverTask(currentTask)
                        // 更新剩余列表
                        overdueTasks = overdueTasks.drop(1)
                        if (overdueTasks.isEmpty()) showOverdueDialog = false
                    }) {
                        Text("顺延到今天")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        viewModel.giveUpTask(currentTask)
                        overdueTasks = overdueTasks.drop(1)
                        if (overdueTasks.isEmpty()) showOverdueDialog = false
                    }) {
                        Text("彻底放弃", color = Color.Red)
                    }
                }
            )
        }
    }

    @Composable
    fun PlanItemCard(item: PlanItem, onToggleStatus: () -> Unit, onGiveUp: () -> Unit) {
        val isDone = item.status == PlanStatus.DONE
        val isGivenUp = item.status == PlanStatus.GIVEN_UP

        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isDone) Color(0xFFE8F5E9) else if (isGivenUp) Color(0xFFFFEBEE) else MaterialTheme.colorScheme.surface
            )
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = isDone,
                    onCheckedChange = { onToggleStatus() },
                    enabled = !isGivenUp
                )
                Text(
                    text = item.content,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    textDecoration = if (isDone || isGivenUp) TextDecoration.LineThrough else null,
                    color = if (isDone || isGivenUp) Color.Gray else Color.Unspecified
                )

                if (!isDone && !isGivenUp) {
                    TextButton(onClick = onGiveUp) {
                        Text("放弃", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                    }
                } else if (isGivenUp) {
                    Text("已放弃", style = MaterialTheme.typography.labelSmall, color = Color.Red)
                }
            }
        }
    }

    // 简单的添加弹窗
    @Composable
    fun AddPlanDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("新计划") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("内容") }) },
            confirmButton = {
                Button(onClick = { if (text.isNotBlank()) onConfirm(text) }) { Text("添加") }
            }
        )
    }

