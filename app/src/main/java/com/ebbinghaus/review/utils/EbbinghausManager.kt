package com.ebbinghaus.review.utils

import java.time.Instant
import java.time.temporal.ChronoUnit

// object 关键字在 Kotlin 中即“单例模式”，相当于 Java 的 static utils 类
object EbbinghausManager {

    // 复习周期配置 (单位：分钟)
    // Stage 0: 10 mins (Learning/Forgot)
    // Stage 1: 1 day
    // Stage 2: 2 days
    // ...
    private val INTERVALS_MINUTES = listOf(
        10L,                // Stage 0
        1 * 24 * 60L,       // Stage 1 (1 day)
        2 * 24 * 60L,       // Stage 2 (2 days)
        4 * 24 * 60L,       // Stage 3 (4 days)
        7 * 24 * 60L,       // Stage 4 (7 days)
        15 * 24 * 60L,      // Stage 5 (15 days)
        30 * 24 * 60L,      // Stage 6 (30 days)
        60 * 24 * 60L,      // Stage 7 (60 days)
        120 * 24 * 60L      // Stage 8 (120 days)
    )

    /**
     * 计算下一次复习的时间戳
     * @param currentStage 当前处于第几阶段 (从0开始)
     * @param fromTime 基准时间，通常是当前时间或上一次复习时间
     */
    fun calculateNextReviewTime(currentStage: Int, fromTime: Long = System.currentTimeMillis()): Long {
        if (currentStage >= INTERVALS_MINUTES.size) {
            return -1L // -1 表示已完成所有复习
        }

        val minutesToAdd = INTERVALS_MINUTES[currentStage]
        
        return Instant.ofEpochMilli(fromTime)
            .plus(minutesToAdd, ChronoUnit.MINUTES)
            .toEpochMilli()
    }

    /**
     * 获取短间隔描述 (用于按钮)
     */
    fun getIntervalDescription(stage: Int): String {
        if (stage >= INTERVALS_MINUTES.size) {
            return "Finish"
        }
        val minutes = INTERVALS_MINUTES[stage]
        return if (minutes < 60) {
            "${minutes}m"
        } else {
            val days = minutes / (24 * 60)
            "${days}d"
        }
    }

    /**
     * 获取阶段描述文本
     */
    fun getStageDescription(stage: Int): String {
        return if (stage < INTERVALS_MINUTES.size) {
            val interval = getIntervalDescription(stage)
            "阶段 ${stage + 1} (间隔 $interval)"
        } else {
            "已完成"
        }
    }
}
