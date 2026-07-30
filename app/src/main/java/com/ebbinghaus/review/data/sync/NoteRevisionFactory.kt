package com.ebbinghaus.review.data.sync

import com.ebbinghaus.review.data.sync.protocol.MarkdownAssetReference
import com.ebbinghaus.review.data.sync.protocol.MarkdownAssetResolver
import com.ebbinghaus.review.data.sync.protocol.MarkdownContent
import com.ebbinghaus.review.data.sync.protocol.NOTE_SCHEMA
import com.ebbinghaus.review.data.sync.protocol.NoteDocumentCodec
import com.ebbinghaus.review.data.sync.protocol.NoteFrontMatter
import java.util.UUID

fun interface UuidSource {
    fun next(): String
}

data class RevisionAssetPublication(
    val reference: MarkdownAssetReference,
    val asset: CachedAsset,
    val repositoryPath: String
)

data class NoteRevisionDraft(
    val note: Note,
    val revision: NoteRevision,
    val documentBytes: ByteArray,
    val assets: List<RevisionAssetPublication>
)

class NoteRevisionFactory(
    private val profile: Profile,
    private val sourceDeviceId: String,
    private val timeService: ProfileTimeService,
    private val revisionStore: ImmutableRevisionStore,
    private val assetResolver: MarkdownAssetResolver,
    private val uuidSource: UuidSource = UuidSource { UUID.randomUUID().toString() }
) {
    init {
        require(profile.timezone == timeService.timezoneId)
        UUID.fromString(sourceDeviceId)
    }

    suspend fun create(markdownBody: String): NoteRevisionDraft {
        val now = timeService.now().toEpochMilli()
        return buildDraft(
            noteId = uuidSource.next(),
            priorNote = null,
            parents = emptyList(),
            revisionKind = "create",
            markdownBody = markdownBody,
            authoredAt = now
        )
    }

    suspend fun restart(
        note: Note,
        activeRevision: NoteRevision,
        markdownBody: String
    ): NoteRevisionDraft {
        require(note.profileId == profile.profileId)
        require(activeRevision.profileId == profile.profileId && activeRevision.noteId == note.noteId)
        require(note.activeRevisionId == activeRevision.revisionId) {
            "Only the uniquely active revision can be restarted"
        }
        return buildDraft(
            noteId = note.noteId,
            priorNote = note,
            parents = listOf(activeRevision),
            revisionKind = "restart",
            markdownBody = markdownBody,
            authoredAt = timeService.now().toEpochMilli()
        )
    }

    suspend fun merge(
        note: Note,
        conflictingLeaves: List<NoteRevision>,
        markdownBody: String
    ): NoteRevisionDraft {
        require(note.profileId == profile.profileId)
        require(conflictingLeaves.size >= 2) { "A merge requires at least two leaves" }
        require(conflictingLeaves.map { it.revisionId }.distinct().size == conflictingLeaves.size)
        require(conflictingLeaves.all { it.profileId == profile.profileId && it.noteId == note.noteId })
        return buildDraft(
            noteId = note.noteId,
            priorNote = note,
            parents = conflictingLeaves.sortedBy { it.revisionId },
            revisionKind = "merge",
            markdownBody = markdownBody,
            authoredAt = timeService.now().toEpochMilli()
        )
    }

    private suspend fun buildDraft(
        noteId: String,
        priorNote: Note?,
        parents: List<NoteRevision>,
        revisionKind: String,
        markdownBody: String,
        authoredAt: Long
    ): NoteRevisionDraft {
        val revisionId = uuidSource.next()
        val authoredAtText = timeService.formatOffsetDateTime(authoredAt)
        val bytes = NoteDocumentCodec.serialize(
            NoteFrontMatter(
                schema = NOTE_SCHEMA,
                noteId = noteId,
                revisionId = revisionId,
                parentRevisionIds = parents.map { it.revisionId },
                revisionKind = revisionKind,
                authoredAt = authoredAtText,
                learningStartedAt = authoredAtText,
                sourceDeviceId = sourceDeviceId,
                contentSha256 = ""
            ),
            markdownBody
        )
        val repositoryPath = timeService.repositoryPath(
            RepositoryDateCategory.NOTES,
            "$revisionId.md",
            authoredAt
        )
        val parsed = NoteDocumentCodec.parse(bytes, repositoryPath)
        check(parsed.isValid) {
            parsed.issues.joinToString { "${it.code}: ${it.message}" }
        }
        val document = checkNotNull(parsed.value)
        val cacheFile = revisionStore.install(profile.profileId, revisionId, bytes)
        val resolvedAssets = assetResolver.resolve(profile.profileId, document.body)
        val missing = resolvedAssets.filter { it.cachedAsset == null }
        require(missing.isEmpty()) {
            "Missing cached assets: ${missing.joinToString { it.reference.destination }}"
        }
        val assets = resolvedAssets.map { resolved ->
            RevisionAssetPublication(
                reference = resolved.reference,
                asset = checkNotNull(resolved.cachedAsset),
                repositoryPath = timeService.repositoryPath(
                    RepositoryDateCategory.ASSETS,
                    "${resolved.reference.sha256}.${resolved.reference.extension}",
                    authoredAt
                )
            )
        }.distinctBy { it.repositoryPath }

        val revision = NoteRevision(
            revisionId = revisionId,
            profileId = profile.profileId,
            noteId = noteId,
            parentRevisionIdsJson = parents.joinToString(prefix = "[", postfix = "]") {
                "\"${it.revisionId}\""
            },
            revisionKind = revisionKind,
            authoredAt = authoredAt,
            learningStartedAt = authoredAt,
            sourceDeviceId = sourceDeviceId,
            contentSha256 = document.frontMatter.contentSha256,
            markdownCachePath = cacheFile.absolutePath,
            repositoryPath = repositoryPath,
            createdAt = authoredAt
        )
        val note = Note(
            noteId = noteId,
            profileId = profile.profileId,
            title = MarkdownContent.titleProjection(document.body),
            activeRevisionId = revisionId,
            projectionState = "ACTIVE",
            reviewStage = 0,
            nextReviewAt = authoredAt,
            isReviewComplete = false,
            createdAt = priorNote?.createdAt ?: authoredAt,
            updatedAt = authoredAt
        )
        return NoteRevisionDraft(note, revision, bytes, assets)
    }
}
