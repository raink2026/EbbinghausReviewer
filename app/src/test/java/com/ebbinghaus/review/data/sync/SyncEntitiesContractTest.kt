package com.ebbinghaus.review.data.sync

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Database(
    entities = [
        Profile::class,
        RemoteRepository::class,
        Note::class,
        NoteRevision::class,
        ReviewEvent::class,
        Asset::class,
        SyncOutbox::class,
        SyncCheckpoint::class
    ],
    version = 1,
    exportSchema = false
)
abstract class SyncEntitiesContractDatabase : RoomDatabase()

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SyncEntitiesContractTest {
    private lateinit var database: SyncEntitiesContractDatabase

    @Before
    fun openDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(
            context,
            SyncEntitiesContractDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun profileOwnedEntitiesExposeStableUuidRelationships() {
        val profileId = "018f0000-0000-7000-8000-000000000101"
        val repositoryId = "018f0000-0000-7000-8000-000000000102"
        val noteId = "018f0000-0000-7000-8000-000000000103"
        val revisionId = "018f0000-0000-7000-8000-000000000104"

        val profile = Profile(
            profileId = profileId,
            displayName = "Default",
            timezone = "Asia/Shanghai",
            algorithmId = "ebbinghaus-8-stage",
            algorithmVersion = 1,
            algorithmParametersJson = "{\"interval_days\":[1,2,4,7,15,30,90,180]}"
        )
        val repository = RemoteRepository(
            repositoryId = repositoryId,
            profileId = profileId,
            owner = "owner",
            name = "notes",
            branch = "main",
            credentialAlias = "gitee-profile-101"
        )
        val note = Note(
            noteId = noteId,
            profileId = profileId,
            title = "Title"
        )
        val revision = NoteRevision(
            revisionId = revisionId,
            profileId = profileId,
            noteId = noteId,
            parentRevisionIdsJson = "[]",
            revisionKind = "create",
            authoredAt = 1784731200000,
            learningStartedAt = 1784731200000,
            sourceDeviceId = "018f0000-0000-7000-8000-000000000105",
            contentSha256 = "a".repeat(64),
            markdownCachePath = "notes/a.md"
        )
        val event = ReviewEvent(
            eventId = "018f0000-0000-7000-8000-000000000106",
            profileId = profileId,
            noteId = noteId,
            revisionId = revisionId,
            streamId = "review:$revisionId",
            parentEventIdsJson = "[]",
            eventType = "review",
            occurredAt = 1784731200000,
            sourceDeviceId = "018f0000-0000-7000-8000-000000000105",
            algorithmId = "ebbinghaus-8-stage",
            algorithmVersion = 1,
            payloadJson = "{}",
            contentSha256 = "b".repeat(64)
        )
        val asset = Asset(
            assetId = "018f0000-0000-7000-8000-000000000107",
            profileId = profileId,
            sha256 = "c".repeat(64),
            extension = "png",
            byteSize = 42,
            cachePath = "assets/c.png"
        )
        val outbox = SyncOutbox(
            operationId = "018f0000-0000-7000-8000-000000000108",
            profileId = profileId,
            repositoryId = repositoryId,
            operationType = "create_note",
            repositoryPath = "2026-07-22/notes/$revisionId.md",
            contentSha256 = revision.contentSha256,
            payloadCachePath = "payloads/$revisionId.md",
            dependencyIdsJson = "[]",
            profileDate = "2026-07-22"
        )
        val checkpoint = SyncCheckpoint(
            checkpointId = "018f0000-0000-7000-8000-000000000109",
            profileId = profileId,
            repositoryId = repositoryId
        )

        assertEquals(profile.profileId, repository.profileId)
        assertEquals(profile.profileId, note.profileId)
        assertEquals(note.noteId, revision.noteId)
        assertEquals(revision.revisionId, event.revisionId)
        assertEquals(profile.profileId, asset.profileId)
        assertEquals(repository.repositoryId, outbox.repositoryId)
        assertEquals(repository.repositoryId, checkpoint.repositoryId)
        assertNull(checkpoint.remoteCommitSha)
    }

    @Test(expected = SQLiteConstraintException::class)
    fun revisionCannotReferenceNoteFromAnotherProfile() {
        insertProfile("profile-a")
        insertProfile("profile-b")
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO sync_notes " +
                "(noteId, profileId, title, activeRevisionId, projectionState, createdAt, updatedAt) " +
                "VALUES ('note-b', 'profile-b', 'B', NULL, 'ACTIVE', 1, 1)"
        )

        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO note_revisions " +
                "(revisionId, profileId, noteId, parentRevisionIdsJson, revisionKind, authoredAt, " +
                "learningStartedAt, sourceDeviceId, contentSha256, markdownCachePath, " +
                "repositoryPath, createdAt) VALUES " +
                "('revision-a', 'profile-a', 'note-b', '[]', 'create', 1, 1, 'device', " +
                "'hash', 'cache', NULL, 1)"
        )
    }

    @Test(expected = SQLiteConstraintException::class)
    fun outboxCannotTargetRepositoryFromAnotherProfile() {
        insertProfile("profile-a")
        insertProfile("profile-b")
        insertRepository("repository-b", "profile-b")

        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO sync_outbox " +
                "(operationId, profileId, repositoryId, operationType, repositoryPath, " +
                "contentSha256, payloadCachePath, dependencyIdsJson, profileDate, batchId, status, attemptCount, " +
                "nextAttemptAt, lastError, createdAt, updatedAt) VALUES " +
                "('operation-a', 'profile-a', 'repository-b', 'create_note', 'path', 'hash', " +
                "'payload', '[]', '2026-07-22', NULL, 'PENDING', 0, NULL, NULL, 1, 1)"
        )
    }

    @Test(expected = SQLiteConstraintException::class)
    fun noteCannotActivateRevisionFromAnotherProfile() {
        insertProfile("profile-a")
        insertProfile("profile-b")
        insertNote("note-a", "profile-a")
        insertNote("note-b", "profile-b")
        insertRevision("revision-b", "profile-b", "note-b")

        database.openHelper.writableDatabase.execSQL(
            "UPDATE sync_notes SET activeRevisionId = 'revision-b' WHERE noteId = 'note-a'"
        )
    }

    private fun insertProfile(profileId: String) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO sync_profiles " +
                "(profileId, displayName, timezone, algorithmId, algorithmVersion, " +
                "algorithmParametersJson, isCurrent, createdAt, updatedAt) VALUES " +
                "('$profileId', 'Profile', 'Asia/Shanghai', 'ebbinghaus-8-stage', 1, '{}', 0, 1, 1)"
        )
    }

    private fun insertRepository(repositoryId: String, profileId: String) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO remote_repositories " +
                "(repositoryId, profileId, owner, name, branch, credentialAlias, autoSync, " +
                "wifiOnly, createdAt, updatedAt) VALUES " +
                "('$repositoryId', '$profileId', 'owner', 'repo', 'main', 'alias', 1, 0, 1, 1)"
        )
    }

    private fun insertNote(noteId: String, profileId: String) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO sync_notes " +
                "(noteId, profileId, title, activeRevisionId, projectionState, createdAt, updatedAt) " +
                "VALUES ('$noteId', '$profileId', 'Title', NULL, 'ACTIVE', 1, 1)"
        )
    }

    private fun insertRevision(revisionId: String, profileId: String, noteId: String) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO note_revisions " +
                "(revisionId, profileId, noteId, parentRevisionIdsJson, revisionKind, authoredAt, " +
                "learningStartedAt, sourceDeviceId, contentSha256, markdownCachePath, " +
                "repositoryPath, createdAt) VALUES " +
                "('$revisionId', '$profileId', '$noteId', '[]', 'create', 1, 1, 'device', " +
                "'hash', 'cache', NULL, 1)"
        )
    }
}
