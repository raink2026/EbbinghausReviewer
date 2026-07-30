package com.ebbinghaus.review.data.sync.remote

import android.content.Context
import androidx.room.withTransaction
import com.ebbinghaus.review.data.AppDatabase
import com.ebbinghaus.review.data.sync.AssetDao
import com.ebbinghaus.review.data.sync.ImmutableAssetCache
import com.ebbinghaus.review.data.sync.ImmutableRevisionStore
import com.ebbinghaus.review.data.sync.Note
import com.ebbinghaus.review.data.sync.NoteRevision
import com.ebbinghaus.review.data.sync.Profile
import com.ebbinghaus.review.data.sync.ProfileTimeService
import com.ebbinghaus.review.data.sync.RemoteRepository
import com.ebbinghaus.review.data.sync.RepositoryDateCategory
import com.ebbinghaus.review.data.sync.ReviewEvent
import com.ebbinghaus.review.data.sync.SyncCheckpoint
import com.ebbinghaus.review.data.sync.projection.NoteDomainState
import com.ebbinghaus.review.data.sync.projection.NoteProjectionEngine
import com.ebbinghaus.review.data.sync.projection.RevisionGraphProjector
import com.ebbinghaus.review.data.sync.projection.ReviewStreamState
import com.ebbinghaus.review.data.sync.protocol.EventDocumentCodec
import com.ebbinghaus.review.data.sync.protocol.MarkdownContent
import com.ebbinghaus.review.data.sync.protocol.NoteDocumentCodec
import com.ebbinghaus.review.data.sync.protocol.ParsedMarkdownRevision
import com.ebbinghaus.review.data.sync.protocol.ParsedRepositoryEvent
import com.ebbinghaus.review.data.sync.protocol.sha256
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.File
import java.time.OffsetDateTime
import java.util.UUID

data class PullResult(
    val appliedCommits: Int,
    val remoteHeadSha: String,
    val importedFiles: Int
)

data class FullRebuildPreview(
    val remoteHeadSha: String,
    val noteCount: Int,
    val assetCount: Int,
    val eventCount: Int,
    val conflictCount: Int,
    val issues: List<String>
) {
    val canApply: Boolean get() = issues.isEmpty()
}

class CheckpointUnreachableException(
    val checkpointSha: String,
    val remoteHeadSha: String
) : IllegalStateException("Checkpoint $checkpointSha is not reachable from $remoteHeadSha")

class RemoteValidationException(val validationIssues: List<String>) :
    IllegalArgumentException(validationIssues.joinToString("; "))

class GiteePullService(
    private val context: Context,
    private val database: AppDatabase,
    private val transport: GiteeTransport,
    private val quarantine: RemoteQuarantine = RemoteQuarantine(context),
    private val assetCache: ImmutableAssetCache = ImmutableAssetCache(context, database.assetDao()),
    private val revisionStore: ImmutableRevisionStore = ImmutableRevisionStore(context)
) {
    private val gson = Gson()

    suspend fun pull(profileId: String): PullResult {
        val profile = database.profileDao().getProfile(profileId) ?: error("Unknown profile")
        val repository = database.remoteRepositoryDao().getForProfile(profileId)
            ?.takeIf { it.isBound } ?: error("Profile repository is not bound")
        val checkpoint = database.syncStateDao().getCheckpoint(profileId)
        database.remoteRepositoryDao().updateSyncHealth(
            profileId,
            repository.repositoryId,
            "PULLING",
            null,
            System.currentTimeMillis()
        )
        return try {
            val branch = transport.getBranch(
                repository.owner,
                repository.name,
                repository.branch,
                repository.credentialAlias
            )
            validateRepositoryControl(profile, repository, branch.headSha)
            val commits = discoverCommits(repository, branch.headSha, checkpoint?.remoteCommitSha)
            var importedFiles = 0
            commits.forEach { commit ->
                val quarantined = downloadCommit(repository, profile, commit.sha)
                try {
                    val batch = validateCommit(profile, repository, commit.sha, quarantined)
                    applyCommit(profile, repository, checkpoint, commit.sha, batch)
                    importedFiles += quarantined.files.size
                } finally {
                    quarantine.discard(quarantined)
                }
            }
            database.remoteRepositoryDao().updateSyncHealth(
                profileId,
                repository.repositoryId,
                "IDLE",
                null,
                System.currentTimeMillis()
            )
            PullResult(commits.size, branch.headSha, importedFiles)
        } catch (error: Exception) {
            database.remoteRepositoryDao().updateSyncHealth(
                profileId,
                repository.repositoryId,
                "ERROR",
                error.message?.take(1000),
                System.currentTimeMillis()
            )
            throw error
        }
    }

    suspend fun previewFullRebuild(profileId: String): FullRebuildPreview {
        val profile = database.profileDao().getProfile(profileId) ?: error("Unknown profile")
        val repository = database.remoteRepositoryDao().getForProfile(profileId) ?: error("No repository")
        val branch = transport.getBranch(
            repository.owner,
            repository.name,
            repository.branch,
            repository.credentialAlias
        )
        validateRepositoryControl(profile, repository, branch.headSha)
        val entries = transport.getTree(
            repository.owner,
            repository.name,
            branch.headSha,
            recursive = true,
            repository.credentialAlias
        ).filter { it.type == "blob" && DATA_PATH.matches(it.path) }
        val files = entries.map { entry ->
            val blob = transport.getBlob(
                repository.owner,
                repository.name,
                entry.sha,
                repository.credentialAlias
            )
            GiteeRemoteFile(entry.path, entry.sha, blob.bytes, branch.headSha)
        }
        val quarantined = quarantine.store(profileId, branch.headSha, files)
        return try {
            val batch = validateCommit(profile, repository, branch.headSha, quarantined)
            val projections = projectBatch(profile, batch)
            FullRebuildPreview(
                remoteHeadSha = branch.headSha,
                noteCount = batch.revisions.map { it.document.frontMatter.noteId }.distinct().size,
                assetCount = batch.assets.size,
                eventCount = batch.events.size,
                conflictCount = projections.count { it.value.state != NoteDomainState.ACTIVE &&
                    it.value.state != NoteDomainState.DELETED },
                issues = projections.values.flatMap { it.issues }
            )
        } catch (error: RemoteValidationException) {
            FullRebuildPreview(branch.headSha, 0, 0, 0, 0, error.validationIssues)
        } finally {
            quarantine.discard(quarantined)
        }
    }

    suspend fun applyFullRebuild(profileId: String, expectedHeadSha: String, confirmed: Boolean) {
        require(confirmed) { "Full rebuild requires explicit user confirmation" }
        val preview = previewFullRebuild(profileId)
        require(preview.remoteHeadSha == expectedHeadSha && preview.canApply) {
            "Remote repository changed or rebuild validation failed"
        }
        val profile = database.profileDao().getProfile(profileId) ?: error("Unknown profile")
        val repository = database.remoteRepositoryDao().getForProfile(profileId) ?: error("No repository")
        requireCurrentHead(repository, expectedHeadSha)
        val entries = transport.getTree(
            repository.owner,
            repository.name,
            expectedHeadSha,
            true,
            repository.credentialAlias
        ).filter { it.type == "blob" && DATA_PATH.matches(it.path) }
        val files = entries.map { entry ->
            val blob = transport.getBlob(repository.owner, repository.name, entry.sha, repository.credentialAlias)
            GiteeRemoteFile(entry.path, entry.sha, blob.bytes, expectedHeadSha)
        }
        val quarantined = quarantine.store(profileId, expectedHeadSha, files)
        try {
            val batch = validateCommit(profile, repository, expectedHeadSha, quarantined)
            requireCurrentHead(repository, expectedHeadSha)
            applyCommit(
                profile = profile,
                repository = repository,
                originalCheckpoint = null,
                commitSha = expectedHeadSha,
                batch = batch,
                replaceProjection = true
            )
        } finally {
            quarantine.discard(quarantined)
        }
    }

    private suspend fun requireCurrentHead(repository: RemoteRepository, expectedHeadSha: String) {
        val currentHead = transport.getBranch(
            repository.owner,
            repository.name,
            repository.branch,
            repository.credentialAlias
        ).headSha
        require(currentHead == expectedHeadSha) {
            "Remote repository changed after rebuild preview"
        }
    }

    private suspend fun discoverCommits(
        repository: RemoteRepository,
        remoteHeadSha: String,
        checkpointSha: String?
    ): List<GiteeCommitSummary> {
        if (checkpointSha == remoteHeadSha) return emptyList()
        val newestFirst = mutableListOf<GiteeCommitSummary>()
        var foundCheckpoint = false
        val seenCommitShas = mutableSetOf<String>()
        var page = 1
        while (true) {
            val commits = transport.listCommits(
                repository.owner,
                repository.name,
                repository.branch,
                page,
                COMMITS_PER_PAGE,
                repository.credentialAlias
            )
            if (commits.isEmpty()) {
                if (checkpointSha == null) foundCheckpoint = true
                break
            }
            var pageAdvanced = false
            for (commit in commits) {
                if (checkpointSha != null && commit.sha == checkpointSha) {
                    foundCheckpoint = true
                    break
                }
                if (seenCommitShas.add(commit.sha)) {
                    newestFirst += commit
                    pageAdvanced = true
                }
            }
            if (foundCheckpoint || commits.size < COMMITS_PER_PAGE) {
                if (checkpointSha == null) foundCheckpoint = true
                break
            }
            if (!pageAdvanced) {
                throw IllegalStateException("Gitee commit pagination did not advance")
            }
            page = Math.incrementExact(page)
        }
        if (!foundCheckpoint) {
            if (checkpointSha == null) {
                throw IllegalStateException("Unable to traverse repository history")
            }
            throw CheckpointUnreachableException(checkpointSha, remoteHeadSha)
        }
        return newestFirst.asReversed()
    }

    private suspend fun validateRepositoryControl(
        profile: Profile,
        repository: RemoteRepository,
        ref: String
    ) {
        val issues = mutableListOf<String>()
        val profileBytes = transport.getContent(
            repository.owner,
            repository.name,
            ".ebbinghaus/profile.json",
            ref,
            repository.credentialAlias
        ).bytes
        val identity = runCatching {
            RepositoryConnectionService(transport) { assetPath ->
                context.assets.open(assetPath).use { it.readBytes() }
            }.parseProfile(profileBytes)
        }.getOrElse { error ->
            throw RemoteValidationException(
                listOf(".ebbinghaus/profile.json: ${error.message ?: "invalid profile identity"}")
            )
        }
        if (identity.repositoryId != repository.repositoryId) {
            issues += ".ebbinghaus/profile.json: repository identity does not match binding"
        }
        if (identity.profileId != profile.profileId || identity.timezone != profile.timezone) {
            issues += ".ebbinghaus/profile.json: profile identity or timezone does not match binding"
        }
        if (identity.algorithmId != profile.algorithmId ||
            identity.algorithmVersion != profile.algorithmVersion ||
            JsonParser.parseString(identity.algorithmParametersJson) !=
            JsonParser.parseString(profile.algorithmParametersJson)
        ) {
            issues += ".ebbinghaus/profile.json: review algorithm does not match binding"
        }
        CONTROL_SCHEMAS.forEach { (remotePath, assetPath) ->
            val remote = transport.getContent(
                repository.owner,
                repository.name,
                remotePath,
                ref,
                repository.credentialAlias
            ).bytes
            val expected = context.assets.open(assetPath).use { it.readBytes() }
            val matches = runCatching {
                JsonParser.parseString(remote.toString(Charsets.UTF_8)) ==
                    JsonParser.parseString(expected.toString(Charsets.UTF_8))
            }.getOrDefault(false)
            if (!matches) issues += "$remotePath: control schema does not match supported v1"
        }
        if (issues.isNotEmpty()) throw RemoteValidationException(issues)
    }

    private suspend fun downloadCommit(
        repository: RemoteRepository,
        profile: Profile,
        commitSha: String
    ): QuarantinedCommit {
        val detail = transport.getCommit(
            repository.owner,
            repository.name,
            commitSha,
            repository.credentialAlias
        )
        val changedFiles = detail.getAsJsonArray("files") ?: error("Commit response omitted files")
        val remoteFiles = changedFiles.mapNotNull { element ->
            val file = element.asJsonObject
            val path = file.get("filename")?.asString ?: file.get("path")?.asString
                ?: error("Commit file omitted path")
            val status = file.get("status")?.asString ?: "added"
            if (!DATA_PATH.matches(path)) return@mapNotNull null
            if (status !in setOf("added", "new")) {
                throw RemoteValidationException(listOf("Committed data file is not append-only: $path"))
            }
            transport.getContent(
                repository.owner,
                repository.name,
                path,
                commitSha,
                repository.credentialAlias
            )
        }
        return quarantine.store(profile.profileId, commitSha, remoteFiles)
    }

    private suspend fun validateCommit(
        profile: Profile,
        repository: RemoteRepository,
        commitSha: String,
        commit: QuarantinedCommit
    ): ValidatedBatch {
        val issues = mutableListOf<String>()
        val revisions = mutableListOf<ValidatedRevision>()
        val events = mutableListOf<ReviewEvent>()
        val assets = mutableMapOf<String, ByteArray>()
        val timeService = ProfileTimeService.forProfile(profile)
        commit.files.sortedBy { it.repositoryPath }.forEach { quarantined ->
            val path = quarantined.repositoryPath
            val bytes = quarantined.file.readBytes()
            when {
                NOTE_PATH.matches(path) -> {
                    val parsed = NoteDocumentCodec.parse(bytes, path)
                    issues += parsed.issues.map { "$path: ${it.code}: ${it.message}" }
                    parsed.value?.let { document ->
                        val authoredAt = OffsetDateTime.parse(document.frontMatter.authoredAt).toInstant()
                        if (timeService.localDateAt(authoredAt).toString() != path.substringBefore('/')) {
                            issues += "$path: note.path_date: Note path date must use profile timezone"
                        }
                        revisions += ValidatedRevision(path, document)
                    }
                }
                EVENT_PATH.matches(path) -> {
                    val parsed = EventDocumentCodec.parse(bytes, path)
                    issues += parsed.issues.map { "$path: ${it.code}: ${it.message}" }
                    parsed.value?.let { event ->
                        val occurredAt = OffsetDateTime.parse(event.occurredAt).toInstant()
                        if (timeService.localDateAt(occurredAt).toString() != path.substringBefore('/')) {
                            issues += "$path: event.path_date: Event path date must use profile timezone"
                        }
                        runCatching { event.toEntity(profile, path) }
                            .onSuccess(events::add)
                            .onFailure { issues += "$path: ${it.message}" }
                    }
                }
                ASSET_PATH.matches(path) -> {
                    val match = ASSET_PATH.matchEntire(path)!!
                    val expectedHash = match.groupValues[2]
                    if (bytes.size > MAX_ASSET_BYTES) issues += "$path: asset exceeds 10 MiB"
                    if (sha256(bytes) != expectedHash) issues += "$path: asset hash mismatch"
                    assets[path] = bytes
                }
            }
        }

        revisions.forEach { revision ->
            val date = revision.path.substringBefore('/')
            MarkdownContent.invalidImageDestinations(revision.document.body).forEach {
                issues += "${revision.path}: invalid image destination $it"
            }
            MarkdownContent.assetReferences(revision.document.body).forEach { reference ->
                val assetPath = "$date/assets/${reference.sha256}.${reference.extension}"
                if (assetPath !in assets && assetCache.find(
                        profile.profileId,
                        reference.sha256,
                        reference.extension,
                        verifyHash = true
                    ) == null
                ) {
                    val remote = runCatching {
                        transport.getContent(
                            repository.owner,
                            repository.name,
                            assetPath,
                            commitSha,
                            repository.credentialAlias
                        )
                    }.getOrNull()
                    if (remote == null || sha256(remote.bytes) != reference.sha256) {
                        issues += "${revision.path}: missing or invalid asset $assetPath"
                    } else {
                        assets[assetPath] = remote.bytes
                    }
                }
            }
        }
        if (issues.isNotEmpty()) throw RemoteValidationException(issues)
        return ValidatedBatch(revisions, events, assets)
    }

    private suspend fun applyCommit(
        profile: Profile,
        repository: RemoteRepository,
        originalCheckpoint: SyncCheckpoint?,
        commitSha: String,
        batch: ValidatedBatch,
        replaceProjection: Boolean = false
    ) {
        val installedAssets = mutableMapOf<String, com.ebbinghaus.review.data.sync.CachedAsset>()
        batch.assets.forEach { (path, bytes) ->
            val extension = path.substringAfterLast('.')
            val installed = assetCache.install(
                profile.profileId,
                extension,
                ByteArrayInputStream(bytes)
            )
            installedAssets[path] = installed
        }
        val incomingRevisions = batch.revisions.map { validated ->
            val document = validated.document
            val metadata = document.frontMatter
            val cacheFile = revisionStore.install(profile.profileId, metadata.revisionId, document.originalBytes)
            NoteRevision(
                revisionId = metadata.revisionId,
                profileId = profile.profileId,
                noteId = metadata.noteId,
                parentRevisionIdsJson = gson.toJson(metadata.parentRevisionIds),
                revisionKind = metadata.revisionKind,
                authoredAt = OffsetDateTime.parse(metadata.authoredAt).toInstant().toEpochMilli(),
                learningStartedAt = OffsetDateTime.parse(metadata.learningStartedAt).toInstant().toEpochMilli(),
                sourceDeviceId = metadata.sourceDeviceId,
                contentSha256 = metadata.contentSha256,
                markdownCachePath = cacheFile.absolutePath,
                repositoryPath = validated.path
            )
        }
        val noteIds = (incomingRevisions.map { it.noteId } + batch.events.map { it.noteId }).toSortedSet()
        val timeService = ProfileTimeService.forProfile(profile)
        database.withTransaction {
            if (replaceProjection) {
                database.noteProjectionDao().clearProjection(profile.profileId)
                database.assetDao().resetReferenceCounts(profile.profileId)
            }
            noteIds.forEach { noteId ->
                val existingRevisions = database.noteProjectionDao().getRevisions(profile.profileId, noteId)
                val newRevisions = incomingRevisions.filter { it.noteId == noteId }.filter { incoming ->
                    val existing = existingRevisions.find { it.revisionId == incoming.revisionId }
                    if (existing != null && existing.contentSha256 != incoming.contentSha256) {
                        throw RemoteValidationException(listOf("Revision ID ${incoming.revisionId} is reused"))
                    }
                    existing == null
                }
                val allRevisions = existingRevisions + newRevisions
                if (allRevisions.isEmpty()) {
                    throw RemoteValidationException(listOf("Events target missing note $noteId"))
                }
                val existingEvents = allRevisions.flatMap { revision ->
                    database.noteProjectionDao().getEvents(
                        profile.profileId,
                        "review:${revision.revisionId}"
                    )
                } + database.noteProjectionDao().getEvents(profile.profileId, "lifecycle:$noteId")
                val newEvents = batch.events.filter { it.noteId == noteId }.filter { incoming ->
                    val existing = database.noteProjectionDao().getEvent(profile.profileId, incoming.eventId)
                    if (existing != null && existing.contentSha256 != incoming.contentSha256) {
                        throw RemoteValidationException(listOf("Event ID ${incoming.eventId} is reused"))
                    }
                    existing == null
                }
                val projection = NoteProjectionEngine(profile, timeService)
                    .project(allRevisions, existingEvents + newEvents)
                if (projection.state == NoteDomainState.QUARANTINED) {
                    throw RemoteValidationException(projection.issues)
                }
                val existingNote = database.noteProjectionDao().getNote(profile.profileId, noteId)
                val activeRevision = projection.activeRevisionId?.let { activeId ->
                    allRevisions.find { it.revisionId == activeId }
                }
                val title = activeRevision?.let(::readTitle) ?: existingNote?.title ?: "Untitled"
                val note = Note(
                    noteId = noteId,
                    profileId = profile.profileId,
                    title = title,
                    activeRevisionId = projection.activeRevisionId,
                    projectionState = projection.state.name,
                    reviewStage = projection.activeSchedule?.stage ?: 0,
                    nextReviewAt = projection.activeSchedule?.nextReviewAt,
                    isReviewComplete = projection.activeSchedule?.state == ReviewStreamState.COMPLETE,
                    createdAt = existingNote?.createdAt ?: allRevisions.minOf { it.authoredAt },
                    updatedAt = (newRevisions.maxOfOrNull { it.authoredAt }
                        ?: newEvents.maxOfOrNull { it.occurredAt }
                        ?: existingNote?.updatedAt
                        ?: System.currentTimeMillis())
                )
                database.noteProjectionDao().applyProjection(
                    profile.profileId,
                    note,
                    newRevisions,
                    newEvents
                )
                newRevisions.forEach { revision ->
                    val date = revision.repositoryPath!!.substringBefore('/')
                    val document = batch.revisions.first { it.document.frontMatter.revisionId == revision.revisionId }
                    MarkdownContent.assetReferences(document.document.body).forEach { reference ->
                        val assetId = installedAssets[
                            "$date/assets/${reference.sha256}.${reference.extension}"
                        ]?.assetId ?: database.assetDao().find(
                                profile.profileId,
                                reference.sha256,
                                reference.extension
                            )?.assetId ?: error("Validated asset disappeared")
                        check(database.assetDao().adjustReferenceCount(profile.profileId, assetId, 1) == 1)
                    }
                }
            }
            val checkpoint = database.syncStateDao().getCheckpoint(profile.profileId)
                ?: originalCheckpoint
            database.syncStateDao().saveCheckpoint(
                SyncCheckpoint(
                    checkpointId = checkpoint?.checkpointId ?: UUID.randomUUID().toString(),
                    profileId = profile.profileId,
                    repositoryId = repository.repositoryId,
                    remoteCommitSha = commitSha,
                    lastPullAt = System.currentTimeMillis(),
                    lastPushAt = checkpoint?.lastPushAt
                )
            )
        }
    }

    private suspend fun projectBatch(
        profile: Profile,
        batch: ValidatedBatch
    ) = batch.revisions.groupBy { it.document.frontMatter.noteId }.mapValues { (noteId, revisions) ->
        val entities = revisions.map { validated ->
            val metadata = validated.document.frontMatter
            NoteRevision(
                metadata.revisionId,
                profile.profileId,
                noteId,
                gson.toJson(metadata.parentRevisionIds),
                metadata.revisionKind,
                OffsetDateTime.parse(metadata.authoredAt).toInstant().toEpochMilli(),
                OffsetDateTime.parse(metadata.learningStartedAt).toInstant().toEpochMilli(),
                metadata.sourceDeviceId,
                metadata.contentSha256,
                "",
                validated.path
            )
        }
        NoteProjectionEngine(profile, ProfileTimeService.forProfile(profile)).project(
            entities,
            batch.events.filter { it.noteId == noteId }
        )
    }

    private fun readTitle(revision: NoteRevision): String = runCatching {
        val parsed = NoteDocumentCodec.parse(File(revision.markdownCachePath).readBytes(), revision.repositoryPath)
        MarkdownContent.titleProjection(checkNotNull(parsed.value).body)
    }.getOrDefault("Untitled")

    private fun ParsedRepositoryEvent.toEntity(profile: Profile, path: String): ReviewEvent {
        val noteId = payload.get("note_id")?.asString ?: error("event payload requires note_id")
        val revisionId = payload.get("revision_id")?.takeUnless { it.isJsonNull }?.asString
        require(algorithm.id == profile.algorithmId && algorithm.version == profile.algorithmVersion)
        return ReviewEvent(
            eventId,
            profile.profileId,
            noteId,
            revisionId,
            streamId,
            gson.toJson(parentEventIds),
            eventType,
            OffsetDateTime.parse(occurredAt).toInstant().toEpochMilli(),
            sourceDeviceId,
            algorithm.id,
            algorithm.version,
            gson.toJson(payload),
            sha256(originalBytes),
            path
        )
    }

    private data class ValidatedRevision(
        val path: String,
        val document: ParsedMarkdownRevision
    )

    private data class ValidatedBatch(
        val revisions: List<ValidatedRevision>,
        val events: List<ReviewEvent>,
        val assets: Map<String, ByteArray>
    )

    private companion object {
        const val COMMITS_PER_PAGE = 100
        const val MAX_ASSET_BYTES = 10 * 1024 * 1024
        val NOTE_PATH = Regex("\\d{4}-\\d{2}-\\d{2}/notes/[0-9a-f-]{36}\\.md")
        val EVENT_PATH = Regex("\\d{4}-\\d{2}-\\d{2}/events/[0-9a-f-]{36}\\.json")
        val ASSET_PATH = Regex("(\\d{4}-\\d{2}-\\d{2})/assets/([0-9a-f]{64})\\.[a-z0-9]{1,10}")
        val DATA_PATH = Regex("\\d{4}-\\d{2}-\\d{2}/(notes|assets|events)/.+")
        val CONTROL_SCHEMAS = mapOf(
            ".ebbinghaus/profile.schema.json" to
                "repository-control/ebbinghaus/profile.schema.json",
            ".ebbinghaus/note.schema.json" to
                "repository-control/ebbinghaus/note.schema.json",
            ".ebbinghaus/event.schema.json" to
                "repository-control/ebbinghaus/event.schema.json"
        )
    }
}
