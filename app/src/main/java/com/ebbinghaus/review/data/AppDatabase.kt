package com.ebbinghaus.review.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ebbinghaus.review.utils.AppConstants

// 【新增】User::class
@Database(entities = [ReviewItem::class, ReviewLog::class, PlanItem::class, User::class], version = 4, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun reviewDao(): ReviewDao
    abstract fun planDao(): PlanDao
    abstract fun userDao(): UserDao

    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE users ADD COLUMN showMenuLabels INTEGER NOT NULL DEFAULT 1")
                database.execSQL("ALTER TABLE users ADD COLUMN homeIcon TEXT NOT NULL DEFAULT 'Home'")
                database.execSQL("ALTER TABLE users ADD COLUMN planIcon TEXT NOT NULL DEFAULT 'DateRange'")
                database.execSQL("ALTER TABLE users ADD COLUMN profileIcon TEXT NOT NULL DEFAULT 'Person'")
            }
        }

        @Volatile
        private var Instance: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return Instance ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    AppConstants.DB_NAME
                )
                .addMigrations(MIGRATION_3_4)
                // Removed fallbackToDestructiveMigration for data safety
                .build().also { Instance = it }
            }
        }
    }
}