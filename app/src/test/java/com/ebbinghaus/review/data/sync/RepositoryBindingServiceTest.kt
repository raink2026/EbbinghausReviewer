package com.ebbinghaus.review.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ebbinghaus.review.data.AppDatabase
import java.util.UUID
import kotlinx.coroutines.flow.first
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

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class RepositoryBindingServiceTest {
    private lateinit var database: AppDatabase
    private lateinit var service: RepositoryBindingService

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        service = RepositoryBindingService(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun repositoryIdentityAlreadyBoundToAnotherProfileSelectsExistingProfile() = runBlocking {
        val existingProfile = profile()
        val repositoryId = UUID.randomUUID().toString()
        database.profileDao().insert(existingProfile)
        database.remoteRepositoryDao().insert(repository(repositoryId, existingProfile.profileId))
        val incomingIdentity = identity(repositoryId, UUID.randomUUID().toString())

        val result = service.bindInitializedRepository(
            incomingIdentity,
            RepositoryLocation("owner", "repository"),
            "new-credential",
            "Imported"
        )

        assertEquals(
            RepositoryBindingResult.SelectExistingProfile(existingProfile.profileId),
            result
        )
        assertEquals(1, database.profileDao().observeProfiles().first().size)
    }

    @Test
    fun existingProfileCannotBeRetargetedToDifferentRepository() = runBlocking {
        val profile = profile()
        database.profileDao().insert(profile)
        database.remoteRepositoryDao().insert(repository(UUID.randomUUID().toString(), profile.profileId))

        val result = service.bindInitializedRepository(
            identity(UUID.randomUUID().toString(), profile.profileId),
            RepositoryLocation("owner", "other"),
            "credential",
            profile.displayName
        )

        assertTrue(result is RepositoryBindingResult.Rejected)
        assertEquals("repository", database.remoteRepositoryDao().getForProfile(profile.profileId)?.name)
    }

    @Test
    fun unbindingPreservesPendingOutboxAndLocalNotes() = runBlocking {
        val profile = profile()
        val repository = repository(UUID.randomUUID().toString(), profile.profileId)
        database.profileDao().insert(profile)
        database.remoteRepositoryDao().insert(repository)
        database.noteProjectionDao().applyProjection(
            profile.profileId,
            Note("note-a", profile.profileId, "Local note"),
            emptyList(),
            emptyList()
        )
        database.syncStateDao().insertOutbox(
            SyncOutbox(
                operationId = UUID.randomUUID().toString(),
                profileId = profile.profileId,
                repositoryId = repository.repositoryId,
                operationType = "CREATE_NOTE",
                repositoryPath = "2026-07-22/notes/${UUID.randomUUID()}.md",
                contentSha256 = "a".repeat(64),
                payloadCachePath = "payload.md",
                dependencyIdsJson = "[]",
                profileDate = "2026-07-22"
            )
        )

        assertTrue(service.unbind(profile.profileId))

        assertFalse(database.remoteRepositoryDao().getForProfile(profile.profileId)!!.isBound)
        assertEquals(1, database.noteProjectionDao().getNotes(profile.profileId).size)
        assertEquals(1, database.syncStateDao().getAllOutbox(profile.profileId).size)
    }

    private fun profile() = Profile(
        profileId = UUID.randomUUID().toString(),
        displayName = "Profile",
        timezone = "Asia/Shanghai",
        algorithmId = "ebbinghaus-8-stage",
        algorithmVersion = 1,
        algorithmParametersJson = "{\"interval_days\":[1,2,4,7,15,30,60,120]}"
    )

    private fun identity(repositoryId: String, profileId: String) = RepositoryProfileIdentity(
        repositoryId,
        profileId,
        "Asia/Shanghai",
        "ebbinghaus-8-stage",
        1,
        "{\"interval_days\":[1,2,4,7,15,30,60,120]}"
    )

    private fun repository(repositoryId: String, profileId: String) = RemoteRepository(
        repositoryId,
        profileId,
        "owner",
        "repository",
        "main",
        "credential"
    )
}
