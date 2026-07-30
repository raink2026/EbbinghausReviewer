package com.ebbinghaus.review.data

import android.content.Context
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.ebbinghaus.review.data.sync.Asset
import com.ebbinghaus.review.data.sync.Note
import com.ebbinghaus.review.data.sync.NoteRevision
import com.ebbinghaus.review.data.sync.Profile
import com.ebbinghaus.review.data.sync.RemoteRepository
import com.ebbinghaus.review.data.sync.ReviewEvent
import com.ebbinghaus.review.data.sync.SyncCheckpoint
import com.ebbinghaus.review.data.sync.SyncOutbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Database(
    entities = [ReviewItem::class, ReviewLog::class, PlanItem::class, LegacyVersionSixUser::class],
    version = 5,
    exportSchema = false
)
abstract class LegacyVersionFiveDatabase : RoomDatabase()

@Entity(tableName = "users")
data class LegacyVersionSixUser(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val avatarUrl: String? = null,
    val isCurrent: Boolean = false,
    val showMenuLabels: Boolean = true,
    val homeIcon: String = "Home",
    val planIcon: String = "DateRange",
    val profileIcon: String = "Person",
    val themeColor: Long? = null,
    val fontScale: Float = 1.0f
)

@Database(
    entities = [
        ReviewItem::class,
        ReviewLog::class,
        PlanItem::class,
        LegacyVersionSixUser::class,
        Profile::class,
        RemoteRepository::class,
        Note::class,
        NoteRevision::class,
        ReviewEvent::class,
        Asset::class,
        SyncOutbox::class,
        SyncCheckpoint::class
    ],
    version = 6,
    exportSchema = false
)
abstract class LegacyVersionSixDatabase : RoomDatabase()

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AppDatabaseMigrationTest {
    @Test
    fun migrationSixToSevenBackfillsThemeConfigurationAndPreservesLegacyBackground() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-theme-${System.nanoTime()}.db"
        val legacy = Room.databaseBuilder(context, LegacyVersionSixDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        try {
            legacy.openHelper.writableDatabase.execSQL(
                "INSERT INTO users " +
                    "(id,name,avatarUrl,isCurrent,showMenuLabels,homeIcon,planIcon,profileIcon,themeColor,fontScale) " +
                    "VALUES (1,'Default',NULL,1,1,'Home','DateRange','Person',NULL,1.0)"
            )
            legacy.openHelper.writableDatabase.execSQL(
                "INSERT INTO users " +
                    "(id,name,avatarUrl,isCurrent,showMenuLabels,homeIcon,planIcon,profileIcon,themeColor,fontScale) " +
                    "VALUES (2,'Legacy color',NULL,0,1,'Home','DateRange','Person',4293453557,1.0)"
            )
        } finally {
            legacy.close()
        }

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_6_7)
            .allowMainThreadQueries()
            .build()
        try {
            migrated.openHelper.writableDatabase.query(
                "SELECT themePreset,customThemeDark,customThemeFont,customThemeBackground " +
                    "FROM users ORDER BY id"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("wechat", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals("system", cursor.getString(2))
                assertTrue(cursor.isNull(3))

                assertTrue(cursor.moveToNext())
                assertEquals("custom", cursor.getString(0))
                assertEquals(4293453557L, cursor.getLong(3))
            }
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun migrationFiveToSevenRetainsLegacyRowsAndAddsEmptySyncSchema() {
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
            .addMigrations(AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7)
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
