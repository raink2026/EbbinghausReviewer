package com.ebbinghaus.review.data.sync.remote

import com.ebbinghaus.review.data.sync.Profile
import com.ebbinghaus.review.data.sync.RepositoryLocation
import com.ebbinghaus.review.data.sync.RepositoryProfileIdentity
import com.ebbinghaus.review.data.sync.protocol.ALGORITHM_ID
import com.ebbinghaus.review.data.sync.protocol.ALGORITHM_VERSION
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.ZoneId
import java.util.UUID

sealed interface RepositoryInspection {
    data class Initialized(
        val identity: RepositoryProfileIdentity,
        val repository: GiteeRepositoryInfo,
        val branch: GiteeBranch
    ) : RepositoryInspection

    data class Empty(
        val repository: GiteeRepositoryInfo,
        val branchName: String
    ) : RepositoryInspection

    data class Failed(val message: String, val statusCode: Int? = null) : RepositoryInspection
}

class RepositoryConnectionService(
    private val transport: GiteeTransport,
    private val controlFile: (String) -> ByteArray
) {
    suspend fun inspect(
        location: RepositoryLocation,
        credentialAlias: String
    ): RepositoryInspection {
        return try {
            val repository = transport.getRepository(location.owner, location.name, credentialAlias)
            if (!repository.privateRepository) {
                return RepositoryInspection.Failed("The profile repository must be private")
            }
            if (!repository.canPush) {
                return RepositoryInspection.Failed("The token does not have repository write access")
            }
            val branch = try {
                transport.getBranch(
                    location.owner,
                    location.name,
                    location.branch,
                    credentialAlias
                )
            } catch (error: GiteeApiException) {
                if (error.statusCode == 404) {
                    return RepositoryInspection.Empty(repository, location.branch)
                }
                throw error
            }
            val profileFile = try {
                transport.getContent(
                    location.owner,
                    location.name,
                    ".ebbinghaus/profile.json",
                    location.branch,
                    credentialAlias
                )
            } catch (error: GiteeApiException) {
                if (error.statusCode == 404) {
                    return RepositoryInspection.Empty(repository, location.branch)
                }
                throw error
            }
            RepositoryInspection.Initialized(
                identity = parseProfile(profileFile.bytes),
                repository = repository,
                branch = branch
            )
        } catch (error: GiteeApiException) {
            RepositoryInspection.Failed(
                error.responseBody.ifBlank { error.message.orEmpty() },
                error.statusCode
            )
        } catch (error: Exception) {
            RepositoryInspection.Failed(error.message ?: "Unable to inspect repository")
        }
    }

    suspend fun initialize(
        location: RepositoryLocation,
        credentialAlias: String,
        profile: Profile,
        repositoryId: String,
        repository: GiteeRepositoryInfo
    ): GiteeCreatedCommit {
        ensureTargetBranch(location, credentialAlias, repository)
        val gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
        val algorithmParameters = JsonParser.parseString(profile.algorithmParametersJson).asJsonObject
        val profileJson = JsonObject().apply {
            addProperty("schema", "ebbinghaus-profile/v1")
            addProperty("repository_id", repositoryId)
            addProperty("profile_id", profile.profileId)
            addProperty("timezone", profile.timezone)
            add("review_algorithm", JsonObject().apply {
                addProperty("id", profile.algorithmId)
                addProperty("version", profile.algorithmVersion)
                add("parameters", algorithmParameters)
            })
        }
        val actions = listOf(
            GiteeCommitAction(
                ".ebbinghaus/profile.json",
                (gson.toJson(profileJson) + "\n").toByteArray(),
                binary = false
            ),
            GiteeCommitAction(
                ".ebbinghaus/note.schema.json",
                controlFile("repository-control/ebbinghaus/note.schema.json"),
                binary = false
            ),
            GiteeCommitAction(
                ".ebbinghaus/event.schema.json",
                controlFile("repository-control/ebbinghaus/event.schema.json"),
                binary = false
            ),
            GiteeCommitAction(
                ".ebbinghaus/profile.schema.json",
                controlFile("repository-control/ebbinghaus/profile.schema.json"),
                binary = false
            ),
            GiteeCommitAction(
                "README.md",
                REPOSITORY_README.toByteArray(),
                binary = false
            ),
            GiteeCommitAction(
                "scripts/review-sync.sh",
                controlFile("repository-control/scripts/review-sync.sh"),
                binary = false
            )
        )
        return transport.createCommit(
            location.owner,
            location.name,
            location.branch,
            "chore: initialize Ebbinghaus repository format",
            actions,
            credentialAlias
        )
    }

    private suspend fun ensureTargetBranch(
        location: RepositoryLocation,
        credentialAlias: String,
        repository: GiteeRepositoryInfo
    ) {
        try {
            transport.getBranch(
                location.owner,
                location.name,
                location.branch,
                credentialAlias
            )
            return
        } catch (error: GiteeApiException) {
            if (error.statusCode != 404) throw error
        }
        val baseBranch = repository.defaultBranch ?: return
        transport.createBranch(
            owner = location.owner,
            repository = location.name,
            refs = baseBranch,
            branchName = location.branch,
            credentialAlias = credentialAlias
        )
    }

    internal fun parseProfile(bytes: ByteArray): RepositoryProfileIdentity {
        val json = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        require(json.keySet() == setOf(
            "schema",
            "repository_id",
            "profile_id",
            "timezone",
            "review_algorithm"
        )) { "Repository profile has unsupported fields" }
        require(json.get("schema").asString == "ebbinghaus-profile/v1")
        val repositoryId = canonicalUuid(json.get("repository_id").asString)
        val profileId = canonicalUuid(json.get("profile_id").asString)
        val timezone = ZoneId.of(json.get("timezone").asString).id
        val algorithm = json.getAsJsonObject("review_algorithm")
        require(algorithm.keySet() == setOf("id", "version", "parameters"))
        require(algorithm.get("id").asString == ALGORITHM_ID)
        require(algorithm.get("version").asInt == ALGORITHM_VERSION)
        val parameters = algorithm.getAsJsonObject("parameters")
        require(parameters.keySet() == setOf("interval_days"))
        val intervals = parameters.getAsJsonArray("interval_days").map { it.asInt }
        require(intervals.size == 8 && intervals.all { it > 0 })
        return RepositoryProfileIdentity(
            repositoryId,
            profileId,
            timezone,
            ALGORITHM_ID,
            ALGORITHM_VERSION,
            parameters.toString()
        )
    }

    private fun canonicalUuid(value: String): String = UUID.fromString(value).toString().also {
        require(it == value) { "Repository profile UUID is not canonical" }
    }
}

private const val REPOSITORY_README =
    "# Ebbinghaus Review Repository\n\nUse scripts/review-sync.sh for desktop publishing.\n"
