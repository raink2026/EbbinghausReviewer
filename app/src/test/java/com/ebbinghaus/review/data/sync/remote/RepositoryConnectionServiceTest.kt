package com.ebbinghaus.review.data.sync.remote

import com.ebbinghaus.review.data.sync.Profile
import com.ebbinghaus.review.data.sync.RepositoryLocation
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoryConnectionServiceTest {
    private val transport = FakeGiteeTransport()
    private val controlFiles = mapOf(
        "repository-control/ebbinghaus/note.schema.json" to "note-schema\n".toByteArray(),
        "repository-control/ebbinghaus/event.schema.json" to "event-schema\n".toByteArray(),
        "repository-control/ebbinghaus/profile.schema.json" to "profile-schema\n".toByteArray(),
        "repository-control/scripts/review-sync.sh" to "#!/usr/bin/env bash\n".toByteArray()
    )
    private val service = RepositoryConnectionService(transport, controlFiles::getValue)

    @Test
    fun missingBranchIsReportedAsEmptyRepository() = runBlocking {
        transport.branchFailure = GiteeApiException(404, "branch not found")

        val result = service.inspect(RepositoryLocation("owner", "repository"), "credential")

        assertTrue(result is RepositoryInspection.Empty)
    }

    @Test
    fun initializedRepositoryImportsCanonicalIdentity() = runBlocking {
        val repositoryId = UUID.randomUUID().toString()
        val profileId = UUID.randomUUID().toString()
        val profileJson = """
            {
              "schema": "ebbinghaus-profile/v1",
              "repository_id": "$repositoryId",
              "profile_id": "$profileId",
              "timezone": "Asia/Shanghai",
              "review_algorithm": {
                "id": "ebbinghaus-8-stage",
                "version": 1,
                "parameters": {"interval_days": [1,2,4,7,15,30,60,120]}
              }
            }
        """.trimIndent().toByteArray()
        transport.contents[".ebbinghaus/profile.json" to "main"] = GiteeRemoteFile(
            ".ebbinghaus/profile.json",
            "profile-blob",
            profileJson,
            "main-head"
        )
        transport.branchValue = GiteeBranch("main", "main-head")

        val result = service.inspect(RepositoryLocation("owner", "repository"), "credential")

        val initialized = result as RepositoryInspection.Initialized
        assertEquals(repositoryId, initialized.identity.repositoryId)
        assertEquals(profileId, initialized.identity.profileId)
        assertEquals("Asia/Shanghai", initialized.identity.timezone)
    }

    @Test
    fun initializationPublishesEveryCanonicalControlFileAndDesktopScript() = runBlocking {
        val profile = Profile(
            profileId = UUID.randomUUID().toString(),
            displayName = "Profile",
            timezone = "Asia/Shanghai",
            algorithmId = "ebbinghaus-8-stage",
            algorithmVersion = 1,
            algorithmParametersJson = "{\"interval_days\":[1,2,4,7,15,30,60,120]}"
        )

        service.initialize(
            RepositoryLocation("owner", "repository", "main"),
            "credential-alias",
            profile,
            UUID.randomUUID().toString()
        )

        val commit = transport.createdCommits.single()
        assertEquals(
            setOf(
                ".ebbinghaus/profile.json",
                ".ebbinghaus/note.schema.json",
                ".ebbinghaus/event.schema.json",
                ".ebbinghaus/profile.schema.json",
                "README.md",
                "scripts/review-sync.sh"
            ),
            commit.actions.mapTo(mutableSetOf()) { it.path }
        )
        assertEquals("credential-alias", commit.credentialAlias)
        assertTrue(commit.actions.all { !it.binary })
    }
}
