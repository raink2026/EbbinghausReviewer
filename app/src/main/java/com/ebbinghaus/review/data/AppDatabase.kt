package com.ebbinghaus.review.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.ebbinghaus.review.utils.AppConstants

// 【新增】User::class
@Database(entities = [ReviewItem::class, ReviewLog::class, PlanItem::class, User::class], version = 3, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun reviewDao(): ReviewDao
    abstract fun planDao(): PlanDao
    abstract fun userDao(): UserDao

    companion object {
        @Volatile
        private var Instance: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return Instance ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    AppConstants.DB_NAME
                )
                // Removed fallbackToDestructiveMigration for data safety
                .build().also { Instance = it }
            }
        }
    }
}