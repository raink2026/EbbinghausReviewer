package com.ebbinghaus.review.workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ebbinghaus.review.MainActivity
import com.ebbinghaus.review.R
import com.ebbinghaus.review.data.AppDatabase

// CoroutineWorker 相当于一个在后台线程运行的 Job
class ReviewWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    // doWork 就是 execute() 方法，后台任务的入口
    override suspend fun doWork(): Result {
        val context = applicationContext
        
        // 1. 获取数据库实例
        val db = AppDatabase.getDatabase(context)
        
        // 2. 查询是否有到期的任务
        val dueCount = db.reviewDao().getDueCountSync(System.currentTimeMillis())
        
        // 3. 如果有待复习项，发送通知
        if (dueCount > 0) {
            sendNotification(context, dueCount)
        }

        // Result.success() 告诉系统任务执行成功
        return Result.success()
    }

    private fun sendNotification(context: Context, count: Int) {
        val channelId = "review_channel"
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Android 8.0+ 必须创建通知渠道
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "复习提醒", // 用户在设置里看到的渠道名
                NotificationManager.IMPORTANCE_HIGH // 高优先级，会发出声音并悬浮
            )
            manager.createNotificationChannel(channel)
        }

        // 点击通知打开 MainActivity
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // 使用默认图标
            .setContentTitle("该复习啦！")
            .setContentText("你有 $count 个知识点需要现在复习")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true) // 点击后自动消失
            .build()

        manager.notify(1, notification)
    }
}
