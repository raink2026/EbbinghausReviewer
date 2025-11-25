
package com.ebbinghaus.review.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
    import androidx.lifecycle.viewModelScope
    import com.ebbinghaus.review.data.AppDatabase
    import com.ebbinghaus.review.data.PlanItem
    import com.ebbinghaus.review.data.PlanStatus
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.SharingStarted
    import kotlinx.coroutines.flow.asStateFlow
    import kotlinx.coroutines.flow.flatMapLatest
    import kotlinx.coroutines.flow.stateIn
    import kotlinx.coroutines.launch
    import java.time.LocalDate
    import java.time.YearMonth

    class PlanViewModel(application: Application) : AndroidViewModel(application) {
        private val dao = AppDatabase.getDatabase(application).planDao()
        private val _currentDate = MutableStateFlow(LocalDate.now().toString())

        // 实时获取今日计划
        val todayPlans = _currentDate.flatMapLatest { date ->
            dao.getPlansByDate(date)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        // 检查旧账 (返回过期任务列表)
        suspend fun checkOverdueTasks(): List<PlanItem> {
            val today = LocalDate.now().toString()
            return dao.getOverduePlans(today)
        }

        // 动作：添加任务
        fun addPlan(content: String, isPriority: Boolean = false) {
            viewModelScope.launch {
                dao.insert(PlanItem(content = content, date = LocalDate.now().toString(), isPriority = isPriority))
            }
        }

        // 动作：切换完成状态
        fun toggleStatus(item: PlanItem) {
            viewModelScope.launch {
                val newStatus = if (item.status == PlanStatus.DONE) PlanStatus.TODO else PlanStatus.DONE
                dao.update(item.copy(status = newStatus))
            }
        }

        // 动作：放弃任务 (Explicit Give Up)
        fun giveUpTask(item: PlanItem) {
            viewModelScope.launch {
                dao.update(item.copy(status = PlanStatus.GIVEN_UP))
            }
        }

        // 动作：旧账处理 - 顺延到今天
        fun carryOverTask(item: PlanItem) {
            viewModelScope.launch {
                dao.update(item.copy(date = LocalDate.now().toString()))
            }
        }

        // 状态：统计数据 (Key: 日期字符串 "yyyy-MM-dd", Value: 完成数量)
        private val _monthlyHeatMap = MutableStateFlow<Map<String, Int>>(emptyMap())
        val monthlyHeatMap = _monthlyHeatMap.asStateFlow()

        // 状态：当前统计的月份
        private val _statsMonth = MutableStateFlow(YearMonth.now())
        val statsMonth = _statsMonth.asStateFlow()

        // 动作：加载某个月的热力图数据
        fun loadMonthlyStats(yearMonth: YearMonth) {
            viewModelScope.launch {
                _statsMonth.value = yearMonth
                // 格式化为 "2023-10" 这样的前缀进行模糊查询
                val monthPrefix = "${yearMonth.year}-${String.format("%02d", yearMonth.monthValue)}"
                val stats = dao.getMonthlyHeatMap(monthPrefix)

                // 转换为 Map 方便 UI 索引
                _monthlyHeatMap.value = stats.associate { it.date to it.count }
            }
        }

        // 动作：切换月份
        fun changeStatsMonth(amount: Long) {
            val newMonth = _statsMonth.value.plusMonths(amount)
            loadMonthlyStats(newMonth)
        }
    }

