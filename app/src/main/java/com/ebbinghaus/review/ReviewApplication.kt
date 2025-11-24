package com.ebbinghaus.review

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.ebbinghaus.review.workers.ReviewWorker
import java.util.concurrent.TimeUnit

class ReviewApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        setupWorker()
    }

    private fun setupWorker() {
        // 创建一个每 15 分钟运行一次的周期性任务
        // 注意：Android 为了省电，最小间隔锁定为 15 分钟
        val reviewWorkRequest = PeriodicWorkRequestBuilder<ReviewWorker>(
            15, TimeUnit.MINUTES
        ).build()

        // 提交任务
        // KEEP: 如果任务已经存在，保持原样（避免每次启动都重置定时器）
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "ReviewWorker",
            ExistingPeriodicWorkPolicy.KEEP,
            reviewWorkRequest
        )
    }
}
