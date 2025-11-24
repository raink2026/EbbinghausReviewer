package com.ebbinghaus.review.ui

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ebbinghaus.review.data.AppDatabase
import com.ebbinghaus.review.data.ReviewItem
import com.ebbinghaus.review.data.ReviewLog
import com.ebbinghaus.review.utils.EbbinghausManager
import com.ebbinghaus.review.workers.ReviewWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppDatabase.getDatabase(application).reviewDao()

    val dueItems: StateFlow<List<ReviewItem>> = dao.getDueItems(System.currentTimeMillis())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allActiveItems: StateFlow<List<ReviewItem>> = dao.getAllActiveItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val todayReviewedItems: StateFlow<List<ReviewItem>> = run {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val start = calendar.timeInMillis
        
        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        val end = calendar.timeInMillis
        
        dao.getTodayReviewedItems(start, end, System.currentTimeMillis())
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    // 回收站列表
    val deletedItems: StateFlow<List<ReviewItem>> = dao.getDeletedItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // 初始化时清理超过 15 天的垃圾
        viewModelScope.launch {
            val threshold = System.currentTimeMillis() - 15 * 24 * 60 * 60 * 1000L
            dao.deleteExpiredItems(threshold)
        }
    }

    // === 辅助：显示 Toast ===
    private fun showToast(message: String) {
        Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
    }

    // === 业务动作 ===

    fun addItem(title: String, description: String, content: String, imagePaths: List<String>) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // 将图片复制到应用内部存储
                val savedImagePaths = imagePaths.map { uriString ->
                    copyImageToInternalStorage(getApplication(), Uri.parse(uriString)) ?: uriString
                }
                
                val imagesString = if (savedImagePaths.isNotEmpty()) savedImagePaths.joinToString("|") else null
                
                val newItem = ReviewItem(
                    title = title,
                    description = description,
                    content = content,
                    imagePaths = imagesString,
                    nextReviewTime = System.currentTimeMillis(), 
                    stage = 0
                )
                dao.insert(newItem)
            }
            updateWidget()
            loadHeatMapData()
            showToast("添加成功")
        }
    }

    private fun copyImageToInternalStorage(context: Context, uri: Uri): String? {
        return try {
            val contentResolver = context.contentResolver
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val directory = File(context.filesDir, "review_images")
                if (!directory.exists()) directory.mkdirs()

                // 获取扩展名
                val mime = contentResolver.getType(uri)
                val extension = if (mime != null) MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) else "jpg"
                val ext = extension ?: "jpg"

                val fileName = "img_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}.$ext"
                val file = File(directory, fileName)

                FileOutputStream(file).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
                
                // 确保文件存在并且大小大于0
                if (file.exists() && file.length() > 0) {
                    return Uri.fromFile(file).toString()
                } else {
                    // 如果文件创建失败，删除空文件
                    if (file.exists()) {
                        file.delete()
                    }
                }
            }
            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun markAsReviewed(item: ReviewItem, remembered: Boolean) {
        viewModelScope.launch {
            val currentStage = item.stage
            var nextStage = currentStage
            var nextTime = item.nextReviewTime

            if (remembered) {
                nextStage = item.stage + 1
                val calculatedTime = EbbinghausManager.calculateNextReviewTime(item.stage)
                nextTime = if (calculatedTime == -1L) item.nextReviewTime else calculatedTime 
                
                if (calculatedTime == -1L) {
                    dao.update(item.copy(stage = nextStage, isFinished = true))
                } else {
                    dao.update(item.copy(stage = nextStage, nextReviewTime = nextTime))
                }
                showToast("复习打卡成功！进入下一阶段")
            } else {
                // 忘了：重置
                nextStage = 0
                nextTime = EbbinghausManager.calculateNextReviewTime(0)
                dao.update(item.copy(stage = 0, nextReviewTime = nextTime))
                showToast("没关系，进度已重置，下次加油！")
            }

            // 记录 Log
            val log = ReviewLog(
                itemId = item.id,
                reviewTime = System.currentTimeMillis(),
                stageBefore = currentStage,
                action = if (remembered) "REMEMBER" else "FORGET",
                stageAfter = if (remembered && EbbinghausManager.calculateNextReviewTime(item.stage) == -1L) 99 else nextStage
            )
            dao.insertLog(log)

            updateWidget()
            loadHeatMapData()
        }
    }

    // 软删除
    fun moveToTrash(item: ReviewItem) {
        viewModelScope.launch {
            dao.update(item.copy(isDeleted = true, deletedTime = System.currentTimeMillis()))
            updateWidget()
            loadHeatMapData()
            showToast("已移入回收站 (15天后自动清除)")
        }
    }

    // 从回收站恢复
    fun restoreFromTrash(item: ReviewItem) {
        viewModelScope.launch {
            dao.update(item.copy(isDeleted = false, deletedTime = null))
            updateWidget()
            loadHeatMapData()
            showToast("已恢复到复习列表")
        }
    }

    // 彻底删除
    fun deletePermanently(item: ReviewItem) {
        viewModelScope.launch {
            dao.delete(item)
            showToast("已彻底删除，无法找回")
        }
    }

    suspend fun getItemLogs(itemId: Long): List<ReviewLog> {
        return dao.getLogsByItemId(itemId)
    }

    fun snoozeItem(item: ReviewItem) {
        viewModelScope.launch {
            val snoozedTime = System.currentTimeMillis() + 10 * 60 * 1000
            dao.update(item.copy(nextReviewTime = snoozedTime))
            showToast("已推迟 10 分钟")
        }
    }
    
    suspend fun getItemById(id: Long): ReviewItem? {
        return dao.getById(id)
    }

    // === 日历功能区 ===
    
    private val _historyItems = kotlinx.coroutines.flow.MutableStateFlow<List<ReviewItem>>(emptyList())
    val historyItems: StateFlow<List<ReviewItem>> = _historyItems

    private val _hasDataDates = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    val hasDataDates: StateFlow<Set<String>> = _hasDataDates

    fun loadHeatMapData() {
        viewModelScope.launch(Dispatchers.IO) {
            val activeItems = dao.getAllItemsSync().filter { !it.isFinished }
            val dateSet = mutableSetOf<String>()
            val formatter = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

            activeItems.forEach { item ->
                var currentStage = item.stage
                var currentTime = item.nextReviewTime
                for (i in 0..15) {
                    dateSet.add(formatter.format(Date(currentTime)))
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
            val calendar = Calendar.getInstance()
            calendar.set(year, month - 1, day, 0, 0, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            val targetStart = calendar.timeInMillis
            
            calendar.set(year, month - 1, day, 23, 59, 59)
            val end = calendar.timeInMillis

            val activeItems = dao.getAllItemsSync().filter { !it.isFinished }
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