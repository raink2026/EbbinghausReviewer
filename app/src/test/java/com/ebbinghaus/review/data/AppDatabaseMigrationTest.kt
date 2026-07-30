package com.ebbinghaus.review.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Database(
    entities = [ReviewItem::class, ReviewLog::class, PlanItem::class, User::class],
    version = 5,
    exportSchema = false
)
abstract class LegacyVersionFiveDatabase : RoomDatabase()

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AppDatabaseMigrationTest {
    @Test
    fun migrationFiveToSixRetainsLegacyRowsAndAddsEmptySyncSchema() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-${System.nanoTime()}.db"
        val legacy = Room.databaseBuilder(context, LegacyVersionFiveDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        try {
            legacy.openHelper.writableDatabase.execSQL(
                "INSERT INTO review_items " +
                    "(id,title,description,content,imagePaths,createdTime,stage,nextReviewTime," +
                    "isFinished,isDeleted,deletedTime) VALUES " +
                    "(42,'Legacy','Description','Body',NULL,1000,2,2000,0,0,NULL)"
            )
            legacy.openHelper.writableDatabase.execSQL(
                "INSERT INTO review_logs " +
                    "(id,itemId,reviewTime,stageBefore,action,stageAfter) " +
                    "VALUES (43,42,1500,1,'REMEMBER',2)"
            )
        } finally {
            legacy.close()
        }

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_5_6)
            .allowMainThreadQueries()
            .build()
        try {
            migrated.openHelper.writableDatabase.query(
                "SELECT title,stage,nextReviewTime FROM review_items WHERE id=42"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Legacy", cursor.getString(0))
                assertEquals(2, cursor.getInt(1))
                assertEquals(2000L, cursor.getLong(2))
            }
            migrated.openHelper.writableDatabase.query(
                "SELECT COUNT(*) FROM review_logs WHERE itemId=42"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            listOf(
                "sync_profiles",
                "remote_repositories",
                "sync_notes",
                "note_revisions",
                "sync_review_events",
                "sync_assets",
                "sync_outbox",
                "sync_checkpoints"
            ).forEach { table ->
                migrated.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table")
                    .use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(0, cursor.getInt(0))
                    }
            }
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }
}
