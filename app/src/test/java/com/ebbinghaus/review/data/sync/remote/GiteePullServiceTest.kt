package com.ebbinghaus.review.data.sync.remote

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ebbinghaus.review.data.AppDatabase
import com.ebbinghaus.review.data.sync.Profile
import com.ebbinghaus.review.data.sync.RemoteRepository
import com.ebbinghaus.review.data.sync.SyncCheckpoint
import com.ebbinghaus.review.data.sync.protocol.NoteDocumentCodec
import com.ebbinghaus.review.data.sync.protocol.NoteFrontMatter
import com.ebbinghaus.review.data.sync.protocol.sha256
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class GiteePullServiceTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var transport: FakeGiteeTransport
    private lateinit var service: GiteePullService
    private lateinit var profile: Profile
    private lateinit var repository: RemoteRepository

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        transport = FakeGiteeTransport()
        service = GiteePullService(context, database, transport)
        profile = Profile(
            profileId = UUID.randomUUID().toString(),
            displayName = "Profile",
            timezone = "Asia/Shanghai",
            algorithmId = "ebbinghaus-8-stage",
            algorithmVersion = 1,
            algorithmParametersJson = "{\"interval_days\":[1,2,4,7,15,30,60,120]}"
        )
        repository = RemoteRepository(
            UUID.randomUUID().toString(),
            profile.profileId,
            "owner",
            "repository",
            "main",
            "credential"
        )
        database.profileDao().insert(profile)
        database.remoteRepositoryDao().insert(repository)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun paginatesToCheckpointAppliesOldestFirstAndRepeatsWithoutRows() = runBlocking {
        saveCheckpoint("old")
        transport.branchValue = GiteeBranch("main", "commit-100")
        transport.commitPages[1] = (100 downTo 1).map { index ->
            GiteeCommitSummary("commit-$index", "commit $index", null)
        }
        transport.commitPages[2] = listOf(GiteeCommitSummary("old", "checkpoint", null))

        val first = service.pull(profile.profileId)
        val second = service.pull(profile.profileId)

        assertEquals(100, first.appliedCommits)
        assertEquals("commit-100", database.syncStateDao().getCheckpoint(profile.profileId)?.remoteCommitSha)
        assertEquals(listOf(1, 2), transport.requestedPages)
        assertEquals(0, second.appliedCommits)
        assertTrue(database.noteProjectionDao().getNotes(profile.profileId).isEmpty())
    }

    @Test
    fun unreachableCheckpointStopsIncrementalPull() = runBlocking {
        saveCheckpoint("old")
        transport.branchValue = GiteeBranch("main", "rewritten")
        transport.commitPages[1] = listOf(GiteeCommitSummary("rewritten", "new root", null))

        val failure = runCatching { service.pull(profile.profileId) }

        assertTrue(failure.exceptionOrNull() is CheckpointUnreachableException)
        assertEquals("old", database.syncStateDao().getCheckpoint(profile.profileId)?.remoteCommitSha)
        assertEquals("ERROR", database.remoteRepositoryDao().getForProfile(profile.profileId)?.syncState)
    }

    @Test
    fun modifiedDataFileIsRejectedWithoutCheckpointAdvance() = runBlocking {
        saveCheckpoint("old")
        transport.branchValue = GiteeBranch("main", "new")
        transport.commitPages[1] = listOf(
            GiteeCommitSummary("new", "invalid", null),
            GiteeCommitSummary("old", "checkpoint", null)
        )
        transport.commitDetails["new"] = JsonObject().apply {
            add("files", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("filename", "2026-07-22/notes/${UUID.randomUUID()}.md")
                    addProperty("status", "modified")
                })
            })
        }

        val failure = runCatching { service.pull(profile.profileId) }

        assertTrue(failure.exceptionOrNull() is RemoteValidationException)
        assertEquals("old", database.syncStateDao().getCheckpoint(profile.profileId)?.remoteCommitSha)
    }

    @Test
    fun emptyDeviceRebuildPreviewValidatesCanonicalMarkdown() = runBlocking {
        val noteId = UUID.randomUUID().toString()
        val revisionId = UUID.randomUUID().toString()
        val sourceDeviceId = UUID.randomUUID().toString()
        val path = "2026-07-22/notes/$revisionId.md"
        val bytes = NoteDocumentCodec.serialize(
            NoteFrontMatter(
                noteId = noteId,
                revisionId = revisionId,
                parentRevisionIds = emptyList(),
                revisionKind = "create",
                authoredAt = "2026-07-22T12:00:00+08:00",
                learningStartedAt = "2026-07-22T12:00:00+08:00",
                sourceDeviceId = sourceDeviceId,
                contentSha256 = ""
            ),
            "# Imported note\n"
        )
        val blobSha = sha256(bytes)
        transport.branchValue = GiteeBranch("main", "head")
        transport.treeEntries = listOf(GiteeTreeEntry(path, "blob", blobSha, bytes.size.toLong()))
        transport.blobs[blobSha] = GiteeBlob(blobSha, bytes)

        val preview = service.previewFullRebuild(profile.profileId)

        assertTrue(preview.canApply)
        assertEquals(1, preview.noteCount)
        assertEquals(0, preview.assetCount)
        assertEquals(0, preview.eventCount)
    }

    private suspend fun saveCheckpoint(sha: String) {
        database.syncStateDao().saveCheckpoint(
            SyncCheckpoint(
                checkpointId = UUID.randomUUID().toString(),
                profileId = profile.profileId,
                repositoryId = repository.repositoryId,
                remoteCommitSha = sha
            )
        )
    }
}
