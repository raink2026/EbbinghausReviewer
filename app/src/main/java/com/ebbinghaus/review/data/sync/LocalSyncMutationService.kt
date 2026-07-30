package com.ebbinghaus.review.data.sync

import androidx.room.withTransaction
import com.ebbinghaus.review.data.AppDatabase
import com.ebbinghaus.review.data.sync.protocol.sha256
import com.google.gson.Gson
import java.io.File
import java.util.UUID

class LocalSyncMutationService(
    private val database: AppDatabase,
    private val payloadStore: ImmutableSyncPayloadStore
) {
    private val gson = Gson()

    suspend fun saveRevision(draft: NoteRevisionDraft) {
        val repository = requireRepository(draft.note.profileId)
        database.withTransaction {
            val assetOperations = draft.assets.mapNotNull { publication ->
                database.syncStateDao().findOutboxByPath(
                    draft.note.profileId,
                    publication.repositoryPath
                ) ?: SyncOutbox(
                    operationId = UUID.randomUUID().toString(),
                    profileId = draft.note.profileId,
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
                profileId = draft.note.profileId,
                repositoryId = repository.repositoryId,
                operationType = "CREATE_NOTE",
                repositoryPath = checkNotNull(draft.revision.repositoryPath),
                contentSha256 = sha256(draft.documentBytes),
                payloadCachePath = draft.revision.markdownCachePath,
                dependencyIdsJson = gson.toJson(assetOperations.map { it.operationId }),
                profileDate = checkNotNull(draft.revision.repositoryPath).substringBefore('/')
            )
            check(database.syncStateDao().findOutboxByPath(
                draft.note.profileId,
                noteOperation.repositoryPath
            ) == null) { "Revision repository path already has a pending operation" }

            database.noteProjectionDao().applyProjection(
                draft.note.profileId,
                draft.note,
                listOf(draft.revision),
                emptyList()
            )
            draft.assets.forEach { publication ->
                check(database.assetDao().adjustReferenceCount(
                    draft.note.profileId,
                    publication.asset.assetId,
                    1
                ) == 1)
            }
            database.syncStateDao().insertOutbox(noteOperation)
        }
    }

    suspend fun appendEvent(note: Note, eventDraft: ReviewEventDraft, updatedNote: Note) {
        appendEventInternal(note, eventDraft.event, eventDraft.documentBytes, updatedNote)
    }

    suspend fun appendEvent(note: Note, eventDraft: LifecycleEventDraft, updatedNote: Note) {
        appendEventInternal(note, eventDraft.event, eventDraft.documentBytes, updatedNote)
    }

    private suspend fun appendEventInternal(
        note: Note,
        event: ReviewEvent,
        documentBytes: ByteArray,
        updatedNote: Note
    ) {
        val repository = requireRepository(note.profileId)
        val contentFile = payloadStore.install(note.profileId, event.contentSha256, "json", documentBytes)
        val operation = SyncOutbox(
            operationId = UUID.randomUUID().toString(),
            profileId = note.profileId,
            repositoryId = repository.repositoryId,
            operationType = "CREATE_EVENT",
            repositoryPath = checkNotNull(event.repositoryPath),
            contentSha256 = event.contentSha256,
            payloadCachePath = contentFile.absolutePath,
            dependencyIdsJson = "[]",
            profileDate = checkNotNull(event.repositoryPath).substringBefore('/')
        )
        database.withTransaction {
            check(database.syncStateDao().findOutboxByPath(note.profileId, operation.repositoryPath) == null)
            database.noteProjectionDao().applyProjection(
                note.profileId,
                updatedNote,
                emptyList(),
                listOf(event)
            )
            database.syncStateDao().insertOutbox(operation)
        }
    }

    private suspend fun requireRepository(profileId: String): RemoteRepository =
        database.remoteRepositoryDao().getForProfile(profileId)
            ?.takeIf { it.isBound }
            ?: error("Profile must be bound before creating synchronized data")
}
