package com.ebbinghaus.review.data.sync.remote

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ebbinghaus.review.data.AppDatabase
import com.ebbinghaus.review.data.sync.Profile
import com.ebbinghaus.review.data.sync.RemoteRepository
import com.ebbinghaus.review.data.sync.SyncCheckpoint
import com.ebbinghaus.review.data.sync.SyncOutbox
import com.ebbinghaus.review.data.sync.protocol.sha256
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
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
class GiteePushServiceTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var transport: FakeGiteeTransport
    private lateinit var service: GiteePushService
    private lateinit var profile: Profile
    private lateinit var repository: RemoteRepository
    private val payloads = mutableListOf<File>()

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        transport = FakeGiteeTransport()
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
        saveCheckpoint("head")
        val pullService = GiteePullService(context, database, transport)
        service = GiteePushService(database, transport, pullService)
    }

    @After
    fun tearDown() {
        database.close()
        payloads.forEach(File::delete)
    }

    @Test
    fun publishesAndAcknowledgesOfflineOperation() = runBlocking {
        val operation = insertOperation()

        val result = service.push(profile.profileId)

        assertEquals(1, result.commitsCreated)
        assertEquals(1, result.operationsAcknowledged)
        assertTrue(database.syncStateDao().getAllOutbox(profile.profileId).isEmpty())
        assertEquals(operation.repositoryPath, transport.createdCommits.single().actions.single().path)
        assertTrue("Ebbinghaus-Batch-Id:" in transport.createdCommits.single().message)
    }

    @Test
    fun recoversCommittedBatchAfterAppCrashWithoutDuplicateCommit() = runBlocking {
        val batchId = UUID.randomUUID().toString()
        val operation = insertOperation(status = "IN_FLIGHT", batchId = batchId)
        val remoteSha = "already-created"
        transport.branchValue = GiteeBranch("main", "head")
        transport.commitPages[1] = listOf(
            GiteeCommitSummary(remoteSha, "sync\n\nEbbinghaus-Batch-Id: $batchId", null)
        )
        transport.commitDetails[remoteSha] = JsonObject().apply {
            add("files", JsonArray().apply {
                add(JsonObject().apply { addProperty("filename", operation.repositoryPath) })
            })
        }
        val bytes = File(operation.payloadCachePath).readBytes()
        transport.contents[operation.repositoryPath to remoteSha] = GiteeRemoteFile(
            operation.repositoryPath,
            "blob",
            bytes,
            remoteSha
        )

        val result = service.push(profile.profileId)

        assertEquals(0, result.commitsCreated)
        assertEquals(1, result.operationsAcknowledged)
        assertTrue(transport.createdCommits.isEmpty())
        assertTrue(database.syncStateDao().getAllOutbox(profile.profileId).isEmpty())
    }

    @Test
    fun rateLimitKeepsBatchForRetry() = runBlocking {
        insertOperation()
        transport.contentFailure = { _, _ -> GiteeApiException(429, "retry later") }

        val failure = runCatching { service.push(profile.profileId) }

        assertEquals(SyncFailureKind.TRANSIENT, (failure.exceptionOrNull() as SyncPipelineException).kind)
        val pending = database.syncStateDao().getAllOutbox(profile.profileId).single()
        assertEquals("RETRY", pending.status)
        assertEquals(1, pending.attemptCount)
        assertTrue(pending.nextAttemptAt != null)
    }

    @Test
    fun invalidTokenPausesBatchUntilCredentialRepair() = runBlocking {
        insertOperation()
        transport.contentFailure = { _, _ -> GiteeApiException(401, "invalid token") }

        val failure = runCatching { service.push(profile.profileId) }

        assertEquals(SyncFailureKind.PERSISTENT, (failure.exceptionOrNull() as SyncPipelineException).kind)
        val pending = database.syncStateDao().getAllOutbox(profile.profileId).single()
        assertEquals("PAUSED", pending.status)
        assertTrue(pending.nextAttemptAt == null)
    }

    private suspend fun insertOperation(
        status: String = "PENDING",
        batchId: String? = null
    ): SyncOutbox {
        val revisionId = UUID.randomUUID().toString()
        val bytes = "# Offline note\n".toByteArray()
        val payload = File(context.cacheDir, "payload-$revisionId.md").apply { writeBytes(bytes) }
        payloads += payload
        return SyncOutbox(
            operationId = UUID.randomUUID().toString(),
            profileId = profile.profileId,
            repositoryId = repository.repositoryId,
            operationType = "CREATE_NOTE",
            repositoryPath = "2026-07-22/notes/$revisionId.md",
            contentSha256 = sha256(bytes),
            payloadCachePath = payload.absolutePath,
            dependencyIdsJson = "[]",
            profileDate = "2026-07-22",
            batchId = batchId,
            status = status
        ).also { database.syncStateDao().insertOutbox(it) }
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
