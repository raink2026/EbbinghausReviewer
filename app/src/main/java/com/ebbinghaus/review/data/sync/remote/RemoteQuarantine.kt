package com.ebbinghaus.review.data.sync.remote

import android.content.Context
import java.io.File
import java.io.FileOutputStream

data class QuarantinedFile(val repositoryPath: String, val file: File, val sha: String)

data class QuarantinedCommit(
    val commitSha: String,
    val files: List<QuarantinedFile>,
    val directory: File
)

class RemoteQuarantine(context: Context) {
    private val root = File(context.applicationContext.cacheDir, "ebbinghaus_quarantine")

    fun store(
        profileId: String,
        commitSha: String,
        files: List<GiteeRemoteFile>
    ): QuarantinedCommit {
        require(SAFE_SEGMENT.matches(profileId) && SAFE_SEGMENT.matches(commitSha))
        val directory = File(File(root, profileId), commitSha).apply { mkdirs() }
        val stored = files.map { remote ->
            val normalized = normalizeRepositoryPath(remote.path)
            val destination = File(directory, normalized)
            require(destination.canonicalPath.startsWith(directory.canonicalPath + File.separator))
            destination.parentFile?.mkdirs()
            FileOutputStream(destination).use { output ->
                output.write(remote.bytes)
                output.flush()
                output.fd.sync()
            }
            QuarantinedFile(normalized, destination, remote.sha)
        }
        return QuarantinedCommit(commitSha, stored, directory)
    }

    fun discard(commit: QuarantinedCommit) {
        commit.directory.deleteRecursively()
    }

    private fun normalizeRepositoryPath(path: String): String {
        val normalized = path.replace('\\', '/').trimStart('/')
        require(normalized.isNotBlank() && normalized.split('/').none { it == ".." || it.isBlank() })
        return normalized
    }

    private companion object {
        val SAFE_SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}
