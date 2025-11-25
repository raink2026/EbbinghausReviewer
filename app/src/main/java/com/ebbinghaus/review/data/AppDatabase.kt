package com.ebbinghaus.review.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// 【新增】ReviewLog::class
@Database(entities = [ReviewItem::class, ReviewLog::class, PlanItem::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun reviewDao(): ReviewDao
    abstract fun planDao(): PlanDao

    companion object {
        @Volatile
        private var Instance: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return Instance ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ebbinghaus_db"
                )
                .fallbackToDestructiveMigration()
                .build().also { Instance = it }
            }
        }
    }
}