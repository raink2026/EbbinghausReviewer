package com.ebbinghaus.review.workers

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.ebbinghaus.review.MainActivity
import com.ebbinghaus.review.R
import com.ebbinghaus.review.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.ebbinghaus.review.data.ReviewDao

class ReviewWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val dao = AppDatabase.getDatabase(context).reviewDao()
        // 遍历所有 Widget 实例进行更新
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId, dao)
        }
    }
}

// 核心更新逻辑
fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, dao: ReviewDao) {
    // 1. 获取 DB 数据 (Widget 必须在协程或后台线程获取数据)
    CoroutineScope(Dispatchers.IO).launch {
        val dueCount = try {
            // 使用 Step 5 中为 Worker 添加的同步查询方法
            dao.getDueCountSync(System.currentTimeMillis())
        } catch (e: Exception) {
            0
        }

        // 2. 构建 RemoteViews (远程视图)
        val views = RemoteViews(context.packageName, R.layout.review_widget_layout)
        
        // 设置数据显示
        views.setTextViewText(R.id.widget_count, dueCount.toString())
        views.setTextViewText(R.id.widget_subtitle, if (dueCount > 0) "个知识点待复习" else "全部完成，真棒！")

        // 3. 设置点击事件：点击 widget 启动 MainActivity
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // 将点击事件绑定到整个热区
        views.setOnClickPendingIntent(R.id.widget_open_app_area, pendingIntent) 

        // 4. 通知系统更新视图
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
