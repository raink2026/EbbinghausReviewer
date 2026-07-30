package com.ebbinghaus.review.data.migration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ebbinghaus.review.data.AppDatabase
import com.ebbinghaus.review.data.ReviewItem
import com.ebbinghaus.review.data.ReviewLog
import com.ebbinghaus.review.data.sync.Profile
import com.ebbinghaus.review.data.sync.ProfileTimeService
import com.ebbinghaus.review.data.sync.RemoteRepository
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class LegacyMigrationManagerTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("legacy_sync_migration", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("legacy_sync_migration_guard", Context.MODE_PRIVATE)
            .edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun validImageAndReviewHistoryQueueAtomicallyAndRollbackKeepsLegacyData() = runBlocking {
        val profile = insertDestination("018f0000-0000-7000-8000-000000000301")
        val image = File(context.filesDir, "legacy-migration-image.png").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        }
        val createdAt = 1_784_731_200_000L
        val reviewedAt = createdAt + 60_000L
        val next = ProfileTimeService.forProfile(profile).reviewAtStartOfDay(reviewedAt, 2)
        database.reviewDao().insert(
            ReviewItem(
                id = 101,
                title = "Active legacy note",
                description = "Description",
                content = "Body",
                imagePaths = image.toURI().toString(),
                createdTime = createdAt,
                stage = 1,
                nextReviewTime = next
            )
        )
        database.reviewDao().insertLog(
            ReviewLog(201, 101, reviewedAt, 0, "REMEMBER", 1)
        )
        database.reviewDao().insert(
            ReviewItem(
                id = 102,
                title = "Deleted legacy note",
                content = "Keep only in legacy trash",
                createdTime = createdAt,
                nextReviewTime = createdAt,
                isDeleted = true,
                deletedTime = createdAt
            )
        )
        val manager = LegacyMigrationManager(context, database)

        val preview = manager.preview(profile.profileId)
        assertEquals(2, preview.totalItems)
        assertEquals(1, preview.convertedNotes)
        assertEquals(1, preview.skippedDeletedItems)
        assertEquals(1, preview.assetPaths)
        assertEquals(1, preview.eventCount)
        assertTrue(preview.blockingIssues.isEmpty())
        assertTrue(preview.scheduleDifferences.isEmpty())

        manager.queueMigration(profile.profileId, acceptScheduleDifferences = false)
        val notes = database.noteProjectionDao().getNotes(profile.profileId)
        val outbox = database.syncStateDao().getAllOutbox(profile.profileId)
        assertEquals(1, notes.size)
        assertEquals(setOf("CREATE_ASSET", "CREATE_NOTE", "CREATE_EVENT"),
            outbox.mapTo(mutableSetOf()) { it.operationType })
        val noteOperation = outbox.single { it.operationType == "CREATE_NOTE" }
        val eventOperation = outbox.single { it.operationType == "CREATE_EVENT" }
        val eventDependencies = JsonParser.parseString(eventOperation.dependencyIdsJson)
            .asJsonArray.map { it.asString }
        assertEquals(listOf(noteOperation.operationId), eventDependencies)
        assertTrue(image.exists())
        assertTrue(LegacyDataGuard(context).isReadOnly)

        manager.rollbackBeforePublish(profile.profileId)
        assertTrue(database.noteProjectionDao().getNotes(profile.profileId).isEmpty())
        assertTrue(database.syncStateDao().getAllOutbox(profile.profileId).isEmpty())
        assertEquals(2, database.reviewDao().getAllItemsIncludingDeleted().size)
        assertTrue(image.exists())
        assertFalse(LegacyDataGuard(context).isReadOnly)
    }

    @Test
    fun unreadableImageBlocksPublicationWithoutDeletingLegacyRow() = runBlocking {
        val profile = insertDestination("018f0000-0000-7000-8000-000000000302")
        database.reviewDao().insert(
            ReviewItem(
                id = 103,
                title = "Broken image",
                content = "Body",
                imagePaths = "file:///missing/legacy-image.png",
                createdTime = 1_784_731_200_000L,
                nextReviewTime = 1_784_731_200_000L
            )
        )
        val manager = LegacyMigrationManager(context, database)

        val preview = manager.preview(profile.profileId)
        assertEquals(1, preview.blockingIssues.size)
        assertFalse(preview.canQueue)
        val failure = runCatching {
            manager.queueMigration(profile.profileId, acceptScheduleDifferences = true)
        }
        assertTrue(failure.isFailure)
        assertTrue(database.noteProjectionDao().getNotes(profile.profileId).isEmpty())
        assertTrue(database.syncStateDao().getAllOutbox(profile.profileId).isEmpty())
        assertEquals(1, database.reviewDao().getAllItemsIncludingDeleted().size)
    }

    @Test
    fun completeLegacyHistoryReplaysToCompletedSchedule() = runBlocking {
        val profile = insertDestination("018f0000-0000-7000-8000-000000000303")
        val createdAt = 1_784_731_200_000L
        database.reviewDao().insert(
            ReviewItem(
                id = 104,
                title = "Finished note",
                content = "Body",
                createdTime = createdAt,
                stage = 8,
                nextReviewTime = createdAt,
                isFinished = true
            )
        )
        repeat(8) { index ->
            database.reviewDao().insertLog(
                ReviewLog(
                    id = 300L + index,
                    itemId = 104,
                    reviewTime = createdAt + (index + 1) * 60_000L,
                    stageBefore = index,
                    action = "REMEMBER",
                    stageAfter = if (index == 7) 99 else index + 1
                )
            )
        }
        val manager = LegacyMigrationManager(context, database)
        val preview = manager.preview(profile.profileId)
        assertTrue(preview.scheduleDifferences.isEmpty())

        manager.queueMigration(profile.profileId, acceptScheduleDifferences = false)
        val note = database.noteProjectionDao().getNotes(profile.profileId).single()
        assertEquals(8, note.reviewStage)
        assertTrue(note.isReviewComplete)
        assertEquals(null, note.nextReviewAt)
        assertEquals(8, database.noteProjectionDao().getAllEvents(profile.profileId).size)
    }

    @Test
    fun rerunRecoversCommittedMigrationWithoutDuplicatingRowsOrOutbox() = runBlocking {
        val profile = insertDestination("018f0000-0000-7000-8000-000000000304")
        database.reviewDao().insert(
            ReviewItem(
                id = 105,
                title = "Interrupted migration",
                content = "Body",
                createdTime = 1_784_731_200_000L,
                nextReviewTime = 1_784_731_200_000L
            )
        )
        val firstManager = LegacyMigrationManager(context, database)
        firstManager.queueMigration(profile.profileId, acceptScheduleDifferences = true)
        val originalNotes = database.noteProjectionDao().getNotes(profile.profileId)
        val originalOutbox = database.syncStateDao().getAllOutbox(profile.profileId)

        context.getSharedPreferences("legacy_sync_migration", Context.MODE_PRIVATE)
            .edit().clear().commit()
        val restartedManager = LegacyMigrationManager(context, database)
        val recovered = restartedManager.queueMigration(
            profile.profileId,
            acceptScheduleDifferences = true
        )

        assertEquals(LegacyMigrationStatus.QUEUED, restartedManager.status(profile.profileId))
        assertEquals(1, recovered.convertedNotes)
        assertEquals(originalNotes, database.noteProjectionDao().getNotes(profile.profileId))
        assertEquals(originalOutbox, database.syncStateDao().getAllOutbox(profile.profileId))
        assertTrue(LegacyDataGuard(context).isReadOnly)
    }

    private suspend fun insertDestination(profileId: String): Profile {
        val profile = Profile(
            profileId = profileId,
            displayName = "Migration destination",
            timezone = "Asia/Shanghai",
            algorithmId = "ebbinghaus-8-stage",
            algorithmVersion = 1,
            algorithmParametersJson = "{\"interval_days\":[1,2,4,7,15,30,60,120]}",
            isCurrent = true
        )
        database.profileDao().insert(profile)
        database.remoteRepositoryDao().insert(
            RemoteRepository(
                repositoryId = UUID.randomUUID().toString(),
                profileId = profileId,
                owner = "owner",
                name = "repository",
                branch = "main",
                credentialAlias = "credential-$profileId"
            )
        )
        return profile
    }
}
