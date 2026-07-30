package com.ebbinghaus.review.data.sync.remote

import androidx.room.withTransaction
import com.ebbinghaus.review.data.AppDatabase
import com.ebbinghaus.review.data.sync.OutboxPlanner
import com.ebbinghaus.review.data.sync.Profile
import com.ebbinghaus.review.data.sync.RemoteRepository
import com.ebbinghaus.review.data.sync.SyncCheckpoint
import com.ebbinghaus.review.data.sync.SyncOutbox
import com.ebbinghaus.review.data.sync.protocol.sha256
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.min

enum class SyncFailureKind { TRANSIENT, PERSISTENT }

class SyncPipelineException(
    val kind: SyncFailureKind,
    message: String,
    cause: Throwable? = null,
    val retryAtMillis: Long? = null
) : Exception(message, cause)

data class PushResult(val commitsCreated: Int, val operationsAcknowledged: Int)

class GiteePushService(
    private val database: AppDatabase,
    private val transport: GiteeTransport,
    private val pullService: GiteePullService,
    private val planner: OutboxPlanner = OutboxPlanner()
) {
    suspend fun push(profileId: String): PushResult = try {
        pushInternal(profileId)
    } catch (error: SyncPipelineException) {
        throw error
    } catch (error: Exception) {
        throw classify(error)
    }

    private suspend fun pushInternal(profileId: String): PushResult {
        val profile = database.profileDao().getProfile(profileId) ?: error("Unknown profile")
        val repository = database.remoteRepositoryDao().getForProfile(profileId)
            ?.takeIf { it.isBound } ?: error("Repository is not bound")
        val now = System.currentTimeMillis()
        database.syncStateDao().getDeferredRetryAt(profileId, now)?.let { retryAt ->
            throw SyncPipelineException(
                SyncFailureKind.TRANSIENT,
                "Synchronization retry is deferred until $retryAt",
                retryAtMillis = retryAt
            )
        }
        pullService.pull(profileId)
        var commits = 0
        var acknowledged = 0

        recoverAmbiguousBatches(profile, repository).also { acknowledged += it }
        repeat(MAX_BATCHES_PER_RUN) {
            val pending = database.syncStateDao().getOutbox(
                profileId,
                listOf("PENDING", "RETRY"),
                System.currentTimeMillis()
            )
            val plan = planner.nextBatch(pending) ?: return PushResult(commits, acknowledged)
            val batchId = UUID.randomUUID().toString()
            val assigned = database.syncStateDao().assignBatch(
                profileId,
                repository.repositoryId,
                plan.operations.map { it.operationId },
                batchId,
                System.currentTimeMillis()
            )
            check(assigned == plan.operations.size) { "Outbox batch changed while being assigned" }
            try {
                val created = publishBatch(profile, repository, batchId, plan.operations, allowRetry = true)
                if (created) commits += 1
                acknowledged += plan.operations.size
            } catch (error: Exception) {
                throw handleFailure(profileId, batchId, error)
            }
        }
        return PushResult(commits, acknowledged)
    }

    private suspend fun recoverAmbiguousBatches(
        profile: Profile,
        repository: RemoteRepository
    ): Int {
        val inFlight = database.syncStateDao().getOutbox(
            profile.profileId,
            listOf("IN_FLIGHT", "RETRY"),
            System.currentTimeMillis()
        )
            .filter { it.batchId != null }
            .groupBy { checkNotNull(it.batchId) }
        var acknowledged = 0
        for ((batchId, operations) in inFlight) {
            val matchingCommits = findBatchCommits(repository, batchId)
            if (matchingCommits.isNotEmpty()) {
                matchingCommits.forEach { commitSha ->
                    verifyBatchCommit(repository, commitSha, operations)
                }
                val commitSha = matchingCommits.first()
                acknowledge(profile, repository, batchId, commitSha)
                acknowledged += operations.size
            } else {
                try {
                    val created = publishBatch(profile, repository, batchId, operations, allowRetry = true)
                    if (created || operations.isNotEmpty()) acknowledged += operations.size
                } catch (error: Exception) {
                    throw handleFailure(profile.profileId, batchId, error)
                }
            }
        }
        return acknowledged
    }

    private suspend fun publishBatch(
        profile: Profile,
        repository: RemoteRepository,
        batchId: String,
        operations: List<SyncOutbox>,
        allowRetry: Boolean
    ): Boolean {
        val missing = mutableListOf<SyncOutbox>()
        val branch = transport.getBranch(
            repository.owner,
            repository.name,
            repository.branch,
            repository.credentialAlias
        )
        operations.forEach { operation ->
            readPayload(operation)
            val remote = try {
                transport.getContent(
                    repository.owner,
                    repository.name,
                    operation.repositoryPath,
                    branch.headSha,
                    repository.credentialAlias
                )
            } catch (error: GiteeApiException) {
                if (error.statusCode == 404) null else throw error
            }
            if (remote == null) {
                missing += operation
            } else if (sha256(remote.bytes) != operation.contentSha256) {
                throw RemoteValidationException(
                    listOf("Remote path collision: ${operation.repositoryPath}")
                )
            }
        }
        if (missing.isEmpty()) {
            acknowledge(profile, repository, batchId, branch.headSha)
            return false
        }
        val message = buildString {
            append("sync: publish ${missing.size} immutable files\n\n")
            append("Ebbinghaus-Batch-Id: $batchId")
        }
        val created = try {
            transport.createCommit(
                repository.owner,
                repository.name,
                repository.branch,
                message,
                missing.map { operation ->
                    GiteeCommitAction(
                        operation.repositoryPath,
                        readPayload(operation),
                        binary = operation.operationType == "CREATE_ASSET"
                    )
                },
                repository.credentialAlias
            )
        } catch (error: GiteeApiException) {
            if (allowRetry && error.statusCode == 409) {
                pullService.pull(profile.profileId)
                ensureAppendOnlyOperations(profile.profileId, repository.repositoryId, operations)
                return publishBatch(profile, repository, batchId, operations, allowRetry = false)
            }
            throw error
        }
        verifyBatchCommit(repository, created.sha, operations)
        acknowledge(profile, repository, batchId, created.sha)
        return true
    }

    private suspend fun findBatchCommits(
        repository: RemoteRepository,
        batchId: String
    ): List<String> {
        val trailer = "Ebbinghaus-Batch-Id: $batchId"
        val matches = mutableListOf<String>()
        val seenCommitShas = mutableSetOf<String>()
        var page = 1
        while (true) {
            val commits = transport.listCommits(
                repository.owner,
                repository.name,
                repository.branch,
                page,
                SEARCH_PAGE_SIZE,
                repository.credentialAlias
            )
            if (commits.isEmpty()) return matches.distinct()
            var pageAdvanced = false
            commits.forEach { commit ->
                if (seenCommitShas.add(commit.sha)) {
                    pageAdvanced = true
                    if (commit.message.lineSequence().any { it.trim() == trailer }) {
                        matches += commit.sha
                    }
                }
            }
            if (commits.size < SEARCH_PAGE_SIZE) return matches.distinct()
            if (!pageAdvanced) {
                throw RemoteValidationException(
                    listOf("Gitee commit pagination did not advance during batch recovery")
                )
            }
            page = Math.incrementExact(page)
        }
    }

    private suspend fun verifyBatchCommit(
        repository: RemoteRepository,
        commitSha: String,
        operations: List<SyncOutbox>
    ) {
        val expectedPaths = operations.mapTo(mutableSetOf()) { it.repositoryPath }
        val commit = transport.getCommit(
            repository.owner,
            repository.name,
            commitSha,
            repository.credentialAlias
        )
        val commitPaths = commit.getAsJsonArray("files")?.mapNotNull { element ->
            val file = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            listOf("filename", "new_path", "path").firstNotNullOfOrNull { key ->
                file.get(key)?.takeUnless { it.isJsonNull }?.asString
            }
        }.orEmpty().toSet()
        if (commitPaths != expectedPaths) {
            throw RemoteValidationException(
                listOf(
                    "Batch commit paths differ: expected ${expectedPaths.sorted().joinToString()}, " +
                        "found ${commitPaths.sorted().joinToString()}"
                )
            )
        }
        verifyRemoteFiles(repository, commitSha, operations)
    }

    private suspend fun verifyRemoteFiles(
        repository: RemoteRepository,
        commitSha: String,
        operations: List<SyncOutbox>
    ) {
        operations.forEach { operation ->
            val remote = transport.getContent(
                repository.owner,
                repository.name,
                operation.repositoryPath,
                commitSha,
                repository.credentialAlias
            )
            if (remote.path != operation.repositoryPath ||
                sha256(remote.bytes) != operation.contentSha256
            ) {
                throw RemoteValidationException(
                    listOf("Remote commit verification failed: ${operation.repositoryPath}")
                )
            }
        }
    }

    private fun ensureAppendOnlyOperations(
        profileId: String,
        repositoryId: String,
        operations: List<SyncOutbox>
    ) {
        val allowedTypes = setOf("CREATE_ASSET", "CREATE_EVENT", "CREATE_NOTE")
        require(operations.all {
            it.profileId == profileId &&
                it.repositoryId == repositoryId &&
                it.operationType in allowedTypes &&
                IMMUTABLE_PATH.matches(it.repositoryPath)
        }) { "Remote advancement requires manual recovery for non-append-only operations" }
    }

    private suspend fun acknowledge(
        profile: Profile,
        repository: RemoteRepository,
        batchId: String,
        commitSha: String
    ) {
        database.withTransaction {
            database.syncStateDao().acknowledgeBatch(profile.profileId, batchId)
            val checkpoint = database.syncStateDao().getCheckpoint(profile.profileId)
            database.syncStateDao().saveCheckpoint(
                SyncCheckpoint(
                    checkpointId = checkpoint?.checkpointId ?: UUID.randomUUID().toString(),
                    profileId = profile.profileId,
                    repositoryId = repository.repositoryId,
                    remoteCommitSha = commitSha,
                    lastPullAt = checkpoint?.lastPullAt,
                    lastPushAt = System.currentTimeMillis()
                )
            )
        }
    }

    private fun readPayload(operation: SyncOutbox): ByteArray {
        val file = File(operation.payloadCachePath)
        require(file.isFile) { "Outbox payload is missing: ${operation.repositoryPath}" }
        return file.readBytes().also { bytes ->
            require(sha256(bytes) == operation.contentSha256) {
                "Outbox payload hash mismatch: ${operation.repositoryPath}"
            }
        }
    }

    private suspend fun handleFailure(
        profileId: String,
        batchId: String,
        error: Exception
    ): SyncPipelineException {
        val classified = classify(error)
        if (classified.kind == SyncFailureKind.TRANSIENT) {
            val operations = database.syncStateDao().getBatch(profileId, batchId)
            val attempts = operations.maxOfOrNull { it.attemptCount } ?: 0
            val now = System.currentTimeMillis()
            val localRetryAt = now + min(
                MAX_BACKOFF_MILLIS,
                BASE_BACKOFF_MILLIS shl min(attempts, 8)
            )
            val serverRetryAt = (error as? GiteeApiException)?.retryAfterEpochMillis
            val retryAt = maxOf(localRetryAt, serverRetryAt ?: localRetryAt)
            database.syncStateDao().markBatchForRetry(
                profileId,
                batchId,
                retryAt,
                classified.message.orEmpty().take(1000),
                now
            )
            return SyncPipelineException(
                classified.kind,
                classified.message.orEmpty(),
                error,
                retryAtMillis = retryAt
            )
        } else {
            database.syncStateDao().pauseBatch(
                profileId,
                batchId,
                classified.message.orEmpty().take(1000),
                System.currentTimeMillis()
            )
            return classified
        }
    }

    private fun classify(error: Exception): SyncPipelineException {
        if (error is SyncPipelineException) return error
        val kind = when (error) {
            is GiteeApiException -> when (error.statusCode) {
                408, 409, 429, in 500..599 -> SyncFailureKind.TRANSIENT
                else -> SyncFailureKind.PERSISTENT
            }
            is RemoteValidationException -> SyncFailureKind.PERSISTENT
            is IOException -> SyncFailureKind.TRANSIENT
            else -> SyncFailureKind.PERSISTENT
        }
        return SyncPipelineException(
            kind,
            error.message ?: "Synchronization failed",
            error,
            retryAtMillis = (error as? GiteeApiException)?.retryAfterEpochMillis
        )
    }

    private companion object {
        const val MAX_BATCHES_PER_RUN = 20
        const val SEARCH_PAGE_SIZE = 100
        const val BASE_BACKOFF_MILLIS = 30_000L
        const val MAX_BACKOFF_MILLIS = 6L * 60L * 60L * 1000L
        val IMMUTABLE_PATH = Regex(
            "\\d{4}-\\d{2}-\\d{2}/(?:notes/[0-9a-f-]{36}\\.md|" +
                "events/[0-9a-f-]{36}\\.json|assets/[0-9a-f]{64}\\.[a-z0-9]+)"
        )
    }
}
