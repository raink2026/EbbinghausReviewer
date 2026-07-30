package com.ebbinghaus.review.data.sync

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ProfileDao {
    @Query("SELECT * FROM sync_profiles ORDER BY displayName, profileId")
    abstract fun observeProfiles(): Flow<List<Profile>>

    @Query("SELECT * FROM sync_profiles WHERE isCurrent = 1 LIMIT 1")
    abstract fun observeCurrentProfile(): Flow<Profile?>

    @Query("SELECT * FROM sync_profiles WHERE isCurrent = 1 LIMIT 1")
    abstract suspend fun getCurrentProfile(): Profile?

    @Query("SELECT * FROM sync_profiles WHERE profileId = :profileId LIMIT 1")
    abstract suspend fun getProfile(profileId: String): Profile?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertRow(profile: Profile)

    open suspend fun insert(profile: Profile) {
        require(!profile.isCurrent) { "Select the current profile through switchToProfile" }
        insertRow(profile)
    }

    @Update
    abstract suspend fun update(profile: Profile)

    @Query("UPDATE sync_profiles SET isCurrent = 0 WHERE isCurrent = 1")
    protected abstract suspend fun clearCurrent()

    @Query(
        "UPDATE sync_profiles SET isCurrent = 1, updatedAt = :updatedAt " +
            "WHERE profileId = :profileId"
    )
    protected abstract suspend fun markCurrent(profileId: String, updatedAt: Long): Int

    @Transaction
    open suspend fun switchToProfile(profileId: String, updatedAt: Long = System.currentTimeMillis()) {
        require(getProfile(profileId) != null) { "Unknown profile: $profileId" }
        clearCurrent()
        check(markCurrent(profileId, updatedAt) == 1) { "Unable to select profile: $profileId" }
    }
}

@Dao
abstract class RemoteRepositoryDao {
    @Query("SELECT * FROM remote_repositories WHERE profileId = :profileId LIMIT 1")
    abstract fun observeForProfile(profileId: String): Flow<RemoteRepository?>

    @Query("SELECT * FROM remote_repositories WHERE profileId = :profileId LIMIT 1")
    abstract suspend fun getForProfile(profileId: String): RemoteRepository?

    @Query(
        "SELECT * FROM remote_repositories " +
            "WHERE isBound = 1 AND autoSync = 1 ORDER BY profileId"
    )
    abstract suspend fun getAutoSyncRepositories(): List<RemoteRepository>

    @Query(
        "SELECT * FROM remote_repositories " +
            "WHERE repositoryId = :repositoryId LIMIT 1"
    )
    abstract suspend fun findIdentityBinding(repositoryId: String): RemoteRepository?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(repository: RemoteRepository)

    @Query(
        "UPDATE remote_repositories SET owner = :owner, name = :name, branch = :branch, " +
            "credentialAlias = :credentialAlias, isBound = :isBound, autoSync = :autoSync, " +
            "wifiOnly = :wifiOnly, updatedAt = :updatedAt " +
            "WHERE profileId = :profileId AND repositoryId = :repositoryId"
    )
    abstract suspend fun updateMutableConfiguration(
        profileId: String,
        repositoryId: String,
        owner: String,
        name: String,
        branch: String,
        credentialAlias: String,
        isBound: Boolean,
        autoSync: Boolean,
        wifiOnly: Boolean,
        updatedAt: Long
    ): Int

    @Query(
        "UPDATE remote_repositories SET isBound = 0, autoSync = 0, " +
            "syncState = 'UNBOUND', updatedAt = :updatedAt " +
            "WHERE profileId = :profileId AND repositoryId = :repositoryId"
    )
    abstract suspend fun markUnbound(
        profileId: String,
        repositoryId: String,
        updatedAt: Long
    ): Int

    @Query(
        "UPDATE remote_repositories SET syncState = :syncState, lastSyncError = :lastError, " +
            "updatedAt = :updatedAt WHERE profileId = :profileId AND repositoryId = :repositoryId"
    )
    abstract suspend fun updateSyncHealth(
        profileId: String,
        repositoryId: String,
        syncState: String,
        lastError: String?,
        updatedAt: Long
    ): Int

    @Delete
    protected abstract suspend fun deletePermanently(repository: RemoteRepository)
}

@Dao
abstract class NoteProjectionDao {
    @Query(
        "SELECT * FROM sync_notes WHERE profileId = :profileId " +
            "ORDER BY updatedAt DESC, noteId"
    )
    abstract fun observeNotes(profileId: String): Flow<List<Note>>

    @Query("SELECT * FROM sync_notes WHERE profileId = :profileId ORDER BY noteId")
    abstract suspend fun getNotes(profileId: String): List<Note>

    @Query(
        "SELECT * FROM sync_notes WHERE profileId = :profileId " +
            "AND projectionState = :projectionState ORDER BY updatedAt DESC, noteId"
    )
    abstract fun observeNotesByState(
        profileId: String,
        projectionState: String
    ): Flow<List<Note>>

    @Query(
        "SELECT * FROM sync_notes WHERE profileId = :profileId " +
            "AND projectionState = 'ACTIVE' AND isReviewComplete = 0 " +
            "AND nextReviewAt IS NOT NULL AND nextReviewAt <= :nowMillis " +
            "ORDER BY nextReviewAt, noteId"
    )
    abstract fun observeDueNotes(profileId: String, nowMillis: Long): Flow<List<Note>>

    @Query(
        "SELECT COUNT(*) FROM sync_notes WHERE profileId = :profileId " +
            "AND projectionState = 'ACTIVE' AND isReviewComplete = 0 " +
            "AND nextReviewAt IS NOT NULL AND nextReviewAt <= :nowMillis"
    )
    abstract suspend fun getDueCount(profileId: String, nowMillis: Long): Int

    @Query("SELECT * FROM sync_notes WHERE profileId = :profileId AND noteId = :noteId LIMIT 1")
    abstract suspend fun getNote(profileId: String, noteId: String): Note?

    @Query(
        "SELECT * FROM note_revisions WHERE profileId = :profileId " +
            "AND noteId = :noteId ORDER BY authoredAt, revisionId"
    )
    abstract suspend fun getRevisions(profileId: String, noteId: String): List<NoteRevision>

    @Query(
        "SELECT * FROM note_revisions WHERE profileId = :profileId " +
            "AND revisionId = :revisionId LIMIT 1"
    )
    abstract suspend fun getRevision(profileId: String, revisionId: String): NoteRevision?

    @Query(
        "SELECT * FROM sync_review_events WHERE profileId = :profileId " +
            "AND streamId = :streamId ORDER BY occurredAt, eventId"
    )
    abstract suspend fun getEvents(profileId: String, streamId: String): List<ReviewEvent>

    @Query(
        "SELECT * FROM sync_review_events WHERE profileId = :profileId " +
            "AND eventId = :eventId LIMIT 1"
    )
    abstract suspend fun getEvent(profileId: String, eventId: String): ReviewEvent?

    @Query("SELECT * FROM note_revisions WHERE profileId = :profileId ORDER BY authoredAt, revisionId")
    abstract suspend fun getAllRevisions(profileId: String): List<NoteRevision>

    @Query("SELECT * FROM sync_review_events WHERE profileId = :profileId ORDER BY occurredAt, eventId")
    abstract suspend fun getAllEvents(profileId: String): List<ReviewEvent>

    @Query("DELETE FROM sync_notes WHERE profileId = :profileId")
    abstract suspend fun clearProjection(profileId: String): Int

    @Query(
        "SELECT * FROM sync_review_events WHERE profileId = :profileId " +
            "AND noteId = :noteId ORDER BY occurredAt, eventId"
    )
    abstract fun observeHistory(profileId: String, noteId: String): Flow<List<ReviewEvent>>

    @Query(
        "SELECT * FROM sync_notes AS note WHERE note.profileId = :profileId " +
            "AND EXISTS (SELECT 1 FROM sync_review_events AS event " +
            "WHERE event.profileId = note.profileId AND event.noteId = note.noteId " +
            "AND event.eventType = 'review' AND event.occurredAt >= :startMillis " +
            "AND event.occurredAt < :endMillis) " +
            "ORDER BY note.updatedAt DESC, note.noteId"
    )
    abstract fun observeReviewedNotes(
        profileId: String,
        startMillis: Long,
        endMillis: Long
    ): Flow<List<Note>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertNoteIfMissing(note: Note): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertRevisions(revisions: List<NoteRevision>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertEvents(events: List<ReviewEvent>): List<Long>

    @Query(
        "UPDATE sync_notes SET title = :title, activeRevisionId = :activeRevisionId, " +
        "projectionState = :projectionState, reviewStage = :reviewStage, " +
            "nextReviewAt = :nextReviewAt, isReviewComplete = :isReviewComplete, " +
            "updatedAt = :updatedAt " +
            "WHERE profileId = :profileId AND noteId = :noteId"
    )
    protected abstract suspend fun updateProjection(
        profileId: String,
        noteId: String,
        title: String,
        activeRevisionId: String?,
        projectionState: String,
        reviewStage: Int,
        nextReviewAt: Long?,
        isReviewComplete: Boolean,
        updatedAt: Long
    ): Int

    @Transaction
    open suspend fun applyProjection(
        profileId: String,
        note: Note,
        revisions: List<NoteRevision>,
        events: List<ReviewEvent>
    ) {
        require(note.profileId == profileId)
        require(revisions.all { it.profileId == profileId && it.noteId == note.noteId })
        require(events.all { it.profileId == profileId && it.noteId == note.noteId })

        insertNoteIfMissing(note.copy(activeRevisionId = null))
        if (revisions.isNotEmpty()) insertRevisions(revisions)
        if (events.isNotEmpty()) insertEvents(events)
        check(
            updateProjection(
                profileId = profileId,
                noteId = note.noteId,
                title = note.title,
                activeRevisionId = note.activeRevisionId,
                projectionState = note.projectionState,
                reviewStage = note.reviewStage,
                nextReviewAt = note.nextReviewAt,
                isReviewComplete = note.isReviewComplete,
                updatedAt = note.updatedAt
            ) == 1
        ) { "Projection note is missing: ${note.noteId}" }
    }
}

@Dao
interface AssetDao {
    @Query(
        "SELECT * FROM sync_assets WHERE profileId = :profileId " +
            "AND sha256 = :sha256 AND extension = :extension LIMIT 1"
    )
    suspend fun find(profileId: String, sha256: String, extension: String): Asset?

    @Query("SELECT * FROM sync_assets WHERE profileId = :profileId ORDER BY createdAt, assetId")
    suspend fun getAll(profileId: String): List<Asset>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(asset: Asset): Long

    @Query(
        "UPDATE sync_assets SET referenceCount = referenceCount + :delta " +
            "WHERE profileId = :profileId AND assetId = :assetId " +
            "AND referenceCount + :delta >= 0"
    )
    suspend fun adjustReferenceCount(profileId: String, assetId: String, delta: Int): Int

    @Query("DELETE FROM sync_assets WHERE profileId = :profileId AND referenceCount = 0")
    suspend fun deleteUnreferenced(profileId: String): Int

    @Query(
        "DELETE FROM sync_assets WHERE profileId = :profileId " +
            "AND assetId = :assetId AND referenceCount = 0"
    )
    suspend fun deleteIfUnreferenced(profileId: String, assetId: String): Int

    @Query("UPDATE sync_assets SET referenceCount = 0 WHERE profileId = :profileId")
    suspend fun resetReferenceCounts(profileId: String): Int
}

@Dao
interface SyncStateDao {
    @Query(
        "SELECT * FROM sync_outbox WHERE profileId = :profileId " +
            "AND status IN (:statuses) " +
            "AND (status != 'RETRY' OR nextAttemptAt IS NULL OR nextAttemptAt <= :nowMillis) " +
            "ORDER BY createdAt, operationId"
    )
    suspend fun getOutbox(
        profileId: String,
        statuses: List<String>,
        nowMillis: Long
    ): List<SyncOutbox>

    @Query("SELECT * FROM sync_outbox WHERE profileId = :profileId ORDER BY createdAt, operationId")
    suspend fun getAllOutbox(profileId: String): List<SyncOutbox>

    @Query(
        "SELECT MIN(nextAttemptAt) FROM sync_outbox WHERE profileId = :profileId " +
            "AND status = 'RETRY' AND nextAttemptAt IS NOT NULL AND nextAttemptAt > :nowMillis"
    )
    suspend fun getDeferredRetryAt(profileId: String, nowMillis: Long): Long?

    @Query(
        "SELECT COUNT(*) FROM sync_outbox WHERE profileId = :profileId " +
            "AND status IN ('PENDING', 'RETRY', 'IN_FLIGHT')"
    )
    fun observePendingCount(profileId: String): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOutbox(operation: SyncOutbox)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOutbox(operations: List<SyncOutbox>)

    @Query(
        "SELECT * FROM sync_outbox WHERE profileId = :profileId " +
            "AND repositoryPath = :repositoryPath LIMIT 1"
    )
    suspend fun findOutboxByPath(profileId: String, repositoryPath: String): SyncOutbox?

    @Query("SELECT * FROM sync_outbox WHERE profileId = :profileId AND batchId = :batchId")
    suspend fun getBatch(profileId: String, batchId: String): List<SyncOutbox>

    @Query(
        "UPDATE sync_outbox SET batchId = :batchId, status = 'IN_FLIGHT', updatedAt = :updatedAt " +
            "WHERE profileId = :profileId AND operationId IN (:operationIds) " +
            "AND repositoryId = :repositoryId AND status IN ('PENDING', 'RETRY')"
    )
    suspend fun assignBatch(
        profileId: String,
        repositoryId: String,
        operationIds: List<String>,
        batchId: String,
        updatedAt: Long
    ): Int

    @Query(
        "UPDATE sync_outbox SET status = 'RETRY', attemptCount = attemptCount + 1, " +
            "nextAttemptAt = :nextAttemptAt, lastError = :lastError, updatedAt = :updatedAt " +
            "WHERE profileId = :profileId AND batchId = :batchId"
    )
    suspend fun markBatchForRetry(
        profileId: String,
        batchId: String,
        nextAttemptAt: Long,
        lastError: String,
        updatedAt: Long
    ): Int

    @Query(
        "UPDATE sync_outbox SET status = 'PAUSED', lastError = :lastError, " +
            "updatedAt = :updatedAt WHERE profileId = :profileId AND batchId = :batchId"
    )
    suspend fun pauseBatch(
        profileId: String,
        batchId: String,
        lastError: String,
        updatedAt: Long
    ): Int

    @Query("DELETE FROM sync_outbox WHERE profileId = :profileId AND batchId = :batchId")
    suspend fun acknowledgeBatch(profileId: String, batchId: String): Int

    @Query("DELETE FROM sync_outbox WHERE profileId = :profileId")
    suspend fun deleteOutboxForProfile(profileId: String): Int

    @Query("SELECT * FROM sync_checkpoints WHERE profileId = :profileId LIMIT 1")
    fun observeCheckpoint(profileId: String): Flow<SyncCheckpoint?>

    @Query("SELECT * FROM sync_checkpoints WHERE profileId = :profileId LIMIT 1")
    suspend fun getCheckpoint(profileId: String): SyncCheckpoint?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCheckpoint(checkpoint: SyncCheckpoint)
}
