package com.ebbinghaus.review.data.sync.protocol

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

data class NoteFrontMatter(
    val schema: String = NOTE_SCHEMA,
    val noteId: String,
    val revisionId: String,
    val parentRevisionIds: List<String>,
    val revisionKind: String,
    val authoredAt: String,
    val learningStartedAt: String,
    val sourceDeviceId: String,
    val contentSha256: String
)

data class ParsedMarkdownRevision(
    val frontMatter: NoteFrontMatter,
    val bodyBytes: ByteArray,
    val originalBytes: ByteArray
) {
    val body: String get() = bodyBytes.toString(Charsets.UTF_8)
}

data class EventAlgorithm(val id: String, val version: Int)

data class ParsedRepositoryEvent(
    val schema: String,
    val eventId: String,
    val streamId: String,
    val parentEventIds: List<String>,
    val eventType: String,
    val occurredAt: String,
    val sourceDeviceId: String,
    val algorithm: EventAlgorithm,
    val payload: JsonObject,
    val originalBytes: ByteArray
)

data class ValidationIssue(
    val code: String,
    val path: String?,
    val message: String
)

data class DocumentValidation<T>(
    val value: T?,
    val issues: List<ValidationIssue>
) {
    val isValid: Boolean get() = value != null && issues.isEmpty()
}

object NoteDocumentCodec {
    private val yaml = Yaml()

    fun parse(bytes: ByteArray, repositoryPath: String? = null): DocumentValidation<ParsedMarkdownRevision> {
        val issues = mutableListOf<ValidationIssue>()
        val text = decodeRepositoryText(bytes, repositoryPath, issues)
            ?: return DocumentValidation(null, issues)
        if (!text.startsWith(FRONT_MATTER_OPEN)) {
            issues += issue("note.front_matter", repositoryPath, "Markdown must start with ---")
            return DocumentValidation(null, issues)
        }
        val closingIndex = text.indexOf(FRONT_MATTER_CLOSE, FRONT_MATTER_OPEN.length)
        if (closingIndex < 0) {
            issues += issue("note.front_matter", repositoryPath, "Markdown front matter is not closed")
            return DocumentValidation(null, issues)
        }

        val yamlText = text.substring(FRONT_MATTER_OPEN.length, closingIndex)
        val bodyStart = closingIndex + FRONT_MATTER_CLOSE.length
        val bodyBytes = text.substring(bodyStart).toByteArray(Charsets.UTF_8)
        val values = parseFrontMatter(yamlText, repositoryPath, issues)
            ?: return DocumentValidation(null, issues)
        if (values.keys != NOTE_KEYS) {
            val missing = NOTE_KEYS - values.keys
            val unknown = values.keys - NOTE_KEYS
            if (missing.isNotEmpty()) issues += issue(
                "note.required",
                repositoryPath,
                "Missing front matter fields: ${missing.sorted().joinToString()}"
            )
            if (unknown.isNotEmpty()) issues += issue(
                "note.additional_properties",
                repositoryPath,
                "Unknown front matter fields: ${unknown.sorted().joinToString()}"
            )
        }

        val frontMatter = runCatching {
            NoteFrontMatter(
                schema = values.scalar("schema"),
                noteId = values.scalar("note_id"),
                revisionId = values.scalar("revision_id"),
                parentRevisionIds = values.sequence("parent_revision_ids"),
                revisionKind = values.scalar("revision_kind"),
                authoredAt = values.scalar("authored_at"),
                learningStartedAt = values.scalar("learning_started_at"),
                sourceDeviceId = values.scalar("source_device_id"),
                contentSha256 = values.scalar("content_sha256")
            )
        }.getOrElse {
            issues += issue("note.type", repositoryPath, it.message ?: "Invalid front matter value")
            return DocumentValidation(null, issues)
        }

        validateFrontMatter(frontMatter, repositoryPath, bodyBytes, issues)
        return DocumentValidation(
            ParsedMarkdownRevision(frontMatter, bodyBytes, bytes.copyOf()),
            issues
        )
    }

    fun serialize(frontMatter: NoteFrontMatter, markdownBody: String): ByteArray {
        val normalizedBody = normalizeMarkdownBody(markdownBody)
        val bodyBytes = normalizedBody.toByteArray(Charsets.UTF_8)
        val metadata = frontMatter.copy(contentSha256 = sha256(bodyBytes))
        val yamlText = buildString {
            append("schema: ").append(metadata.schema).append('\n')
            append("note_id: ").append(metadata.noteId).append('\n')
            append("revision_id: ").append(metadata.revisionId).append('\n')
            if (metadata.parentRevisionIds.isEmpty()) {
                append("parent_revision_ids: []\n")
            } else {
                append("parent_revision_ids:\n")
                metadata.parentRevisionIds.forEach { append("  - ").append(it).append('\n') }
            }
            append("revision_kind: ").append(metadata.revisionKind).append('\n')
            append("authored_at: ").append(metadata.authoredAt).append('\n')
            append("learning_started_at: ").append(metadata.learningStartedAt).append('\n')
            append("source_device_id: ").append(metadata.sourceDeviceId).append('\n')
            append("content_sha256: ").append(metadata.contentSha256).append('\n')
        }
        return ("---\n$yamlText---\n$normalizedBody").toByteArray(Charsets.UTF_8)
    }

    private fun validateFrontMatter(
        metadata: NoteFrontMatter,
        repositoryPath: String?,
        bodyBytes: ByteArray,
        issues: MutableList<ValidationIssue>
    ) {
        if (metadata.schema != NOTE_SCHEMA) issues += issue(
            "note.schema",
            repositoryPath,
            "Unsupported note schema: ${metadata.schema}"
        )
        validateUuid(metadata.noteId, "note.note_id", repositoryPath, issues)
        validateUuid(metadata.revisionId, "note.revision_id", repositoryPath, issues)
        validateUuid(metadata.sourceDeviceId, "note.source_device_id", repositoryPath, issues)
        metadata.parentRevisionIds.forEach {
            validateUuid(it, "note.parent_revision_id", repositoryPath, issues)
        }
        if (metadata.parentRevisionIds.distinct().size != metadata.parentRevisionIds.size) {
            issues += issue("note.parents_unique", repositoryPath, "Parent revision IDs must be unique")
        }
        if (metadata.revisionKind !in REVISION_KINDS) issues += issue(
            "note.revision_kind",
            repositoryPath,
            "Unsupported revision kind: ${metadata.revisionKind}"
        )
        validateOffsetDateTime(metadata.authoredAt, "note.authored_at", repositoryPath, issues)
        validateOffsetDateTime(
            metadata.learningStartedAt,
            "note.learning_started_at",
            repositoryPath,
            issues
        )
        if (!SHA256_REGEX.matches(metadata.contentSha256)) issues += issue(
            "note.content_sha256",
            repositoryPath,
            "content_sha256 must be lowercase SHA-256"
        ) else if (sha256(bodyBytes) != metadata.contentSha256) issues += issue(
            "note.body_hash",
            repositoryPath,
            "Markdown body hash does not match content_sha256"
        )
        if (repositoryPath != null) {
            val match = NOTE_PATH_REGEX.matchEntire(repositoryPath.replace('\\', '/'))
            if (match == null || match.groupValues[2] != metadata.revisionId) {
                issues += issue("note.path", repositoryPath, "Note path must use its revision UUID")
            } else if (runCatching { OffsetDateTime.parse(metadata.authoredAt).toLocalDate().toString() }
                    .getOrNull() != match.groupValues[1]) {
                issues += issue("note.path_date", repositoryPath, "Note path date must match authored_at")
            }
        }
    }

    private fun parseFrontMatter(
        yamlText: String,
        path: String?,
        issues: MutableList<ValidationIssue>
    ): Map<String, Node>? = try {
        val root = yaml.compose(StringReader(yamlText)) as? MappingNode
            ?: throw IllegalArgumentException("Front matter must be a mapping")
        buildMap {
            root.value.forEach { tuple ->
                val key = (tuple.keyNode as? ScalarNode)?.value
                    ?: throw IllegalArgumentException("Front matter keys must be strings")
                if (put(key, tuple.valueNode) != null) {
                    throw IllegalArgumentException("Duplicate front matter key: $key")
                }
            }
        }
    } catch (error: Exception) {
        issues += issue("note.yaml", path, error.message ?: "Invalid YAML front matter")
        null
    }

    private fun Map<String, Node>.scalar(key: String): String =
        (getValue(key) as? ScalarNode)?.value
            ?: throw IllegalArgumentException("$key must be a string")

    private fun Map<String, Node>.sequence(key: String): List<String> =
        (getValue(key) as? SequenceNode)?.value?.map {
            (it as? ScalarNode)?.value
                ?: throw IllegalArgumentException("$key entries must be strings")
        } ?: throw IllegalArgumentException("$key must be an array")
}

object EventDocumentCodec {
    private val gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()

    fun parse(bytes: ByteArray, repositoryPath: String? = null): DocumentValidation<ParsedRepositoryEvent> {
        val issues = mutableListOf<ValidationIssue>()
        val text = decodeRepositoryText(bytes, repositoryPath, issues)
            ?: return DocumentValidation(null, issues)
        val json = try {
            JsonParser.parseString(text).asJsonObject
        } catch (error: Exception) {
            issues += issue("event.json", repositoryPath, error.message ?: "Invalid event JSON")
            return DocumentValidation(null, issues)
        }
        val keys = json.keySet()
        if (keys != EVENT_KEYS) {
            val missing = EVENT_KEYS - keys
            val unknown = keys - EVENT_KEYS
            if (missing.isNotEmpty()) issues += issue(
                "event.required",
                repositoryPath,
                "Missing event fields: ${missing.sorted().joinToString()}"
            )
            if (unknown.isNotEmpty()) issues += issue(
                "event.additional_properties",
                repositoryPath,
                "Unknown event fields: ${unknown.sorted().joinToString()}"
            )
        }
        val parsed = runCatching {
            val algorithm = json.getAsJsonObject("algorithm")
            require(algorithm.keySet() == setOf("id", "version")) {
                "algorithm must contain only id and version"
            }
            ParsedRepositoryEvent(
                schema = json.requiredString("schema"),
                eventId = json.requiredString("event_id"),
                streamId = json.requiredString("stream_id"),
                parentEventIds = json.requiredStringArray("parent_event_ids"),
                eventType = json.requiredString("event_type"),
                occurredAt = json.requiredString("occurred_at"),
                sourceDeviceId = json.requiredString("source_device_id"),
                algorithm = EventAlgorithm(
                    algorithm.requiredString("id"),
                    algorithm.get("version").asInt
                ),
                payload = json.getAsJsonObject("payload"),
                originalBytes = bytes.copyOf()
            )
        }.getOrElse {
            issues += issue("event.type", repositoryPath, it.message ?: "Invalid event field")
            return DocumentValidation(null, issues)
        }
        validateEvent(parsed, repositoryPath, issues)
        return DocumentValidation(parsed, issues)
    }

    fun serialize(event: ParsedRepositoryEvent): ByteArray {
        val json = JsonObject().apply {
            addProperty("schema", event.schema)
            addProperty("event_id", event.eventId)
            addProperty("stream_id", event.streamId)
            add("parent_event_ids", gson.toJsonTree(event.parentEventIds))
            addProperty("event_type", event.eventType)
            addProperty("occurred_at", event.occurredAt)
            addProperty("source_device_id", event.sourceDeviceId)
            add("algorithm", JsonObject().apply {
                addProperty("id", event.algorithm.id)
                addProperty("version", event.algorithm.version)
            })
            add("payload", event.payload.deepCopy())
        }
        return (gson.toJson(json) + "\n").toByteArray(Charsets.UTF_8)
    }

    private fun validateEvent(
        event: ParsedRepositoryEvent,
        repositoryPath: String?,
        issues: MutableList<ValidationIssue>
    ) {
        if (event.schema != EVENT_SCHEMA) issues += issue(
            "event.schema",
            repositoryPath,
            "Unsupported event schema: ${event.schema}"
        )
        validateUuid(event.eventId, "event.event_id", repositoryPath, issues)
        validateUuid(event.sourceDeviceId, "event.source_device_id", repositoryPath, issues)
        event.parentEventIds.forEach {
            validateUuid(it, "event.parent_event_id", repositoryPath, issues)
        }
        if (event.parentEventIds.distinct().size != event.parentEventIds.size) {
            issues += issue("event.parents_unique", repositoryPath, "Parent event IDs must be unique")
        }
        if (!STREAM_ID_REGEX.matches(event.streamId)) issues += issue(
            "event.stream_id",
            repositoryPath,
            "Invalid causal stream ID"
        )
        if (event.eventType !in EVENT_TYPES) issues += issue(
            "event.event_type",
            repositoryPath,
            "Unsupported event type: ${event.eventType}"
        )
        validateOffsetDateTime(event.occurredAt, "event.occurred_at", repositoryPath, issues)
        if (event.algorithm.id != ALGORITHM_ID || event.algorithm.version != ALGORITHM_VERSION) {
            issues += issue(
                "event.algorithm",
                repositoryPath,
                "Unsupported review algorithm ${event.algorithm.id}/${event.algorithm.version}"
            )
        }
        if (repositoryPath != null) {
            val match = EVENT_PATH_REGEX.matchEntire(repositoryPath.replace('\\', '/'))
            if (match == null || match.groupValues[2] != event.eventId) {
                issues += issue("event.path", repositoryPath, "Event path must use its event UUID")
            } else if (runCatching { OffsetDateTime.parse(event.occurredAt).toLocalDate().toString() }
                    .getOrNull() != match.groupValues[1]) {
                issues += issue("event.path_date", repositoryPath, "Event path date must match occurred_at")
            }
        }
    }
}

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }

fun normalizeMarkdownBody(markdown: String): String {
    var normalized = markdown.replace("\r\n", "\n").replace('\r', '\n')
    if (!normalized.startsWith('\n')) normalized = "\n$normalized"
    if (!normalized.endsWith('\n')) normalized += "\n"
    return normalized
}

private fun decodeRepositoryText(
    bytes: ByteArray,
    path: String?,
    issues: MutableList<ValidationIssue>
): String? {
    if (bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() &&
        bytes[2] == 0xbf.toByte()
    ) {
        issues += issue("text.bom", path, "UTF-8 BOM is not allowed")
        return null
    }
    if (bytes.contains('\r'.code.toByte())) {
        issues += issue("text.line_endings", path, "Repository text must use LF line endings")
    }
    if (bytes.isEmpty() || bytes.last() != '\n'.code.toByte()) {
        issues += issue("text.trailing_lf", path, "Repository text must end with LF")
    }
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: Exception) {
        issues += issue("text.utf8", path, "Repository text must be valid UTF-8")
        null
    }
}

private fun validateUuid(
    value: String,
    code: String,
    path: String?,
    issues: MutableList<ValidationIssue>
) {
    if (runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false).not()) {
        issues += issue(code, path, "Value must be a canonical lowercase UUID: $value")
    }
}

private fun validateOffsetDateTime(
    value: String,
    code: String,
    path: String?,
    issues: MutableList<ValidationIssue>
) {
    if (runCatching { OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME) }.isFailure) {
        issues += issue(code, path, "Value must be an offset date-time: $value")
    }
}

private fun issue(code: String, path: String?, message: String) =
    ValidationIssue(code, path, message)

private fun JsonObject.requiredString(key: String): String = get(key)?.takeIf {
    it.isJsonPrimitive && it.asJsonPrimitive.isString
}
    ?.asString ?: throw IllegalArgumentException("$key must be a string")

private fun JsonObject.requiredStringArray(key: String): List<String> =
    getAsJsonArray(key)?.map {
        if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString) {
            throw IllegalArgumentException("$key entries must be strings")
        }
        it.asString
    } ?: throw IllegalArgumentException("$key must be an array")

const val NOTE_SCHEMA = "ebbinghaus-note/v1"
const val EVENT_SCHEMA = "ebbinghaus-event/v1"
const val ALGORITHM_ID = "ebbinghaus-8-stage"
const val ALGORITHM_VERSION = 1
private const val FRONT_MATTER_OPEN = "---\n"
private const val FRONT_MATTER_CLOSE = "\n---\n"
private val NOTE_KEYS = setOf(
    "schema",
    "note_id",
    "revision_id",
    "parent_revision_ids",
    "revision_kind",
    "authored_at",
    "learning_started_at",
    "source_device_id",
    "content_sha256"
)
private val EVENT_KEYS = setOf(
    "schema",
    "event_id",
    "stream_id",
    "parent_event_ids",
    "event_type",
    "occurred_at",
    "source_device_id",
    "algorithm",
    "payload"
)
private val REVISION_KINDS = setOf("create", "restart", "merge")
private val EVENT_TYPES = setOf("review", "review_merge", "delete", "restore", "lifecycle_resolve")
private val SHA256_REGEX = Regex("[0-9a-f]{64}")
private val STREAM_ID_REGEX = Regex("(review|lifecycle):[0-9a-f-]{36}")
private val NOTE_PATH_REGEX = Regex("(\\d{4}-\\d{2}-\\d{2})/notes/([0-9a-f-]{36})\\.md")
private val EVENT_PATH_REGEX = Regex("(\\d{4}-\\d{2}-\\d{2})/events/([0-9a-f-]{36})\\.json")
