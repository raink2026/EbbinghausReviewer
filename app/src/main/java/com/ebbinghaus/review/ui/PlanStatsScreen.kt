package com.ebbinghaus.review.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
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
import com.ebbinghaus.review.ui.theme.HeatmapGreenDark
import com.ebbinghaus.review.ui.theme.HeatmapGreenLight
import com.ebbinghaus.review.ui.theme.HeatmapGray
import java.time.LocalDate
import java.time.YearMonth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanStatsScreen(
    viewModel: PlanViewModel,
    onBack: () -> Unit
) {
    val heatMap by viewModel.monthlyHeatMap.collectAsState()
    val currentMonth by viewModel.statsMonth.collectAsState()

    // 初始加载
    LaunchedEffect(Unit) {
        viewModel.loadMonthlyStats(YearMonth.now())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("坚持记录") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).padding(16.dp)) {

            // 1. 月份切换器
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { viewModel.changeStatsMonth(-1) }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Prev")
                }
                Text(
                    text = "${currentMonth.year}年 ${currentMonth.monthValue}月",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = { viewModel.changeStatsMonth(1) }) {
                    Icon(Icons.Default.ArrowForward, contentDescription = "Next")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 2. 统计概览卡片 (正向反馈核心)
            val totalDone = heatMap.values.sum()
            val perfectDays = heatMap.values.count { it >= 3 } // 假设每天完成3个算完美

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    StatItem(count = totalDone.toString(), label = "本月完成")
                    StatItem(count = perfectDays.toString(), label = "完美天数")
                }
            }

            // 3. 热力图日历
            Text("每日热力", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            HeatMapCalendar(currentMonth, heatMap)

            Spacer(modifier = Modifier.height(8.dp))

            // 图例
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.End)) {
                Text("少", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                Spacer(modifier = Modifier.width(4.dp))
                HeatMapCell(color = HeatmapGray) // 0
                HeatMapCell(color = HeatmapGreenLight) // 1-2
                HeatMapCell(color = HeatmapGreenDark) // 3+
                Spacer(modifier = Modifier.width(4.dp))
                Text("多", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
        }
    }
}

@Composable
fun StatItem(count: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = count, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
    }
}

@Composable
fun HeatMapCalendar(
    currentMonth: YearMonth,
    data: Map<String, Int>
) {
    val daysInMonth = currentMonth.lengthOfMonth()
    val firstDayOfWeek = currentMonth.atDay(1).dayOfWeek.value // 1 (Mon) - 7 (Sun)
    val emptyCells = firstDayOfWeek - 1

    LazyVerticalGrid(
        columns = GridCells.Fixed(7),
        modifier = Modifier.height(300.dp),
        userScrollEnabled = false
    ) {
        // 星期头
        items(7) {
            Text(
                text = listOf("一","二","三","四","五","六","日")[it],
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }

        // 空白占位
        items(emptyCells) { Spacer(modifier = Modifier) }

        // 日期格子
        items(daysInMonth) { day ->
            val date = currentMonth.atDay(day + 1)
            val dateStr = date.toString()
            val count = data[dateStr] ?: 0

            // 颜色逻辑：根据完成数量加深颜色
            val cellColor = when {
                count == 0 -> HeatmapGray
                count < 3 -> HeatmapGreenLight
                else -> HeatmapGreenDark
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.aspectRatio(1f).padding(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(4.dp))
                        .background(cellColor)
                )
                Text(
                    text = "${day + 1}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (count >= 3) Color.White else Color.Black
                )
            }
        }
    }
}

@Composable
fun HeatMapCell(color: Color) {
    Box(
        modifier = Modifier
            .size(12.dp)
            .padding(1.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(color)
    )
}
