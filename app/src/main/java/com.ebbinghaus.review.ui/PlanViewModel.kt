
    package com.ebbinghaus.review.ui

    import android.app.Application
    import androidx.lifecycle.AndroidViewModel
    import androidx.lifecycle.viewModelScope
    import com.ebbinghaus.review.data.AppDatabase
    import com.ebbinghaus.review.data.PlanItem
    import com.ebbinghaus.review.data.PlanStatus
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.SharingStarted
    import kotlinx.coroutines.flow.flatMapLatest
    import kotlinx.coroutines.flow.stateIn
    import kotlinx.coroutines.launch
    import java.time.LocalDate

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
    }

