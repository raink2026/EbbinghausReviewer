package com.ebbinghaus.review.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ebbinghaus.review.utils.AppConstants

@Database(entities = [ReviewItem::class, ReviewLog::class, PlanItem::class, User::class], version = 4, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun reviewDao(): ReviewDao
    abstract fun planDao(): PlanDao
    abstract fun userDao(): UserDao

    companion object {
        @Volatile
        private var Instance: AppDatabase? = null

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE users ADD COLUMN themeColor INTEGER")
                database.execSQL("ALTER TABLE users ADD COLUMN fontScale REAL NOT NULL DEFAULT 1.0")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return Instance ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    AppConstants.DB_NAME
                )
                .addMigrations(MIGRATION_3_4)
                .build().also { Instance = it }
            }
        }
    }
}
