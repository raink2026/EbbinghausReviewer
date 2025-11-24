package com.ebbinghaus.review.utils

import java.util.Calendar

// object 关键字在 Kotlin 中即“单例模式”，相当于 Java 的 static utils 类
object EbbinghausManager {

    // 复习周期配置 (单位：天)
    // 第0阶段是刚创建，第1阶段是1天后，第2阶段是2天后...
    private val INTERVAL_DAYS = listOf(1, 2, 4, 7, 15, 30, 60, 120)

    /**
     * 计算下一次复习的时间戳
     * @param currentStage 当前处于第几阶段 (从0开始)
     * @param fromTime 基准时间，通常是当前时间或上一次复习时间
     */
    fun calculateNextReviewTime(currentStage: Int, fromTime: Long = System.currentTimeMillis()): Long {
        if (currentStage >= INTERVAL_DAYS.size) {
            return -1L // -1 表示已完成所有复习
        }

        val daysToAdd = INTERVAL_DAYS[currentStage]
        
        // 使用 Calendar 进行日期计算 (Java 8+ 也可用 Instant/LocalDateTime，但在 Android 兼容性上 Calendar 依然稳健)
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = fromTime
        calendar.add(Calendar.DAY_OF_YEAR, daysToAdd)
        
        return calendar.timeInMillis
    }

    /**
     * 获取阶段描述文本
     */
    fun getStageDescription(stage: Int): String {
        return if (stage < INTERVAL_DAYS.size) {
            "阶段 ${stage + 1} (间隔 ${INTERVAL_DAYS[stage]} 天)"
        } else {
            "已完成"
        }
    }
}
