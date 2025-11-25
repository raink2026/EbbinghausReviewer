package com.ebbinghaus.review.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PlanDao {
    // 获取指定日期的任务，优先显示重要的，其次按创建时间
    @Query("SELECT * FROM plan_items WHERE date = :date ORDER BY isPriority DESC, createdTime ASC")
    fun getPlansByDate(date: String): Flow<List<PlanItem>>

    // 【防拖延核心】查找所有日期在今天之前，且状态还是 TODO 的任务
    @Query("SELECT * FROM plan_items WHERE date < :today AND status = 'TODO'")
    suspend fun getOverduePlans(today: String): List<PlanItem>

    // 【热力图预埋】统计某个月每一天的完成数量 (例如查询 "2023-10%")
    @Query("SELECT date, COUNT(*) as count FROM plan_items WHERE status = 'DONE' AND date LIKE :monthPrefix || '%' GROUP BY date")
    suspend fun getMonthlyHeatMap(monthPrefix: String): List<DailyStat>

    @Insert
    suspend fun insert(item: PlanItem)

    @Update
    suspend fun update(item: PlanItem)

    @Delete
    suspend fun delete(item: PlanItem)
}

// 辅助类，用于承载热力图数据
data class DailyStat(val date: String, val count: Int)
