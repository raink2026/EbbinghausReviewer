package com.ebbinghaus.review.ui

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ebbinghaus.review.R
import com.ebbinghaus.review.data.AppDatabase
import com.ebbinghaus.review.data.ReviewItem
import com.ebbinghaus.review.data.ReviewLog
import com.ebbinghaus.review.data.repository.ImageRepository
import com.ebbinghaus.review.data.repository.ReviewRepository
import com.ebbinghaus.review.utils.EbbinghausManager
import com.ebbinghaus.review.workers.ReviewWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainViewModel(application: Application) : AndroidViewModel(application) {

    // Ideally these should be injected via DI (Hilt/Koin), but for now we construct them here.
    private val database = AppDatabase.getDatabase(application)
    private val repository = ReviewRepository(database.reviewDao(), ImageRepository(application))
    private val userDao = database.userDao()

    val currentUser = userDao.getCurrentUser()
<<<<<<< HEAD
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
=======
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
>>>>>>> ce9a4e258236ce94ac915dd3baedfc88fe971f55

    val dueItems: StateFlow<List<ReviewItem>> = repository.getDueItems(System.currentTimeMillis())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allActiveItems: StateFlow<List<ReviewItem>> = repository.allActiveItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val todayReviewedItems: StateFlow<List<ReviewItem>> = run {
        val todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val todayEnd = LocalDate.now().atTime(LocalTime.MAX).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        
        repository.getTodayReviewedItems(todayStart, todayEnd, System.currentTimeMillis())
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    // 回收站列表
    val deletedItems: StateFlow<List<ReviewItem>> = repository.deletedItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // 初始化时清理超过 15 天的垃圾
        viewModelScope.launch {
            val threshold = System.currentTimeMillis() - 15 * 24 * 60 * 60 * 1000L
            repository.deleteExpiredItems(threshold)
        }
    }

    // === 辅助：显示 Toast ===
    private fun showToast(message: String) {
        Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
    }

    // === 业务动作 ===

    fun addItem(title: String, description: String, content: String, imagePaths: List<String>) {
        viewModelScope.launch {
            try {
                repository.addItem(title, description, content, imagePaths)
                updateWidget()
                loadHeatMapData()
                showToast(getApplication<Application>().getString(R.string.add_success))
            } catch (e: Exception) {
                e.printStackTrace()
                showToast("Failed to add item: ${e.message}")
            }
        }
    }

    fun markAsReviewed(item: ReviewItem, remembered: Boolean) {
        viewModelScope.launch {
            repository.markAsReviewed(item, remembered)

            if (remembered) {
                showToast(getApplication<Application>().getString(R.string.review_success))
            } else {
                showToast(getApplication<Application>().getString(R.string.review_reset))
            }

            updateWidget()
            loadHeatMapData()
        }
    }

    // 软删除
    fun moveToTrash(item: ReviewItem) {
        viewModelScope.launch {
            repository.moveToTrash(item)
            updateWidget()
            loadHeatMapData()
            showToast(getApplication<Application>().getString(R.string.moved_to_trash))
        }
    }

    // 从回收站恢复
    fun restoreFromTrash(item: ReviewItem) {
        viewModelScope.launch {
            repository.restoreFromTrash(item)
            updateWidget()
            loadHeatMapData()
            showToast(getApplication<Application>().getString(R.string.restored_from_trash))
        }
    }

    // 彻底删除
    fun deletePermanently(item: ReviewItem) {
        viewModelScope.launch {
            repository.deletePermanently(item)
            showToast(getApplication<Application>().getString(R.string.deleted_permanently))
        }
    }

    suspend fun getItemLogs(itemId: Long): List<ReviewLog> {
        return repository.getItemLogs(itemId)
    }

    fun snoozeItem(item: ReviewItem) {
        viewModelScope.launch {
            val snoozedTime = System.currentTimeMillis() + 10 * 60 * 1000
            repository.updateItem(item.copy(nextReviewTime = snoozedTime))
            showToast(getApplication<Application>().getString(R.string.snoozed))
        }
    }
    
    suspend fun getItemById(id: Long): ReviewItem? {
        return repository.getById(id)
    }

    // === 日历功能区 ===
    
    private val _historyItems = kotlinx.coroutines.flow.MutableStateFlow<List<ReviewItem>>(emptyList())
    val historyItems: StateFlow<List<ReviewItem>> = _historyItems

    private val _hasDataDates = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    val hasDataDates: StateFlow<Set<String>> = _hasDataDates

    fun loadHeatMapData() {
        viewModelScope.launch(Dispatchers.IO) {
            // Optimization: Use specific query to avoid loading heavy content/images
            val minimalItems = repository.getItemsForHeatMap()

            val dateSet = mutableSetOf<String>()
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

            minimalItems.forEach { item ->
                var currentStage = item.stage
                var currentTime = item.nextReviewTime
                // Only calculate if the item is not finished yet, as finished items are not scheduled
                // However, the query already filters isFinished=0.

                // Note: Logic logic was: for i in 0..15.
                // We keep the same logic.
                for (i in 0..15) {
                    val date = Instant.ofEpochMilli(currentTime).atZone(ZoneId.systemDefault()).toLocalDate()
                    dateSet.add(date.format(formatter))
                    val nextTime = EbbinghausManager.calculateNextReviewTime(currentStage, currentTime)
                    if (nextTime == -1L) break
                    currentTime = nextTime
                    currentStage++
                }
            }
            withContext(Dispatchers.Main) {
                _hasDataDates.value = dateSet
            }
        }
    }

    fun selectDate(year: Int, month: Int, day: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val date = LocalDate.of(year, month, day)
            val targetStart = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val end = date.atTime(LocalTime.MAX).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

            // For detailed history, we still need full items to display
            val activeItems = repository.getAllItemsSync().filter { !it.isFinished }
            val planItems = mutableListOf<ReviewItem>()

            activeItems.forEach { item ->
                if (isItemScheduledForDate(item, targetStart, end)) {
                    planItems.add(item)
                }
            }
            withContext(Dispatchers.Main) {
                _historyItems.value = planItems
            }
        }
    }

    private fun isItemScheduledForDate(item: ReviewItem, start: Long, end: Long): Boolean {
        var currentStage = item.stage
        var currentTime = item.nextReviewTime
        for (i in 0..15) {
            if (currentTime >= start && currentTime <= end) return true
            if (currentTime > end) return false
            val nextTime = EbbinghausManager.calculateNextReviewTime(currentStage, currentTime)
            if (nextTime == -1L) break
            currentTime = nextTime
            currentStage++
        }
        return false
    }

    private fun updateWidget() {
        val context = getApplication<Application>()
        val intent = Intent(context, ReviewWidgetProvider::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
        }
        val ids = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, ReviewWidgetProvider::class.java))
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        context.sendBroadcast(intent)
    }
}
