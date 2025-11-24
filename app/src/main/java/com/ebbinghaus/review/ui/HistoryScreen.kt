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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
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
    // 状态：当前展示的月份
    var currentMonth by remember { mutableStateOf(YearMonth.now()) }
    // 状态：用户选中的日期 (默认今天)
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    
    // 数据流
    val historyItems by viewModel.historyItems.collectAsState()
    val hasDataDates by viewModel.hasDataDates.collectAsState()

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
        topBar = { TopAppBar(title = { Text("复习计划日历") }) } // 修改标题
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            // === 1. 日历控件区域 ===
            CalendarWidget(
                currentMonth = currentMonth,
                selectedDate = selectedDate,
                hasDataDates = hasDataDates,
                onMonthChange = { currentMonth = it },
                onDateSelected = { selectedDate = it }
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // === 2. 选中日期的详情列表 ===
            Text(
                text = "${selectedDate.monthValue}月${selectedDate.dayOfMonth}日 计划复习 ${historyItems.size} 个知识点",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.primary
            )

            if (historyItems.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("这一天没有复习计划，休息一下吧！", color = Color.Gray)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
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
    hasDataDates: Set<String>,
    onMonthChange: (YearMonth) -> Unit,
    onDateSelected: (LocalDate) -> Unit
) {
    Column(modifier = Modifier.padding(16.dp)) {
        // 月份切换头
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onMonthChange(currentMonth.minusMonths(1)) }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Prev")
            }
            Text(
                text = "${currentMonth.year}年 ${currentMonth.monthValue}月",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = { onMonthChange(currentMonth.plusMonths(1)) }) {
                Icon(Icons.Default.ArrowForward, contentDescription = "Next")
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
                    color = Color.Gray,
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
            modifier = Modifier.height(240.dp),
            userScrollEnabled = false
        ) {
            items(emptyCells) { Spacer(modifier = Modifier) }

            items(daysInMonth) { day ->
                val dateNum = day + 1
                val thisDate = currentMonth.atDay(dateNum)
                val isSelected = thisDate == selectedDate
                val isToday = thisDate == LocalDate.now()
                
                val dateKey = "${thisDate.year}-${String.format("%02d", thisDate.monthValue)}-${String.format("%02d", thisDate.dayOfMonth)}"
                val hasData = hasDataDates.contains(dateKey)

                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
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
                        )
                        .clickable { onDateSelected(thisDate) },
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

@Composable
fun HistoryItemCard(item: com.ebbinghaus.review.data.ReviewItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { onClick() }, // 添加点击事件
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = item.title, fontWeight = FontWeight.Bold)
                Text(
                    text = if (item.isFinished) "状态：已完成复习" else "状态：复习阶段 ${item.stage}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.isFinished) Color(0xFF4CAF50) else Color.Gray
                )
            }
        }
    }
}