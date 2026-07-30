package com.ebbinghaus.review.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ebbinghaus.review.data.sync.ProfileTimeService
import com.ebbinghaus.review.ui.components.MarkdownNoteCard
import com.ebbinghaus.review.ui.components.AppTopBar
import com.ebbinghaus.review.ui.components.EmptyState
import com.ebbinghaus.review.ui.components.ReviewStageRail
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    navController: NavController,
    viewModel: MainViewModel
) {
    val profile by viewModel.currentProfile.collectAsState()
    val today = profile?.let {
        ProfileTimeService.forProfile(it).localDateAt(System.currentTimeMillis())
    } ?: LocalDate.now()
    // 状态：当前展示的月份
    var currentMonth by remember { mutableStateOf(YearMonth.from(today)) }
    // 状态：用户选中的日期 (默认今天)
    var selectedDate by remember { mutableStateOf(today) }
    
    // 数据流
    val historyItems by viewModel.historyItems.collectAsState()
    val syncHistoryNotes by viewModel.syncHistoryNotes.collectAsState()
    val hasDataDates by viewModel.hasDataDates.collectAsState()

    LaunchedEffect(profile?.profileId, today) {
        selectedDate = today
        currentMonth = YearMonth.from(today)
    }

    // 初始化：加载热力点数据，并选中今天
    LaunchedEffect(Unit) {
        viewModel.loadHeatMapData()
        viewModel.selectDate(selectedDate.year, selectedDate.monthValue, selectedDate.dayOfMonth)
    }

    // 监听选中日期变化，重新查询数据
    LaunchedEffect(selectedDate) {
        viewModel.selectDate(selectedDate.year, selectedDate.monthValue, selectedDate.dayOfMonth)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(title = "复习计划日历", onBack = { navController.popBackStack() })
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            // === 1. 日历控件区域 ===
            CalendarWidget(
                currentMonth = currentMonth,
                selectedDate = selectedDate,
                today = today,
                hasDataDates = hasDataDates,
                onMonthChange = { currentMonth = it },
                onDateSelected = { selectedDate = it }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)

            // === 2. 选中日期的详情列表 ===
            Text(
                text = "${selectedDate.monthValue}月${selectedDate.dayOfMonth}日 计划复习 ${historyItems.size + syncHistoryNotes.size} 项",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onBackground
            )

            if (historyItems.isEmpty() && syncHistoryNotes.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(title = "这一天没有复习计划", supporting = "可以提前查看其他日期的安排")
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(syncHistoryNotes, key = { "sync-${it.noteId}" }) { note ->
                        MarkdownNoteCard(note) {
                            navController.navigate("markdown/${note.noteId}")
                        }
                    }
                    items(historyItems) { item ->
                        // 修改这里：点击卡片跳转到详情
                        HistoryItemCard(item) {
                            navController.navigate("review/${item.id}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CalendarWidget(
    currentMonth: YearMonth,
    selectedDate: LocalDate,
    today: LocalDate,
    hasDataDates: Set<String>,
    onMonthChange: (YearMonth) -> Unit,
    onDateSelected: (LocalDate) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium
    ) {
    Column(modifier = Modifier.padding(12.dp)) {
        // 月份切换头
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onMonthChange(currentMonth.minusMonths(1)) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上个月")
            }
            Text(
                text = "${currentMonth.year}年 ${currentMonth.monthValue}月",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = { onMonthChange(currentMonth.plusMonths(1)) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "下个月")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 星期头
        Row(modifier = Modifier.fillMaxWidth()) {
            val daysOfWeek = listOf("一", "二", "三", "四", "五", "六", "日")
            daysOfWeek.forEach { day ->
                Text(
                    text = day,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 日期网格
        val daysInMonth = currentMonth.lengthOfMonth()
        val firstDayOfWeek = currentMonth.atDay(1).dayOfWeek.value // 1 (Mon) - 7 (Sun)
        
        // 计算前面需要留白的格子
        val emptyCells = firstDayOfWeek - 1 

        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            modifier = Modifier.height(288.dp),
            userScrollEnabled = false
        ) {
            items(emptyCells) { Spacer(modifier = Modifier) }

            items(daysInMonth) { day ->
                val dateNum = day + 1
                val thisDate = currentMonth.atDay(dateNum)
                val isSelected = thisDate == selectedDate
                val isToday = thisDate == today
                
                val dateKey = "${thisDate.year}-${String.format("%02d", thisDate.monthValue)}-${String.format("%02d", thisDate.dayOfMonth)}"
                val hasData = hasDataDates.contains(dateKey)

                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .semantics(mergeDescendants = true) {
                            contentDescription = "${thisDate.monthValue}月${thisDate.dayOfMonth}日"
                            selected = isSelected
                        }
                        .clickable(
                            role = Role.Button,
                            onClickLabel = "选择日期"
                        ) { onDateSelected(thisDate) }
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary 
                            else if (isToday) MaterialTheme.colorScheme.primaryContainer
                            else Color.Transparent
                        )
                        .border(
                            width = 1.dp,
                            color = if (isToday && !isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "$dateNum",
                            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                        )
                        if (hasData) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Box(
                                modifier = Modifier
                                    .size(4.dp)
                                    .clip(CircleShape)
                                    .background(if (isSelected) Color.White else MaterialTheme.colorScheme.error)
                            )
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
fun HistoryItemCard(item: com.ebbinghaus.review.data.ReviewItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { onClick() }, // 添加点击事件
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = item.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (item.isFinished) "状态：已完成复习" else "状态：复习阶段 ${item.stage}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.isFinished) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                ReviewStageRail(stage = item.stage, completed = item.isFinished)
            }
        }
    }
}
