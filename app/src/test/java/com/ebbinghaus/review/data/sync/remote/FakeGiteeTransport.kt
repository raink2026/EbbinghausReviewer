package com.ebbinghaus.review.data.sync.remote

import com.google.gson.JsonArray
import com.google.gson.JsonObject

internal data class RecordedCommit(
    val branch: String,
    val message: String,
    val actions: List<GiteeCommitAction>,
    val credentialAlias: String
)

internal class FakeGiteeTransport : GiteeTransport {
    var repositoryInfo = GiteeRepositoryInfo("owner", "repository", "main", true, true)
    var branchValue = GiteeBranch("main", "head")
    var branchFailure: GiteeApiException? = null
    val createdBranches = mutableListOf<Pair<String, String>>()
    val commitPages = mutableMapOf<Int, List<GiteeCommitSummary>>()
    val commitDetails = mutableMapOf<String, JsonObject>()
    val contents = mutableMapOf<Pair<String, String>, GiteeRemoteFile>()
    var treeEntries: List<GiteeTreeEntry> = emptyList()
    val blobs = mutableMapOf<String, GiteeBlob>()
    val requestedPages = mutableListOf<Int>()
    val createdCommits = mutableListOf<RecordedCommit>()
    var contentFailure: ((String, String) -> GiteeApiException?)? = null
    var createFailure: GiteeApiException? = null
    var nextCreatedSha = "created-sha"

    override suspend fun getRepository(
        owner: String,
        repository: String,
        credentialAlias: String
    ): GiteeRepositoryInfo = repositoryInfo

    override suspend fun getBranch(
        owner: String,
        repository: String,
        branch: String,
        credentialAlias: String
    ): GiteeBranch {
        branchFailure?.let { throw it }
        return branchValue
    }

    override suspend fun createBranch(
        owner: String,
        repository: String,
        refs: String,
        branchName: String,
        credentialAlias: String
    ): GiteeBranch {
        createdBranches += refs to branchName
        branchFailure = null
        branchValue = GiteeBranch(branchName, branchValue.headSha)
        return branchValue
    }

    override suspend fun listCommits(
        owner: String,
        repository: String,
        branch: String,
        page: Int,
        perPage: Int,
        credentialAlias: String
    ): List<GiteeCommitSummary> {
        requestedPages += page
        return commitPages[page].orEmpty()
    }

    override suspend fun getCommit(
        owner: String,
        repository: String,
        sha: String,
        credentialAlias: String
    ): JsonObject = commitDetails[sha] ?: JsonObject().apply { add("files", JsonArray()) }

    override suspend fun getContent(
        owner: String,
        repository: String,
        path: String,
        ref: String,
        credentialAlias: String
    ): GiteeRemoteFile {
        contentFailure?.invoke(path, ref)?.let { throw it }
        return contents[path to ref]
            ?: contents[path to "*"]
            ?: throw GiteeApiException(404, "not found")
    }

    override suspend fun getTree(
        owner: String,
        repository: String,
        sha: String,
        recursive: Boolean,
        credentialAlias: String
    ): List<GiteeTreeEntry> = treeEntries

    override suspend fun getBlob(
        owner: String,
        repository: String,
        sha: String,
        credentialAlias: String
    ): GiteeBlob = blobs.getValue(sha)

    override suspend fun createCommit(
        owner: String,
        repository: String,
        branch: String,
        message: String,
        actions: List<GiteeCommitAction>,
        credentialAlias: String
    ): GiteeCreatedCommit {
        createFailure?.let { throw it }
        createdCommits += RecordedCommit(branch, message, actions, credentialAlias)
        actions.forEach { action ->
            contents[action.path to nextCreatedSha] = GiteeRemoteFile(
                path = action.path,
                sha = action.path,
                bytes = action.content,
                lastCommitSha = nextCreatedSha
            )
            contents[action.path to "*"] = contents.getValue(action.path to nextCreatedSha)
        }
        commitDetails[nextCreatedSha] = JsonObject().apply {
            add("files", JsonArray().apply {
                actions.forEach { action ->
                    add(JsonObject().apply { addProperty("filename", action.path) })
                }
            })
        }
        branchValue = branchValue.copy(headSha = nextCreatedSha)
        return GiteeCreatedCommit(nextCreatedSha, JsonObject())
    }
}
