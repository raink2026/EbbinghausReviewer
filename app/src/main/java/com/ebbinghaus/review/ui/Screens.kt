package com.ebbinghaus.review.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.ebbinghaus.review.data.ReviewItem
import com.ebbinghaus.review.data.ReviewLog
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// === 1. 首页 (HomeScreen) ===
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
                title = { Text("复习列表") },
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
                text = "待复习 (${dueItems.size})",
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
                    Text("待复习任务已清空！", color = Color.Gray)
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(dueItems, key = { it.id }) { item ->
                        // 支持左滑删除 & 长按历史
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
                text = "今日已学 (${todayReviewedItems.size})",
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
                    Text("今天还没学习哦", color = Color.Gray, modifier = Modifier.padding(top = 32.dp))
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

// 封装带左滑删除和长按功能的卡片
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReviewItemCardWrapper(
    item: ReviewItem,
    viewModel: MainViewModel,
    onClick: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showHistoryDialog by remember { mutableStateOf(false) }
    var historyLogs by remember { mutableStateOf<List<ReviewLog>>(emptyList()) }

    // 删除状态管理
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            if (it == SwipeToDismissBoxValue.EndToStart) {
                showDeleteConfirm = true
                false // 【重要修改】返回 false，阻止组件进入 Dismissed 状态，让它自动回弹
            } else {
                false
            }
        }
    )

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除确认") },
            text = { Text("确定要删除这个知识点吗？\n它将被移入回收站，15天后自动彻底删除。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.moveToTrash(item)
                    showDeleteConfirm = false
                }) { Text("删除", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }

    // 历史记录弹窗
    if (showHistoryDialog) {
        HistoryLogsDialog(item, historyLogs) { showHistoryDialog = false }
    }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val color = if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) Color.Red else Color.Transparent
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 4.dp) 
                    .background(color)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White)
                }
            }
        },
        content = {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = {
                            coroutineScope.launch {
                                historyLogs = viewModel.getItemLogs(item.id)
                                showHistoryDialog = true
                            }
                        }
                    ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        
                        if (item.description.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = item.description,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        val statusText = if (item.isFinished) "已完成" else "阶段: ${item.stage}"
                        Text(text = statusText, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    Icon(Icons.Default.DateRange, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    )
}

@Composable
fun HistoryLogsDialog(item: ReviewItem, logs: List<ReviewLog>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("复习历史：${item.title}") },
        text = {
            if (logs.isEmpty()) {
                Text("暂无复习记录")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    items(logs) { log ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            // 阶段圆圈
                            val color = if (log.action == "REMEMBER") Color(0xFF4CAF50) else Color.Gray
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .background(color, androidx.compose.foundation.shape.CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(log.stageBefore.toString(), color = Color.White, style = MaterialTheme.typography.labelSmall)
                            }
                            
                            Spacer(modifier = Modifier.width(8.dp))
                            
                            Column {
                                val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                                Text(formatter.format(Date(log.reviewTime)), style = MaterialTheme.typography.bodyMedium)
                                val status = if (log.action == "REMEMBER") "记得 -> 阶段 ${log.stageAfter}" else "忘了 -> 重置"
                                Text(status, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

// === 2. 添加页 (AddItemScreen) ===
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddItemScreen(navController: NavController, viewModel: MainViewModel) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var imageUris by remember { mutableStateOf<List<Uri>>(emptyList()) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(),
        onResult = { uris -> 
             if (uris.isNotEmpty()) {
                 imageUris = imageUris + uris
             }
        }
    )

    Scaffold(
        topBar = { TopAppBar(title = { Text("添加新知识点") }) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("标题 (例如：单词)") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("描述 (可选，显示在卡片上)") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text("内容 / 答案") },
                modifier = Modifier.fillMaxWidth().height(150.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            Button(
                onClick = { 
                    photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (imageUris.isEmpty()) "添加图片 (支持多选)" else "继续添加图片")
            }

            if (imageUris.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(imageUris) { uri ->
                        AsyncImage(
                            model = uri,
                            contentDescription = null,
                            modifier = Modifier
                                .size(120.dp)
                                .clickable { },
                            contentScale = ContentScale.Crop,
                            onError = {
                                // 图片加载失败时忽略错误
                            }
                        )
                    }
                }
                Text("已选 ${imageUris.size} 张图片", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }

            Spacer(modifier = Modifier.weight(1f))
            
            Button(
                onClick = {
                    if (title.isNotBlank()) {
                        val uriStrings = imageUris.map { it.toString() }
                        viewModel.addItem(title, description, content, uriStrings)
                        // 清空状态，为下一次添加做准备
                        title = ""
                        description = ""
                        content = ""
                        imageUris = emptyList()
                        navController.popBackStack()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = title.isNotBlank()
            ) {
                Text("保存加入复习队列")
            }
        }
    }
}

// === 3. 复习页 (ReviewScreen) ===
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    navController: NavController,
    viewModel: MainViewModel,
    itemId: Long
) {
    var item by remember { mutableStateOf<ReviewItem?>(null) }
    var isAnswerVisible by remember { mutableStateOf(false) }
    var fullScreenImageUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(itemId) {
        item = viewModel.getItemById(itemId)
    }

    if (fullScreenImageUrl != null) {
        FullImageDialog(
            imageUrl = fullScreenImageUrl!!,
            onDismiss = { fullScreenImageUrl = null }
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("正在复习") }) }
    ) { innerPadding ->
        if (item == null) {
            Box(modifier = Modifier.padding(innerPadding)) { Text("加载中...") }
        } else {
            val currentItem = item!!
            // 修复 Bug 1: 判断是否允许复习 (今天没完成 且 未到期 -> 不可复习)
            // 逻辑：如果 nextReviewTime > Now，说明还没到时间（或者是已经复习完了被推到未来了）
            val isReviewable = !currentItem.isFinished && currentItem.nextReviewTime <= System.currentTimeMillis()
            
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .padding(16.dp)
                    .fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = currentItem.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                
                if (currentItem.description.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = currentItem.description, style = MaterialTheme.typography.bodyLarge, color = Color.Gray)
                }
                
                Spacer(modifier = Modifier.height(20.dp))

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(if (isAnswerVisible) Color.Transparent else Color.LightGray)
                        .clickable { isAnswerVisible = true },
                    contentAlignment = Alignment.Center
                ) {
                    if (isAnswerVisible) {
                        LazyColumn(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxSize().padding(16.dp)
                        ) {
                            item {
                                if (!currentItem.imagePaths.isNullOrEmpty()) {
                                    val paths = currentItem.imagePaths!!.split("|")
                                    LazyRow(
                                        modifier = Modifier.fillMaxWidth().height(250.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        items(paths) { path ->
                                            // 只显示有效的图片路径
                                            if (path.isNotBlank()) {
                                                AsyncImage(
                                                    model = Uri.parse(path),
                                                    contentDescription = null,
                                                    modifier = Modifier
                                                        .fillMaxHeight()
                                                        .clickable { fullScreenImageUrl = path },
                                                    contentScale = ContentScale.Fit,
                                                    onError = { 
                                                        // 图片加载失败时忽略错误，不显示该图片
                                                    }
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(16.dp))
                                }
                            }
                            item {
                                Text(
                                    text = currentItem.content,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    } else {
                        Text("点击显示答案", color = Color.DarkGray)
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                if (isAnswerVisible) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        // 放弃按钮
                        Button(
                            onClick = {
                                navController.popBackStack()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("放弃 (Skip)")
                        }
                        
                        if (isReviewable) {
                            // 完成复习按钮
                            Button(
                                onClick = {
                                    viewModel.markAsReviewed(currentItem, remembered = true)
                                    navController.popBackStack()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text("完成复习 (Done)")
                            }
                        } else {
                            // 不可复习状态
                             Button(
                                onClick = {
                                    navController.popBackStack()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
                            ) {
                                Text("今日已完成")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FullImageDialog(imageUrl: String, onDismiss: () -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 4f)
                        if (scale > 1f) {
                           offset += pan
                        } else {
                           offset = androidx.compose.ui.geometry.Offset.Zero
                        }
                    }
                }
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = Uri.parse(imageUrl),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    ),
                contentScale = ContentScale.Fit
            )
        }
    }
}