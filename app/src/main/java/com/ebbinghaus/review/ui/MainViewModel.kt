package com.ebbinghaus.review.ui

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.room.withTransaction
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ebbinghaus.review.R
import com.ebbinghaus.review.data.AppDatabase
import com.ebbinghaus.review.data.ReviewItem
import com.ebbinghaus.review.data.ReviewLog
import com.ebbinghaus.review.data.repository.ImageRepository
import com.ebbinghaus.review.data.repository.ReviewRepository
import com.ebbinghaus.review.data.migration.LegacyDataGuard
import com.ebbinghaus.review.data.sync.CachedAsset
import com.ebbinghaus.review.data.sync.ImmutableAssetCache
import com.ebbinghaus.review.data.sync.ImmutableRevisionStore
import com.ebbinghaus.review.data.sync.LifecycleEventFactory
import com.ebbinghaus.review.data.sync.LocalSyncMutationService
import com.ebbinghaus.review.data.sync.Note
import com.ebbinghaus.review.data.sync.NoteRevisionFactory
import com.ebbinghaus.review.data.sync.Profile
import com.ebbinghaus.review.data.sync.ProfileTimeService
import com.ebbinghaus.review.data.sync.ImmutableSyncPayloadStore
import com.ebbinghaus.review.data.sync.ReviewEventFactory
import com.ebbinghaus.review.data.sync.projection.LifecycleEventProjector
import com.ebbinghaus.review.data.sync.projection.NoteDomainProjection
import com.ebbinghaus.review.data.sync.projection.NoteProjectionEngine
import com.ebbinghaus.review.data.sync.projection.ReviewEventProjector
import com.ebbinghaus.review.data.sync.projection.ReviewStreamState
import com.ebbinghaus.review.data.sync.projection.RevisionGraphProjector
import com.ebbinghaus.review.data.sync.protocol.MarkdownAssetResolver
import com.ebbinghaus.review.data.sync.protocol.NoteDocumentCodec
import com.ebbinghaus.review.ui.markdown.MarkdownNoteDetail
import com.ebbinghaus.review.ui.conflict.ConflictRevision
import com.ebbinghaus.review.ui.conflict.NoteConflictDetail
import com.ebbinghaus.review.ui.conflict.ReviewConflictOption
import com.ebbinghaus.review.utils.EbbinghausManager
import com.ebbinghaus.review.workers.ReviewWidgetProvider
import com.ebbinghaus.review.workers.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {

    // Ideally these should be injected via DI (Hilt/Koin), but for now we construct them here.
    private val database = AppDatabase.getDatabase(application)
    private val repository = ReviewRepository(
        database.reviewDao(),
        ImageRepository(application),
        LegacyDataGuard(application)
    )
    private val userDao = database.userDao()
    private val profileDao = database.profileDao()
    private val noteDao = database.noteProjectionDao()
    private val assetCache = ImmutableAssetCache(application, database.assetDao())
    private val revisionStore = ImmutableRevisionStore(application)
    private val markdownAssetResolver = MarkdownAssetResolver(assetCache)
    private val syncMutationService = LocalSyncMutationService(
        database,
        ImmutableSyncPayloadStore(application)
    )
    private val deviceId: String = application.getSharedPreferences("device_identity", Context.MODE_PRIVATE)
        .let { preferences ->
            preferences.getString("device_id", null) ?: UUID.randomUUID().toString().also {
                preferences.edit().putString("device_id", it).apply()
            }
        }

    val currentUser = userDao.getCurrentUser()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val currentProfile: StateFlow<Profile?> = profileDao.observeCurrentProfile()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val syncedNotes: StateFlow<List<Note>> = profileDao.observeCurrentProfile()
        .flatMapLatest { profile ->
            if (profile == null) flowOf(emptyList()) else noteDao.observeNotes(profile.profileId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dueSyncedNotes: StateFlow<List<Note>> = syncedNotes.map { notes ->
        val now = System.currentTimeMillis()
        notes.filter {
            it.projectionState == "ACTIVE" && !it.isReviewComplete &&
                it.nextReviewAt?.let { next -> next <= now } == true
        }.sortedWith(compareBy<Note> { it.nextReviewAt }.thenBy { it.noteId })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val todaySyncedNotes: StateFlow<List<Note>> = currentProfile.flatMapLatest { profile ->
        if (profile == null) {
            flowOf(emptyList())
        } else {
            val boundary = ProfileTimeService.forProfile(profile)
                .dayBoundary(System.currentTimeMillis())
            noteDao.observeReviewedNotes(
                profile.profileId,
                boundary.startInclusiveMillis,
                boundary.endExclusiveMillis
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val deletedSyncedNotes: StateFlow<List<Note>> = syncedNotes.map { notes ->
        notes.filter { it.projectionState == "DELETED" }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val conflictedSyncedNotes: StateFlow<List<Note>> = syncedNotes.map { notes ->
        notes.filter {
            it.projectionState in setOf(
                "CONTENT_CONFLICT",
                "REVIEW_CONFLICT",
                "DELETION_CONFLICT"
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dueItems: StateFlow<List<ReviewItem>> = repository.getDueItems(System.currentTimeMillis())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allActiveItems: StateFlow<List<ReviewItem>> = repository.allActiveItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val todayReviewedItems: StateFlow<List<ReviewItem>> = currentProfile.flatMapLatest { profile ->
        val now = System.currentTimeMillis()
        val boundary = if (profile == null) {
            val today = LocalDate.now(ZoneId.systemDefault())
            val start = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            start to today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } else {
            ProfileTimeService.forProfile(profile).dayBoundary(now).let {
                it.startInclusiveMillis to it.endExclusiveMillis
            }
        }
        repository.getTodayReviewedItems(boundary.first, boundary.second - 1, now)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 回收站列表
    val deletedItems: StateFlow<List<ReviewItem>> = repository.deletedItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // 初始化时清理超过 15 天的垃圾
        viewModelScope.launch {
            val threshold = System.currentTimeMillis() - 15 * 24 * 60 * 60 * 1000L
            repository.deleteExpiredItems(threshold)
        }
    }

    // === 辅助：显示 Toast ===
    private fun showToast(message: String) {
        Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
    }

    // === 业务动作 ===

    fun addItem(title: String, description: String, content: String, imagePaths: List<String>) {
        viewModelScope.launch {
            try {
                repository.addItem(title, description, content, imagePaths)
                updateWidget()
                loadHeatMapData()
                showToast(getApplication<Application>().getString(R.string.add_success))
            } catch (e: Exception) {
                e.printStackTrace()
                showToast("Failed to add item: ${e.message}")
            }
        }
    }

    suspend fun importMarkdownAsset(uri: Uri): CachedAsset = withContext(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        val mime = resolver.getType(uri)
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            ?: uri.lastPathSegment?.substringAfterLast('.', "jpg")
            ?: "jpg"
        val profile = currentProfile.value ?: error("Select or create a repository profile first")
        resolver.openInputStream(uri)?.use { input ->
            assetCache.install(profile.profileId, extension, input)
        } ?: error("Unable to open selected image")
    }

    fun saveMarkdownNote(markdownBody: String, onComplete: (Result<String>) -> Unit = {}) {
        viewModelScope.launch {
            val result = runCatching {
                val profile = currentProfile.value
                    ?: error("Select or create a repository profile first")
                val factory = revisionFactory(profile)
                val draft = withContext(Dispatchers.IO) { factory.create(markdownBody) }
                syncMutationService.saveRevision(draft)
                scheduleSync(profile.profileId)
                updateWidget()
                draft.note.noteId
            }
            onComplete(result)
        }
    }

    fun reviseMarkdownNote(
        noteId: String,
        markdownBody: String,
        onComplete: (Result<String>) -> Unit = {}
    ) {
        viewModelScope.launch {
            val result = runCatching {
                val profile = currentProfile.value ?: error("No active profile")
                val note = noteDao.getNote(profile.profileId, noteId) ?: error("Note not found")
                val activeId = note.activeRevisionId ?: error("Note has no active revision")
                val active = noteDao.getRevision(profile.profileId, activeId)
                    ?: error("Active revision is missing")
                val draft = withContext(Dispatchers.IO) {
                    revisionFactory(profile).restart(note, active, markdownBody)
                }
                syncMutationService.saveRevision(draft)
                scheduleSync(profile.profileId)
                updateWidget()
                draft.revision.revisionId
            }
            onComplete(result)
        }
    }

    suspend fun getMarkdownNote(noteId: String): MarkdownNoteDetail? = withContext(Dispatchers.IO) {
        val profile = currentProfile.value ?: return@withContext null
        val note = noteDao.getNote(profile.profileId, noteId) ?: return@withContext null
        val activeId = note.activeRevisionId ?: return@withContext null
        val revision = noteDao.getRevision(profile.profileId, activeId) ?: return@withContext null
        val parsed = NoteDocumentCodec.parse(
            java.io.File(revision.markdownCachePath).readBytes(),
            revision.repositoryPath
        )
        val document = parsed.value ?: return@withContext null
        val revisions = noteDao.getRevisions(profile.profileId, noteId)
        MarkdownNoteDetail(
            note = note,
            revision = revision,
            markdownBody = document.body,
            assets = markdownAssetResolver.resolve(profile.profileId, document.body),
            archivedRevisions = revisions.filter { it.revisionId != activeId }
        )
    }

    suspend fun getConflictDetail(noteId: String): NoteConflictDetail? = withContext(Dispatchers.IO) {
        val profile = currentProfile.value ?: return@withContext null
        val note = noteDao.getNote(profile.profileId, noteId) ?: return@withContext null
        val revisions = noteDao.getRevisions(profile.profileId, noteId)
        val revisionProjection = RevisionGraphProjector.project(revisions)
        val events = loadNoteEvents(profile.profileId, noteId, revisions)
        val domain = NoteProjectionEngine(profile, ProfileTimeService.forProfile(profile))
            .project(revisions, events)
        if (domain.state.name !in setOf(
                "CONTENT_CONFLICT",
                "REVIEW_CONFLICT",
                "DELETION_CONFLICT"
            )
        ) return@withContext null
        val branches = revisionProjection.leafRevisionIds.mapNotNull { revisionId ->
            revisions.firstOrNull { it.revisionId == revisionId }?.let { revision ->
                val parsed = NoteDocumentCodec.parse(
                    java.io.File(revision.markdownCachePath).readBytes(),
                    revision.repositoryPath
                ).value ?: return@let null
                ConflictRevision(
                    revision,
                    parsed.body,
                    markdownAssetResolver.resolve(profile.profileId, parsed.body)
                )
            }
        }.sortedBy { it.revision.revisionId }
        val activeRevisionId = revisionProjection.activeRevisionId
        val reviewProjection = activeRevisionId?.let(domain.reviewHistoryByRevision::get)
        val eventById = events.associateBy { it.eventId }
        val options = reviewProjection?.takeIf {
            it.state == ReviewStreamState.REVIEW_CONFLICT
        }?.leafEventIds.orEmpty().mapNotNull { eventId ->
            val payload = eventById[eventId]?.payloadJson
                ?.let { runCatching { com.google.gson.JsonParser.parseString(it).asJsonObject }.getOrNull() }
                ?: return@mapNotNull null
            val stage = payload.get("stage_after")?.asInt ?: return@mapNotNull null
            val next = payload.get("next_review_at")?.takeUnless { it.isJsonNull }
                ?.asString?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
            ReviewConflictOption(eventId, stage, next)
        }.sortedBy { it.eventId }
        NoteConflictDetail(note, domain.state.name, branches, options)
    }

    fun mergeMarkdownConflict(
        noteId: String,
        markdownBody: String,
        onComplete: (Result<Unit>) -> Unit = {}
    ) {
        viewModelScope.launch {
            val result = runCatching {
                val profile = currentProfile.value ?: error("No active profile")
                val note = noteDao.getNote(profile.profileId, noteId) ?: error("Note not found")
                val revisions = noteDao.getRevisions(profile.profileId, noteId)
                val revisionProjection = RevisionGraphProjector.project(revisions)
                require(revisionProjection.state.name == "CONTENT_CONFLICT")
                val leaves = revisionProjection.leafRevisionIds.map { revisionId ->
                    revisions.single { it.revisionId == revisionId }
                }
                val draft = withContext(Dispatchers.IO) {
                    revisionFactory(profile).merge(note, leaves, markdownBody)
                }
                val allRevisions = revisions + draft.revision
                val events = loadNoteEvents(profile.profileId, noteId, revisions)
                val projection = NoteProjectionEngine(profile, ProfileTimeService.forProfile(profile))
                    .project(allRevisions, events)
                syncMutationService.saveRevision(
                    draft.copy(note = projectNote(draft.note, projection, draft.revision.authoredAt))
                )
                scheduleSync(profile.profileId)
                updateWidget()
            }
            onComplete(result)
        }
    }

    fun resolveReviewConflict(
        noteId: String,
        selectedEventId: String,
        onComplete: (Result<Unit>) -> Unit = {}
    ) {
        viewModelScope.launch {
            val result = runCatching {
                val profile = currentProfile.value ?: error("No active profile")
                val note = noteDao.getNote(profile.profileId, noteId) ?: error("Note not found")
                val revisions = noteDao.getRevisions(profile.profileId, noteId)
                val revisionId = RevisionGraphProjector.project(revisions).activeRevisionId
                    ?: error("Content conflict must be resolved first")
                val revision = revisions.single { it.revisionId == revisionId }
                val events = loadNoteEvents(profile.profileId, noteId, revisions)
                val streamEvents = events.filter { it.streamId == "review:$revisionId" }
                val timeService = ProfileTimeService.forProfile(profile)
                val conflict = ReviewEventProjector(profile, timeService)
                    .project(revision, streamEvents)
                val selected = streamEvents.single { it.eventId == selectedEventId }
                val draft = ReviewEventFactory(profile, deviceId, timeService)
                    .createMerge(note, revision, conflict, selected)
                val projection = NoteProjectionEngine(profile, timeService)
                    .project(revisions, events + draft.event)
                val projectedNote = projectNote(note, projection, draft.event.occurredAt)
                syncMutationService.appendEvent(note, draft, projectedNote)
                scheduleSync(profile.profileId)
                updateWidget()
            }
            onComplete(result)
        }
    }

    fun resolveLifecycleConflict(
        noteId: String,
        keepDeleted: Boolean,
        targetRevisionId: String? = null,
        onComplete: (Result<Unit>) -> Unit = {}
    ) {
        viewModelScope.launch {
            val result = runCatching {
                val profile = currentProfile.value ?: error("No active profile")
                val note = noteDao.getNote(profile.profileId, noteId) ?: error("Note not found")
                val revisions = noteDao.getRevisions(profile.profileId, noteId)
                val events = loadNoteEvents(profile.profileId, noteId, revisions)
                val revisionProjection = RevisionGraphProjector.project(revisions)
                val lifecycle = LifecycleEventProjector.project(
                    profile.profileId,
                    noteId,
                    revisionProjection.leafRevisionIds,
                    revisions.mapTo(mutableSetOf()) { it.revisionId },
                    events.filter { it.streamId == "lifecycle:$noteId" }
                )
                val timeService = ProfileTimeService.forProfile(profile)
                val draft = LifecycleEventFactory(profile, deviceId, timeService).resolve(
                    note,
                    lifecycle,
                    keepDeleted,
                    targetRevisionId
                )
                val projection = NoteProjectionEngine(profile, timeService)
                    .project(revisions, events + draft.event)
                val projectedNote = projectNote(note, projection, draft.event.occurredAt)
                syncMutationService.appendEvent(note, draft, projectedNote)
                scheduleSync(profile.profileId)
                updateWidget()
            }
            onComplete(result)
        }
    }

    suspend fun resolveMarkdownAssets(markdownBody: String) = withContext(Dispatchers.IO) {
        val profile = currentProfile.value ?: return@withContext emptyList()
        markdownAssetResolver.resolve(profile.profileId, markdownBody)
    }

    private fun revisionFactory(profile: Profile) = NoteRevisionFactory(
        profile = profile,
        sourceDeviceId = deviceId,
        timeService = ProfileTimeService.forProfile(profile),
        revisionStore = revisionStore,
        assetResolver = markdownAssetResolver
    )

    private suspend fun loadNoteEvents(
        profileId: String,
        noteId: String,
        revisions: List<com.ebbinghaus.review.data.sync.NoteRevision>
    ) = revisions.flatMap { revision ->
        noteDao.getEvents(profileId, "review:${revision.revisionId}")
    } + noteDao.getEvents(profileId, "lifecycle:$noteId")

    private fun projectNote(
        note: Note,
        projection: NoteDomainProjection,
        updatedAt: Long
    ): Note = note.copy(
        activeRevisionId = projection.activeRevisionId,
        projectionState = projection.state.name,
        reviewStage = projection.activeSchedule?.stage ?: note.reviewStage,
        nextReviewAt = projection.activeSchedule?.nextReviewAt,
        isReviewComplete = projection.activeSchedule?.state == ReviewStreamState.COMPLETE,
        updatedAt = updatedAt
    )

    fun markAsReviewed(item: ReviewItem, remembered: Boolean) {
        viewModelScope.launch {
            repository.markAsReviewed(item, remembered)

            if (remembered) {
                showToast(getApplication<Application>().getString(R.string.review_success))
            } else {
                showToast(getApplication<Application>().getString(R.string.review_reset))
            }

            updateWidget()
            loadHeatMapData()
        }
    }

    fun markMarkdownNoteReviewed(
        noteId: String,
        remembered: Boolean,
        onComplete: (Result<Unit>) -> Unit = {}
    ) {
        viewModelScope.launch {
            val result = runCatching {
                val profile = currentProfile.value ?: error("No active profile")
                val note = noteDao.getNote(profile.profileId, noteId) ?: error("Note not found")
                require(note.projectionState == "ACTIVE") {
                    "Resolve note conflicts before reviewing"
                }
                val revisionId = note.activeRevisionId ?: error("No active revision")
                val revision = noteDao.getRevision(profile.profileId, revisionId)
                    ?: error("Active revision is missing")
                val streamId = "review:$revisionId"
                val existingEvents = noteDao.getEvents(profile.profileId, streamId)
                val timeService = ProfileTimeService.forProfile(profile)
                val currentSchedule = ReviewEventProjector(profile, timeService)
                    .project(revision, existingEvents)
                val draft = ReviewEventFactory(profile, deviceId, timeService)
                    .createReview(note, revision, currentSchedule, remembered)
                val updatedNote = note.copy(
                    reviewStage = draft.stageAfter,
                    nextReviewAt = draft.nextReviewAt,
                    isReviewComplete = draft.completed,
                    updatedAt = draft.event.occurredAt
                )
                syncMutationService.appendEvent(note, draft, updatedNote)
                scheduleSync(profile.profileId)
                updateWidget()
            }
            onComplete(result)
        }
    }

    fun moveMarkdownNoteToTrash(noteId: String, onComplete: (Result<Unit>) -> Unit = {}) {
        appendLifecycleEvent(noteId, restore = false, onComplete)
    }

    fun restoreMarkdownNote(noteId: String, onComplete: (Result<Unit>) -> Unit = {}) {
        appendLifecycleEvent(noteId, restore = true, onComplete)
    }

    private fun appendLifecycleEvent(
        noteId: String,
        restore: Boolean,
        onComplete: (Result<Unit>) -> Unit
    ) {
        viewModelScope.launch {
            val result = runCatching {
                val profile = currentProfile.value ?: error("No active profile")
                val note = noteDao.getNote(profile.profileId, noteId) ?: error("Note not found")
                val revisions = noteDao.getRevisions(profile.profileId, noteId)
                val lifecycleEvents = noteDao.getEvents(profile.profileId, "lifecycle:$noteId")
                val revisionProjection = com.ebbinghaus.review.data.sync.projection.RevisionGraphProjector
                    .project(revisions)
                val lifecycle = LifecycleEventProjector.project(
                    profile.profileId,
                    noteId,
                    revisionProjection.leafRevisionIds,
                    revisions.mapTo(mutableSetOf()) { it.revisionId },
                    lifecycleEvents
                )
                val timeService = ProfileTimeService.forProfile(profile)
                val factory = LifecycleEventFactory(profile, deviceId, timeService)
                val draft = if (restore) {
                    val target = revisionProjection.activeRevisionId
                        ?: revisionProjection.leafRevisionIds.singleOrNull()
                        ?: error("Resolve content conflict before restoring")
                    factory.restore(note, target, lifecycle)
                } else {
                    factory.delete(note, revisionProjection.leafRevisionIds, lifecycle)
                }
                val allEvents = revisions.flatMap { revision ->
                    noteDao.getEvents(profile.profileId, "review:${revision.revisionId}")
                } + lifecycleEvents + draft.event
                val projection = NoteProjectionEngine(profile, timeService).project(revisions, allEvents)
                val projectedNote = note.copy(
                    activeRevisionId = projection.activeRevisionId,
                    projectionState = projection.state.name,
                    reviewStage = projection.activeSchedule?.stage ?: note.reviewStage,
                    nextReviewAt = projection.activeSchedule?.nextReviewAt,
                    isReviewComplete = projection.activeSchedule?.state ==
                        com.ebbinghaus.review.data.sync.projection.ReviewStreamState.COMPLETE,
                    updatedAt = draft.event.occurredAt
                )
                syncMutationService.appendEvent(note, draft, projectedNote)
                scheduleSync(profile.profileId)
                updateWidget()
            }
            onComplete(result)
        }
    }

    // 软删除
    fun moveToTrash(item: ReviewItem) {
        viewModelScope.launch {
            repository.moveToTrash(item)
            updateWidget()
            loadHeatMapData()
            showToast(getApplication<Application>().getString(R.string.moved_to_trash))
        }
    }

    // 从回收站恢复
    fun restoreFromTrash(item: ReviewItem) {
        viewModelScope.launch {
            repository.restoreFromTrash(item)
            updateWidget()
            loadHeatMapData()
            showToast(getApplication<Application>().getString(R.string.restored_from_trash))
        }
    }

    // 彻底删除
    fun deletePermanently(item: ReviewItem) {
        viewModelScope.launch {
            repository.deletePermanently(item)
            showToast(getApplication<Application>().getString(R.string.deleted_permanently))
        }
    }

    suspend fun getItemLogs(itemId: Long): List<ReviewLog> {
        return repository.getItemLogs(itemId)
    }

    fun snoozeItem(item: ReviewItem) {
        viewModelScope.launch {
            val snoozedTime = System.currentTimeMillis() + 10 * 60 * 1000
            repository.updateItem(item.copy(nextReviewTime = snoozedTime))
            showToast(getApplication<Application>().getString(R.string.snoozed))
        }
    }
    
    suspend fun getItemById(id: Long): ReviewItem? {
        return repository.getById(id)
    }

    // === 日历功能区 ===
    
    private val _historyItems = kotlinx.coroutines.flow.MutableStateFlow<List<ReviewItem>>(emptyList())
    val historyItems: StateFlow<List<ReviewItem>> = _historyItems

    private val _syncHistoryNotes = kotlinx.coroutines.flow.MutableStateFlow<List<Note>>(emptyList())
    val syncHistoryNotes: StateFlow<List<Note>> = _syncHistoryNotes

    private val _hasDataDates = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    val hasDataDates: StateFlow<Set<String>> = _hasDataDates

    fun loadHeatMapData() {
        viewModelScope.launch(Dispatchers.IO) {
            // Optimization: Use specific query to avoid loading heavy content/images
            val minimalItems = repository.getItemsForHeatMap()

            val dateSet = mutableSetOf<String>()
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            val profile = currentProfile.value
            val legacyZone = profile?.let { ProfileTimeService.forProfile(it).zoneId }
                ?: ZoneId.systemDefault()

            minimalItems.forEach { item ->
                var currentStage = item.stage
                var currentTime = item.nextReviewTime
                // Only calculate if the item is not finished yet, as finished items are not scheduled
                // However, the query already filters isFinished=0.

                // Note: Logic logic was: for i in 0..15.
                // We keep the same logic.
                for (i in 0..15) {
                    val date = Instant.ofEpochMilli(currentTime).atZone(legacyZone).toLocalDate()
                    dateSet.add(date.format(formatter))
                    val nextTime = EbbinghausManager.calculateNextReviewTime(currentStage, currentTime)
                    if (nextTime == -1L) break
                    currentTime = nextTime
                    currentStage++
                }
            }
            if (profile != null) {
                val timeService = ProfileTimeService.forProfile(profile)
                val intervals = com.google.gson.JsonParser.parseString(profile.algorithmParametersJson)
                    .asJsonObject.getAsJsonArray("interval_days").map { it.asInt }
                syncedNotes.value.filter {
                    it.projectionState == "ACTIVE" && !it.isReviewComplete && it.nextReviewAt != null
                }.forEach { note ->
                    var stage = note.reviewStage
                    var next = checkNotNull(note.nextReviewAt)
                    while (stage < intervals.size) {
                        dateSet += timeService.calendarDate(next).format(formatter)
                        stage += 1
                        if (stage >= intervals.size) break
                        next = timeService.reviewAtStartOfDay(next, intervals[stage])
                    }
                }
            }
            withContext(Dispatchers.Main) {
                _hasDataDates.value = dateSet
            }
        }
    }

    fun selectDate(year: Int, month: Int, day: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val date = LocalDate.of(year, month, day)
            val profile = currentProfile.value
            val legacyZone = profile?.let { ProfileTimeService.forProfile(it).zoneId }
                ?: ZoneId.systemDefault()
            val targetStart = date.atStartOfDay(legacyZone).toInstant().toEpochMilli()
            val end = date.plusDays(1).atStartOfDay(legacyZone).toInstant().toEpochMilli() - 1

            // For detailed history, we still need full items to display
            val activeItems = repository.getAllItemsSync().filter { !it.isFinished }
            val planItems = mutableListOf<ReviewItem>()

            activeItems.forEach { item ->
                if (isItemScheduledForDate(item, targetStart, end)) {
                    planItems.add(item)
                }
            }
            val syncItems = if (profile == null) {
                emptyList()
            } else {
                val timeService = ProfileTimeService.forProfile(profile)
                val boundary = timeService.dayBoundary(date)
                val intervals = com.google.gson.JsonParser.parseString(profile.algorithmParametersJson)
                    .asJsonObject.getAsJsonArray("interval_days").map { it.asInt }
                syncedNotes.value.filter { note ->
                    isSyncedNoteScheduledForDate(note, boundary, intervals, timeService)
                }
            }
            withContext(Dispatchers.Main) {
                _historyItems.value = planItems
                _syncHistoryNotes.value = syncItems
            }
        }
    }

    private fun isItemScheduledForDate(item: ReviewItem, start: Long, end: Long): Boolean {
        var currentStage = item.stage
        var currentTime = item.nextReviewTime
        for (i in 0..15) {
            if (currentTime >= start && currentTime <= end) return true
            if (currentTime > end) return false
            val nextTime = EbbinghausManager.calculateNextReviewTime(currentStage, currentTime)
            if (nextTime == -1L) break
            currentTime = nextTime
            currentStage++
        }
        return false
    }

    private fun isSyncedNoteScheduledForDate(
        note: Note,
        boundary: com.ebbinghaus.review.data.sync.ProfileDayBoundary,
        intervals: List<Int>,
        timeService: ProfileTimeService
    ): Boolean {
        if (note.projectionState != "ACTIVE" || note.isReviewComplete) return false
        var stage = note.reviewStage
        var next = note.nextReviewAt ?: return false
        while (stage < intervals.size) {
            if (next in boundary.startInclusiveMillis until boundary.endExclusiveMillis) return true
            if (next >= boundary.endExclusiveMillis) return false
            stage += 1
            if (stage >= intervals.size) break
            next = timeService.reviewAtStartOfDay(next, intervals[stage])
        }
        return false
    }

    private fun updateWidget() {
        val context = getApplication<Application>()
        val intent = Intent(context, ReviewWidgetProvider::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
        }
        val ids = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, ReviewWidgetProvider::class.java))
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        context.sendBroadcast(intent)
    }

    fun requestProfileSync(manual: Boolean = true) {
        currentProfile.value?.let { scheduleSync(it.profileId, manual) }
    }

    private fun scheduleSync(profileId: String, manual: Boolean = false) {
        viewModelScope.launch {
            val remote = database.remoteRepositoryDao().getForProfile(profileId) ?: return@launch
            if (manual || remote.autoSync) {
                SyncScheduler.enqueue(getApplication(), profileId, remote.wifiOnly, manual)
            }
        }
    }
}
