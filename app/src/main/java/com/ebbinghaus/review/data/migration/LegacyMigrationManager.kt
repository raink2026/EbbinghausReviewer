package com.ebbinghaus.review.data.migration

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.room.withTransaction
import com.ebbinghaus.review.data.AppDatabase
import com.ebbinghaus.review.data.ReviewItem
import com.ebbinghaus.review.data.sync.Asset
import com.ebbinghaus.review.data.sync.CachedAsset
import com.ebbinghaus.review.data.sync.ImmutableAssetCache
import com.ebbinghaus.review.data.sync.ImmutableRevisionStore
import com.ebbinghaus.review.data.sync.ImmutableSyncPayloadStore
import com.ebbinghaus.review.data.sync.Note
import com.ebbinghaus.review.data.sync.NoteRevision
import com.ebbinghaus.review.data.sync.Profile
import com.ebbinghaus.review.data.sync.ProfileTimeService
import com.ebbinghaus.review.data.sync.RemoteRepository
import com.ebbinghaus.review.data.sync.RepositoryDateCategory
import com.ebbinghaus.review.data.sync.ReviewEvent
import com.ebbinghaus.review.data.sync.RevisionAssetPublication
import com.ebbinghaus.review.data.sync.SyncOutbox
import com.ebbinghaus.review.data.sync.projection.NoteProjectionEngine
import com.ebbinghaus.review.data.sync.projection.ReviewStreamState
import com.ebbinghaus.review.data.sync.protocol.EVENT_SCHEMA
import com.ebbinghaus.review.data.sync.protocol.EventAlgorithm
import com.ebbinghaus.review.data.sync.protocol.EventDocumentCodec
import com.ebbinghaus.review.data.sync.protocol.MarkdownAssetReference
import com.ebbinghaus.review.data.sync.protocol.MarkdownContent
import com.ebbinghaus.review.data.sync.protocol.NOTE_SCHEMA
import com.ebbinghaus.review.data.sync.protocol.NoteDocumentCodec
import com.ebbinghaus.review.data.sync.protocol.NoteFrontMatter
import com.ebbinghaus.review.data.sync.protocol.ParsedRepositoryEvent
import com.ebbinghaus.review.data.sync.protocol.sha256
import com.ebbinghaus.review.data.sync.remote.FullRebuildPreview
import com.ebbinghaus.review.data.sync.remote.GiteePullService
import com.google.gson.Gson
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

enum class LegacyMigrationStatus {
    NOT_STARTED,
    QUEUED,
    RESTORE_VERIFIED,
    ROLLED_BACK
}

data class LegacyMigrationPreview(
    val profileId: String,
    val totalItems: Int,
    val convertedNotes: Int,
    val skippedDeletedItems: Int,
    val assetPaths: Int,
    val eventCount: Int,
    val blockingIssues: List<String>,
    val scheduleDifferences: List<String>
) {
    val canQueue: Boolean get() = blockingIssues.isEmpty()
}

data class LegacyRestoreResult(
    val preview: FullRebuildPreview,
    val matchesMigration: Boolean
)

class LegacyMigrationManager(
    context: Context,
    private val database: AppDatabase
) {
    private val appContext = context.applicationContext
    private val assetCache = ImmutableAssetCache(appContext, database.assetDao())
    private val revisionStore = ImmutableRevisionStore(appContext)
    private val payloadStore = ImmutableSyncPayloadStore(appContext)
    private val gson = Gson()
    private val state = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val legacyGuard = LegacyDataGuard(appContext)

    fun status(profileId: String): LegacyMigrationStatus = runCatching {
        LegacyMigrationStatus.valueOf(
            state.getString(statusKey(profileId), LegacyMigrationStatus.NOT_STARTED.name)
                ?: LegacyMigrationStatus.NOT_STARTED.name
        )
    }.getOrDefault(LegacyMigrationStatus.NOT_STARTED)

    suspend fun preview(profileId: String): LegacyMigrationPreview = prepare(profileId).preview

    suspend fun queueMigration(
        profileId: String,
        acceptScheduleDifferences: Boolean
    ): LegacyMigrationPreview {
        recoverCommittedQueue(profileId)?.let { return it }
        val prepared = prepare(profileId)
        require(prepared.preview.blockingIssues.isEmpty()) {
            prepared.preview.blockingIssues.joinToString("; ")
        }
        require(acceptScheduleDifferences || prepared.preview.scheduleDifferences.isEmpty()) {
            "Resolve the reported schedule differences before migration"
        }
        val repository = requireRepository(profileId)
        database.withTransaction {
            require(database.noteProjectionDao().getNotes(profileId).isEmpty()) {
                "Legacy migration requires an empty destination profile"
            }
            require(database.syncStateDao().getAllOutbox(profileId).isEmpty()) {
                "Legacy migration requires an empty destination outbox"
            }
            prepared.notes.forEach { preparedNote ->
                val assetOperations = preparedNote.assets.map { publication ->
                    database.syncStateDao().findOutboxByPath(profileId, publication.repositoryPath)
                        ?: SyncOutbox(
                            operationId = UUID.randomUUID().toString(),
                            profileId = profileId,
                            repositoryId = repository.repositoryId,
                            operationType = "CREATE_ASSET",
                            repositoryPath = publication.repositoryPath,
                            contentSha256 = publication.asset.sha256,
                            payloadCachePath = publication.asset.file.absolutePath,
                            dependencyIdsJson = "[]",
                            profileDate = publication.repositoryPath.substringBefore('/')
                        ).also { operation -> database.syncStateDao().insertOutbox(operation) }
                }
                val noteOperation = SyncOutbox(
                    operationId = UUID.randomUUID().toString(),
                    profileId = profileId,
                    repositoryId = repository.repositoryId,
                    operationType = "CREATE_NOTE",
                    repositoryPath = checkNotNull(preparedNote.revision.repositoryPath),
                    contentSha256 = sha256(preparedNote.noteBytes),
                    payloadCachePath = preparedNote.revision.markdownCachePath,
                    dependencyIdsJson = gson.toJson(assetOperations.map { it.operationId }),
                    profileDate = checkNotNull(preparedNote.revision.repositoryPath).substringBefore('/')
                )
                database.syncStateDao().insertOutbox(noteOperation)
                preparedNote.events.forEach { migratedEvent ->
                    database.syncStateDao().insertOutbox(
                        SyncOutbox(
                            operationId = UUID.randomUUID().toString(),
                            profileId = profileId,
                            repositoryId = repository.repositoryId,
                            operationType = "CREATE_EVENT",
                            repositoryPath = checkNotNull(migratedEvent.event.repositoryPath),
                            contentSha256 = migratedEvent.event.contentSha256,
                            payloadCachePath = migratedEvent.payloadFile.absolutePath,
                            dependencyIdsJson = gson.toJson(listOf(noteOperation.operationId)),
                            profileDate = checkNotNull(migratedEvent.event.repositoryPath)
                                .substringBefore('/')
                        )
                    )
                }
                database.noteProjectionDao().applyProjection(
                    profileId,
                    preparedNote.note,
                    listOf(preparedNote.revision),
                    preparedNote.events.map { it.event }
                )
                preparedNote.assets.forEach { publication ->
                    check(database.assetDao().adjustReferenceCount(
                        profileId,
                        publication.asset.assetId,
                        1
                    ) == 1)
                }
            }
        }
        state.edit()
            .putString(statusKey(profileId), LegacyMigrationStatus.QUEUED.name)
            .putInt(countKey(profileId, "notes"), prepared.preview.convertedNotes)
            .putInt(countKey(profileId, "assets"), prepared.preview.assetPaths)
            .putInt(countKey(profileId, "events"), prepared.preview.eventCount)
            .apply()
        legacyGuard.setReadOnly(true)
        return prepared.preview
    }

    private suspend fun recoverCommittedQueue(profileId: String): LegacyMigrationPreview? {
        val notes = database.noteProjectionDao().getNotes(profileId)
        val outbox = database.syncStateDao().getAllOutbox(profileId)
        if (notes.isEmpty() && outbox.isEmpty()) return null

        val legacyItems = database.reviewDao().getAllItemsIncludingDeleted()
        val expectedNoteIds = legacyItems
            .mapTo(mutableSetOf()) { stableUuid(profileId, "item:${it.id}:note") }
        require(notes.mapTo(mutableSetOf()) { it.noteId } == expectedNoteIds) {
            "Legacy migration destination contains unrelated or partial notes"
        }
        val noteOperations = outbox.filter { it.operationType == "CREATE_NOTE" }
        require(noteOperations.size == notes.size) {
            "Legacy migration outbox is incomplete; rollback or repair it before retrying"
        }
        val repository = requireRepository(profileId)
        require(outbox.all { it.repositoryId == repository.repositoryId }) {
            "Legacy migration outbox targets another repository"
        }

        val preview = LegacyMigrationPreview(
            profileId = profileId,
            totalItems = legacyItems.size,
            convertedNotes = notes.size,
            skippedDeletedItems = 0,
            assetPaths = outbox.count { it.operationType == "CREATE_ASSET" },
            eventCount = outbox.count { it.operationType == "CREATE_EVENT" },
            blockingIssues = emptyList(),
            scheduleDifferences = emptyList()
        )
        state.edit()
            .putString(statusKey(profileId), LegacyMigrationStatus.QUEUED.name)
            .putInt(countKey(profileId, "notes"), preview.convertedNotes)
            .putInt(countKey(profileId, "assets"), preview.assetPaths)
            .putInt(countKey(profileId, "events"), preview.eventCount)
            .apply()
        legacyGuard.setReadOnly(true)
        return preview
    }

    suspend fun rollbackBeforePublish(profileId: String) {
        require(status(profileId) == LegacyMigrationStatus.QUEUED) {
            "No queued legacy migration is available for rollback"
        }
        require(database.syncStateDao().getCheckpoint(profileId)?.lastPushAt == null) {
            "Migration has already been published and cannot be rolled back locally"
        }
        val expectedNoteIds = database.reviewDao().getAllItemsIncludingDeleted()
            .mapTo(mutableSetOf()) { stableUuid(profileId, "item:${it.id}:note") }
        database.withTransaction {
            val actualNoteIds = database.noteProjectionDao().getNotes(profileId)
                .mapTo(mutableSetOf()) { it.noteId }
            require(actualNoteIds.all { it in expectedNoteIds }) {
                "Destination profile now contains non-migration notes"
            }
            database.syncStateDao().deleteOutboxForProfile(profileId)
            database.noteProjectionDao().clearProjection(profileId)
            database.assetDao().resetReferenceCounts(profileId)
        }
        assetCache.removeReconstructibleUnreferenced(profileId)
        assetCache.cleanOrphans(profileId)
        revisionStore.cleanOrphans(profileId, emptySet())
        payloadStore.cleanOrphans(profileId, emptySet())
        state.edit().putString(statusKey(profileId), LegacyMigrationStatus.ROLLED_BACK.name).apply()
        legacyGuard.setReadOnly(false)
    }

    suspend fun rehearseRestore(
        profileId: String,
        pullService: GiteePullService
    ): LegacyRestoreResult {
        require(status(profileId) == LegacyMigrationStatus.QUEUED)
        require(database.syncStateDao().getAllOutbox(profileId).isEmpty()) {
            "Publish all migration files before the restore rehearsal"
        }
        val preview = pullService.previewFullRebuild(profileId)
        val matches = preview.canApply && preview.conflictCount == 0 &&
            preview.noteCount == state.getInt(countKey(profileId, "notes"), -1) &&
            preview.assetCount == state.getInt(countKey(profileId, "assets"), -1) &&
            preview.eventCount == state.getInt(countKey(profileId, "events"), -1)
        if (matches) {
            state.edit().putString(
                statusKey(profileId),
                LegacyMigrationStatus.RESTORE_VERIFIED.name
            ).apply()
            legacyGuard.setReadOnly(true)
        }
        return LegacyRestoreResult(preview, matches)
    }

    private suspend fun prepare(profileId: String): PreparedMigration {
        val profile = database.profileDao().getProfile(profileId) ?: error("Unknown profile")
        requireRepository(profileId)
        require(database.noteProjectionDao().getNotes(profileId).isEmpty()) {
            "Choose an empty destination profile for legacy migration"
        }
        val items = database.reviewDao().getAllItemsIncludingDeleted()
        val issues = mutableListOf<String>()
        val differences = mutableListOf<String>()
        val preparedNotes = mutableListOf<PreparedLegacyNote>()
        val timeService = ProfileTimeService.forProfile(profile)
        items.forEach { item ->
            runCatching {
                prepareItem(profile, timeService, item, differences)
            }.onSuccess(preparedNotes::add)
                .onFailure { error -> issues += "${item.title}: ${error.message}" }
        }
        val preview = LegacyMigrationPreview(
            profileId = profileId,
            totalItems = items.size,
            convertedNotes = preparedNotes.size,
            skippedDeletedItems = 0,
            assetPaths = preparedNotes.flatMap { it.assets }.map { it.repositoryPath }.distinct().size,
            eventCount = preparedNotes.sumOf { it.events.size },
            blockingIssues = issues,
            scheduleDifferences = differences
        )
        return PreparedMigration(preview, preparedNotes)
    }

    private suspend fun prepareItem(
        profile: Profile,
        timeService: ProfileTimeService,
        item: ReviewItem,
        differences: MutableList<String>
    ): PreparedLegacyNote {
        require(item.createdTime > 0) { "createdTime is invalid" }
        val noteId = stableUuid(profile.profileId, "item:${item.id}:note")
        val revisionId = stableUuid(profile.profileId, "item:${item.id}:revision")
        val assets = loadAssets(profile, timeService, item)
        val body = legacyMarkdown(item, assets)
        val authoredAt = timeService.formatOffsetDateTime(item.createdTime)
        val noteBytes = NoteDocumentCodec.serialize(
            NoteFrontMatter(
                schema = NOTE_SCHEMA,
                noteId = noteId,
                revisionId = revisionId,
                parentRevisionIds = emptyList(),
                revisionKind = "create",
                authoredAt = authoredAt,
                learningStartedAt = authoredAt,
                sourceDeviceId = stableUuid(profile.profileId, "legacy-device"),
                contentSha256 = ""
            ),
            body
        )
        val notePath = timeService.repositoryPath(
            RepositoryDateCategory.NOTES,
            "$revisionId.md",
            item.createdTime
        )
        val parsedNote = NoteDocumentCodec.parse(noteBytes, notePath)
        require(parsedNote.isValid) { parsedNote.issues.joinToString { it.message } }
        val document = checkNotNull(parsedNote.value)
        val noteFile = revisionStore.install(profile.profileId, revisionId, noteBytes)
        val revision = NoteRevision(
            revisionId = revisionId,
            profileId = profile.profileId,
            noteId = noteId,
            parentRevisionIdsJson = "[]",
            revisionKind = "create",
            authoredAt = item.createdTime,
            learningStartedAt = item.createdTime,
            sourceDeviceId = stableUuid(profile.profileId, "legacy-device"),
            contentSha256 = document.frontMatter.contentSha256,
            markdownCachePath = noteFile.absolutePath,
            repositoryPath = notePath,
            createdAt = item.createdTime
        )
        val baseNote = Note(
            noteId = noteId,
            profileId = profile.profileId,
            title = MarkdownContent.titleProjection(body),
            activeRevisionId = revisionId,
            projectionState = "ACTIVE",
            reviewStage = 0,
            nextReviewAt = item.createdTime,
            isReviewComplete = false,
            createdAt = item.createdTime,
            updatedAt = item.createdTime
        )
        val events = buildEvents(profile, timeService, item, noteId, revisionId, differences)
            .toMutableList()
        if (item.isDeleted) {
            events += buildDeleteEvent(profile, timeService, item, noteId, revisionId)
        }
        val projection = NoteProjectionEngine(profile, timeService)
            .project(listOf(revision), events.map { it.event })
        require(projection.issues.isEmpty()) { projection.issues.joinToString() }
        val projectedNote = baseNote.copy(
            activeRevisionId = projection.activeRevisionId,
            projectionState = projection.state.name,
            reviewStage = projection.activeSchedule?.stage ?: 0,
            nextReviewAt = projection.activeSchedule?.nextReviewAt,
            isReviewComplete = projection.activeSchedule?.state == ReviewStreamState.COMPLETE,
            updatedAt = events.maxOfOrNull { it.event.occurredAt } ?: item.createdTime
        )
        val legacyStage = if (item.isFinished) 8 else item.stage.coerceIn(0, 8)
        val legacyNext = item.nextReviewTime.takeUnless { item.isFinished }
        if (projectedNote.reviewStage != legacyStage || projectedNote.nextReviewAt != legacyNext) {
            differences += "${item.title}: legacy stage/next=$legacyStage/$legacyNext, " +
                "replayed=${projectedNote.reviewStage}/${projectedNote.nextReviewAt}"
        }
        return PreparedLegacyNote(projectedNote, revision, noteBytes, assets, events)
    }

    private suspend fun loadAssets(
        profile: Profile,
        timeService: ProfileTimeService,
        item: ReviewItem
    ): List<RevisionAssetPublication> = item.imagePaths.orEmpty().split('|')
        .map(String::trim).filter(String::isNotEmpty).map { source ->
            val uri = Uri.parse(source)
            val extension = extensionFor(uri, source)
            val cached = openLegacyImage(uri, source).use { input ->
                assetCache.install(profile.profileId, extension, input)
            }
            RevisionAssetPublication(
                reference = MarkdownAssetReference(
                    destination = "../assets/${cached.sha256}.${cached.extension}",
                    sha256 = cached.sha256,
                    extension = cached.extension,
                    altText = "图片"
                ),
                asset = cached,
                repositoryPath = timeService.repositoryPath(
                    RepositoryDateCategory.ASSETS,
                    "${cached.sha256}.${cached.extension}",
                    item.createdTime
                )
            )
        }.distinctBy { it.repositoryPath }

    private suspend fun buildEvents(
        profile: Profile,
        timeService: ProfileTimeService,
        item: ReviewItem,
        noteId: String,
        revisionId: String,
        differences: MutableList<String>
    ): List<PreparedLegacyEvent> {
        val intervals = JsonParser.parseString(profile.algorithmParametersJson)
            .asJsonObject.getAsJsonArray("interval_days").map { it.asInt }
        var currentStage = 0
        var parentId: String? = null
        return database.reviewDao().getLogsByItemId(item.id).sortedWith(
            compareBy({ it.reviewTime }, { it.id })
        ).map { log ->
            require(log.reviewTime > 0) { "review log ${log.id} has an invalid time" }
            val result = when (log.action.uppercase()) {
                "REMEMBER" -> "remember"
                "FORGET" -> "forget"
                else -> error("review log ${log.id} has unsupported action ${log.action}")
            }
            val expectedAfter = if (result == "remember") currentStage + 1 else 0
            require(expectedAfter in 0..intervals.size) { "review log ${log.id} exceeds stage range" }
            val legacyAfter = if (log.stageAfter == 99) intervals.size else log.stageAfter
            if (log.stageBefore != currentStage || legacyAfter != expectedAfter) {
                differences += "${item.title}: log ${log.id} stage ${log.stageBefore}->$legacyAfter " +
                    "replays as $currentStage->$expectedAfter"
            }
            val nextReviewAt = if (expectedAfter == intervals.size) null else {
                timeService.reviewAtStartOfDay(log.reviewTime, intervals[expectedAfter])
            }
            val eventId = stableUuid(profile.profileId, "log:${log.id}:event")
            val payload = JsonObject().apply {
                addProperty("note_id", noteId)
                addProperty("revision_id", revisionId)
                addProperty("result", result)
                addProperty("stage_before", currentStage)
                addProperty("stage_after", expectedAfter)
                if (nextReviewAt == null) add("next_review_at", JsonNull.INSTANCE)
                else addProperty("next_review_at", timeService.formatOffsetDateTime(nextReviewAt))
            }
            val parsed = ParsedRepositoryEvent(
                schema = EVENT_SCHEMA,
                eventId = eventId,
                streamId = "review:$revisionId",
                parentEventIds = listOfNotNull(parentId),
                eventType = "review",
                occurredAt = timeService.formatOffsetDateTime(log.reviewTime),
                sourceDeviceId = stableUuid(profile.profileId, "legacy-device"),
                algorithm = EventAlgorithm(profile.algorithmId, profile.algorithmVersion),
                payload = payload,
                originalBytes = byteArrayOf()
            )
            val bytes = EventDocumentCodec.serialize(parsed)
            val path = timeService.repositoryPath(
                RepositoryDateCategory.EVENTS,
                "$eventId.json",
                log.reviewTime
            )
            require(EventDocumentCodec.parse(bytes, path).isValid)
            val event = ReviewEvent(
                eventId = eventId,
                profileId = profile.profileId,
                noteId = noteId,
                revisionId = revisionId,
                streamId = parsed.streamId,
                parentEventIdsJson = gson.toJson(parsed.parentEventIds),
                eventType = "review",
                occurredAt = log.reviewTime,
                sourceDeviceId = parsed.sourceDeviceId,
                algorithmId = profile.algorithmId,
                algorithmVersion = profile.algorithmVersion,
                payloadJson = gson.toJson(payload),
                contentSha256 = sha256(bytes),
                repositoryPath = path,
                createdAt = log.reviewTime
            )
            currentStage = expectedAfter
            parentId = eventId
            PreparedLegacyEvent(
                event,
                payloadStore.install(profile.profileId, event.contentSha256, "json", bytes)
            )
        }
    }

    private fun buildDeleteEvent(
        profile: Profile,
        timeService: ProfileTimeService,
        item: ReviewItem,
        noteId: String,
        revisionId: String
    ): PreparedLegacyEvent {
        val occurredAt = requireNotNull(item.deletedTime) {
            "Deleted legacy item ${item.id} has no deletion time"
        }
        require(occurredAt > 0) { "Deleted legacy item ${item.id} has an invalid deletion time" }
        val eventId = stableUuid(profile.profileId, "item:${item.id}:delete-event")
        val payload = JsonObject().apply {
            addProperty("note_id", noteId)
            add("visible_revision_ids", gson.toJsonTree(listOf(revisionId)))
        }
        val parsed = ParsedRepositoryEvent(
            schema = EVENT_SCHEMA,
            eventId = eventId,
            streamId = "lifecycle:$noteId",
            parentEventIds = emptyList(),
            eventType = "delete",
            occurredAt = timeService.formatOffsetDateTime(occurredAt),
            sourceDeviceId = stableUuid(profile.profileId, "legacy-device"),
            algorithm = EventAlgorithm(profile.algorithmId, profile.algorithmVersion),
            payload = payload,
            originalBytes = byteArrayOf()
        )
        val bytes = EventDocumentCodec.serialize(parsed)
        val path = timeService.repositoryPath(
            RepositoryDateCategory.EVENTS,
            "$eventId.json",
            occurredAt
        )
        require(EventDocumentCodec.parse(bytes, path).isValid)
        val event = ReviewEvent(
            eventId = eventId,
            profileId = profile.profileId,
            noteId = noteId,
            revisionId = null,
            streamId = parsed.streamId,
            parentEventIdsJson = "[]",
            eventType = "delete",
            occurredAt = occurredAt,
            sourceDeviceId = parsed.sourceDeviceId,
            algorithmId = profile.algorithmId,
            algorithmVersion = profile.algorithmVersion,
            payloadJson = gson.toJson(payload),
            contentSha256 = sha256(bytes),
            repositoryPath = path,
            createdAt = occurredAt
        )
        return PreparedLegacyEvent(
            event,
            payloadStore.install(profile.profileId, event.contentSha256, "json", bytes)
        )
    }

    private fun legacyMarkdown(
        item: ReviewItem,
        assets: List<RevisionAssetPublication>
    ): String = buildString {
        append("# ").append(item.title.replace('\n', ' ').trim()).append("\n\n")
        if (item.description.isNotBlank()) append(item.description.trim()).append("\n\n")
        append(item.content.trim()).append("\n")
        assets.forEachIndexed { index, publication ->
            append("\n![图片 ").append(index + 1).append("](")
                .append(publication.reference.destination).append(")\n")
        }
    }

    private fun openLegacyImage(uri: Uri, source: String): InputStream = when {
        uri.scheme == "file" -> File(requireNotNull(uri.path)).inputStream()
        uri.scheme == "content" -> appContext.contentResolver.openInputStream(uri)
            ?: error("cannot read image $source")
        uri.scheme.isNullOrBlank() -> File(source).inputStream()
        else -> appContext.contentResolver.openInputStream(uri)
            ?: error("cannot read image $source")
    }

    private fun extensionFor(uri: Uri, source: String): String {
        val mime = appContext.contentResolver.getType(uri)
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            ?: source.substringAfterLast('.', "jpg").substringBefore('?').lowercase()
    }

    private suspend fun requireRepository(profileId: String): RemoteRepository =
        database.remoteRepositoryDao().getForProfile(profileId)
            ?.takeIf { it.isBound }
            ?: error("Choose a bound destination repository")

    private fun stableUuid(profileId: String, value: String): String = UUID.nameUUIDFromBytes(
        "$profileId:$value".toByteArray(StandardCharsets.UTF_8)
    ).toString()

    private fun statusKey(profileId: String) = "status:$profileId"
    private fun countKey(profileId: String, kind: String) = "count:$profileId:$kind"

    private data class PreparedMigration(
        val preview: LegacyMigrationPreview,
        val notes: List<PreparedLegacyNote>
    )

    private data class PreparedLegacyNote(
        val note: Note,
        val revision: NoteRevision,
        val noteBytes: ByteArray,
        val assets: List<RevisionAssetPublication>,
        val events: List<PreparedLegacyEvent>
    )

    private data class PreparedLegacyEvent(
        val event: ReviewEvent,
        val payloadFile: File
    )

    private companion object {
        const val PREFERENCES = "legacy_sync_migration"
    }
}

class LegacyDataGuard(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "legacy_sync_migration_guard",
        Context.MODE_PRIVATE
    )

    val isReadOnly: Boolean get() = preferences.getBoolean("read_only", false)

    fun requireWritable() {
        check(!isReadOnly) { "Legacy data is read-only after migration" }
    }

    internal fun setReadOnly(value: Boolean) {
        preferences.edit().putBoolean("read_only", value).apply()
    }
}
