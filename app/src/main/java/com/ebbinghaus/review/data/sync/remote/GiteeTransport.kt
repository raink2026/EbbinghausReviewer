package com.ebbinghaus.review.data.sync.remote

import com.ebbinghaus.review.data.security.AndroidSecureLogger
import com.ebbinghaus.review.data.security.CredentialInvalidationHandler
import com.ebbinghaus.review.data.security.CredentialStore
import com.ebbinghaus.review.data.security.SecretRedactor
import com.ebbinghaus.review.data.security.SecureLogger
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

data class GiteeRepositoryInfo(
    val owner: String,
    val name: String,
    val defaultBranch: String,
    val privateRepository: Boolean,
    val canPush: Boolean
)

data class GiteeBranch(val name: String, val headSha: String)

data class GiteeCommitSummary(
    val sha: String,
    val message: String,
    val authoredAt: String?
)

data class GiteeRemoteFile(
    val path: String,
    val sha: String,
    val bytes: ByteArray,
    val lastCommitSha: String?
)

data class GiteeTreeEntry(
    val path: String,
    val type: String,
    val sha: String,
    val size: Long?
)

data class GiteeBlob(val sha: String, val bytes: ByteArray)

data class GiteeCommitAction(
    val path: String,
    val content: ByteArray,
    val binary: Boolean
)

data class GiteeCreatedCommit(val sha: String, val raw: JsonObject)

class GiteeApiException(
    val statusCode: Int,
    val responseBody: String,
    val retryAfterEpochMillis: Long? = null,
    message: String = "Gitee API returned HTTP $statusCode"
) : IOException(message)

class CredentialCallRegistry : CredentialInvalidationHandler {
    private val calls = ConcurrentHashMap<String, MutableSet<Call>>()

    fun register(alias: String, call: Call) {
        calls.compute(alias) { _, current ->
            (current ?: ConcurrentHashMap.newKeySet()).also { it += call }
        }
    }

    fun unregister(alias: String, call: Call) {
        calls[alias]?.let { set ->
            set -= call
            if (set.isEmpty()) calls.remove(alias, set)
        }
    }

    override fun cancelRequestsUsing(credentialAlias: String) {
        calls.remove(credentialAlias)?.forEach(Call::cancel)
    }
}

interface GiteeTransport {
    suspend fun getRepository(
        owner: String,
        repository: String,
        credentialAlias: String
    ): GiteeRepositoryInfo

    suspend fun getBranch(
        owner: String,
        repository: String,
        branch: String,
        credentialAlias: String
    ): GiteeBranch

    suspend fun listCommits(
        owner: String,
        repository: String,
        branch: String,
        page: Int,
        perPage: Int,
        credentialAlias: String
    ): List<GiteeCommitSummary>

    suspend fun getCommit(
        owner: String,
        repository: String,
        sha: String,
        credentialAlias: String
    ): JsonObject

    suspend fun getContent(
        owner: String,
        repository: String,
        path: String,
        ref: String,
        credentialAlias: String
    ): GiteeRemoteFile

    suspend fun getTree(
        owner: String,
        repository: String,
        sha: String,
        recursive: Boolean,
        credentialAlias: String
    ): List<GiteeTreeEntry>

    suspend fun getBlob(
        owner: String,
        repository: String,
        sha: String,
        credentialAlias: String
    ): GiteeBlob

    suspend fun createCommit(
        owner: String,
        repository: String,
        branch: String,
        message: String,
        actions: List<GiteeCommitAction>,
        credentialAlias: String
    ): GiteeCreatedCommit
}

class OkHttpGiteeTransport(
    private val credentialStore: CredentialStore,
    private val callRegistry: CredentialCallRegistry,
    private val client: OkHttpClient = OkHttpClient(),
    private val logger: SecureLogger = AndroidSecureLogger
) : GiteeTransport {
    override suspend fun getRepository(
        owner: String,
        repository: String,
        credentialAlias: String
    ): GiteeRepositoryInfo {
        val json = get(repoUrl(owner, repository), credentialAlias).asJsonObject
        val namespace = json.getAsJsonObject("namespace")
        val permissions = json.getAsJsonObject("permissions")
        return GiteeRepositoryInfo(
            owner = namespace?.get("path")?.asString ?: owner,
            name = json.get("path")?.asString ?: repository,
            defaultBranch = json.get("default_branch")?.asString ?: "main",
            privateRepository = json.get("private")?.asBoolean ?: true,
            canPush = permissions?.get("push")?.asBoolean == true ||
                permissions?.get("admin")?.asBoolean == true
        )
    }

    override suspend fun getBranch(
        owner: String,
        repository: String,
        branch: String,
        credentialAlias: String
    ): GiteeBranch {
        val json = get(repoUrl(owner, repository).newBuilder().addPathSegments("branches")
            .addPathSegment(branch).build(), credentialAlias).asJsonObject
        return GiteeBranch(
            name = json.get("name")?.asString ?: branch,
            headSha = json.getAsJsonObject("commit").get("sha").asString
        )
    }

    override suspend fun listCommits(
        owner: String,
        repository: String,
        branch: String,
        page: Int,
        perPage: Int,
        credentialAlias: String
    ): List<GiteeCommitSummary> {
        require(page > 0 && perPage in 1..100)
        val url = repoUrl(owner, repository).newBuilder().addPathSegments("commits")
            .addQueryParameter("sha", branch)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", perPage.toString())
            .build()
        return get(url, credentialAlias).asJsonArray.map { element ->
            val json = element.asJsonObject
            val commit = json.getAsJsonObject("commit")
            GiteeCommitSummary(
                sha = json.get("sha").asString,
                message = commit?.get("message")?.asString.orEmpty(),
                authoredAt = commit?.getAsJsonObject("author")?.get("date")?.asString
            )
        }
    }

    override suspend fun getCommit(
        owner: String,
        repository: String,
        sha: String,
        credentialAlias: String
    ): JsonObject = get(
        repoUrl(owner, repository).newBuilder().addPathSegments("commits")
            .addPathSegment(sha).build(),
        credentialAlias
    ).asJsonObject

    override suspend fun getContent(
        owner: String,
        repository: String,
        path: String,
        ref: String,
        credentialAlias: String
    ): GiteeRemoteFile {
        val url = repoUrl(owner, repository).newBuilder().addPathSegments("contents")
            .addPathSegments(path.trim('/'))
            .addQueryParameter("ref", ref)
            .build()
        val json = get(url, credentialAlias).asJsonObject
        val content = json.get("content")?.asString.orEmpty().replace("\n", "")
        return GiteeRemoteFile(
            path = json.get("path")?.asString ?: path,
            sha = json.get("sha").asString,
            bytes = Base64.getDecoder().decode(content),
            lastCommitSha = json.get("last_commit_id")?.asString
        )
    }

    override suspend fun getTree(
        owner: String,
        repository: String,
        sha: String,
        recursive: Boolean,
        credentialAlias: String
    ): List<GiteeTreeEntry> {
        val url = repoUrl(owner, repository).newBuilder().addPathSegments("git/trees")
            .addPathSegment(sha)
            .addQueryParameter("recursive", if (recursive) "1" else "0")
            .build()
        val tree = get(url, credentialAlias).asJsonObject.getAsJsonArray("tree") ?: JsonArray()
        return tree.map { element ->
            val json = element.asJsonObject
            GiteeTreeEntry(
                path = json.get("path").asString,
                type = json.get("type").asString,
                sha = json.get("sha").asString,
                size = json.get("size")?.takeUnless { it.isJsonNull }?.asLong
            )
        }
    }

    override suspend fun getBlob(
        owner: String,
        repository: String,
        sha: String,
        credentialAlias: String
    ): GiteeBlob {
        val json = get(
            repoUrl(owner, repository).newBuilder().addPathSegments("git/blobs")
                .addPathSegment(sha).build(),
            credentialAlias
        ).asJsonObject
        return GiteeBlob(
            sha = json.get("sha")?.asString ?: sha,
            bytes = Base64.getDecoder().decode(
                json.get("content").asString.replace("\n", "")
            )
        )
    }

    override suspend fun createCommit(
        owner: String,
        repository: String,
        branch: String,
        message: String,
        actions: List<GiteeCommitAction>,
        credentialAlias: String
    ): GiteeCreatedCommit {
        require(actions.isNotEmpty())
        val payload = JsonObject().apply {
            addProperty("branch", branch)
            addProperty("message", message)
            add("actions", JsonArray().apply {
                actions.forEach { action ->
                    add(JsonObject().apply {
                        addProperty("action", "create")
                        addProperty("file_path", action.path)
                        addProperty(
                            "content",
                            if (action.binary) Base64.getEncoder().encodeToString(action.content)
                            else action.content.toString(Charsets.UTF_8)
                        )
                        if (action.binary) addProperty("encoding", "base64")
                    })
                }
            })
        }
        val json = post(
            repoUrl(owner, repository).newBuilder().addPathSegments("commits").build(),
            payload,
            credentialAlias
        ).asJsonObject
        val sha = json.get("sha")?.asString
            ?: json.getAsJsonObject("commit")?.get("sha")?.asString
            ?: error("Gitee commit response omitted SHA")
        return GiteeCreatedCommit(sha, json)
    }

    private suspend fun get(url: HttpUrl, credentialAlias: String) = execute(
        Request.Builder().url(url).get(),
        credentialAlias
    )

    private suspend fun post(url: HttpUrl, payload: JsonObject, credentialAlias: String) = execute(
        Request.Builder().url(url).post(
            payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        ),
        credentialAlias
    )

    private suspend fun execute(
        requestBuilder: Request.Builder,
        credentialAlias: String
    ) = withContext(Dispatchers.IO) {
        credentialStore.withCredential(credentialAlias) { credential ->
            val token = String(credential)
            val request = requestBuilder
                .header("Authorization", "token $token")
                .header("Accept", "application/json")
                .build()
            val call = client.newCall(request)
            callRegistry.register(credentialAlias, call)
            try {
                call.execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val sanitized = SecretRedactor.redact(body.take(MAX_ERROR_BODY), listOf(token))
                        throw GiteeApiException(
                            statusCode = response.code,
                            responseBody = sanitized,
                            retryAfterEpochMillis = parseRetryAfter(response.header("Retry-After"))
                        )
                    }
                    JsonParser.parseString(body)
                }
            } catch (error: IOException) {
                logger.error("GiteeTransport", "Gitee request failed", error, listOf(token))
                throw error
            } finally {
                callRegistry.unregister(credentialAlias, call)
            }
        } ?: error("Credential is missing for alias $credentialAlias")
    }

    private fun repoUrl(owner: String, repository: String): HttpUrl {
        require(SAFE_REPOSITORY_SEGMENT.matches(owner) && SAFE_REPOSITORY_SEGMENT.matches(repository))
        return BASE_URL.newBuilder().addPathSegments("repos")
            .addPathSegment(owner).addPathSegment(repository).build()
    }

    private fun parseRetryAfter(value: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
        val normalized = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        normalized.toLongOrNull()?.takeIf { it >= 0 }?.let { seconds ->
            return nowMillis + seconds * 1000L
        }
        return runCatching {
            ZonedDateTime.parse(normalized, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
    }

    private companion object {
        val BASE_URL = HttpUrl.Builder()
            .scheme("https")
            .host("gitee.com")
            .addPathSegments("api/v5/")
            .build()
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val SAFE_REPOSITORY_SEGMENT = Regex("[A-Za-z0-9_.-]{1,100}")
        const val MAX_ERROR_BODY = 4096
    }
}
