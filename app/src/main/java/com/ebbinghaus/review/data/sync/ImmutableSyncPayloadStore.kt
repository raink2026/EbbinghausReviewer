package com.ebbinghaus.review.data.sync

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

class ImmutableSyncPayloadStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "ebbinghaus_sync_payloads")

    fun install(profileId: String, contentSha256: String, extension: String, bytes: ByteArray): File {
        require(SAFE_SEGMENT.matches(profileId))
        require(SHA256.matches(contentSha256))
        require(EXTENSION.matches(extension))
        val directory = File(root, profileId).apply { mkdirs() }
        val destination = File(directory, "$contentSha256.$extension")
        if (destination.exists()) {
            check(destination.readBytes().contentEquals(bytes)) { "Sync payload hash collision" }
            return destination
        }
        val temporary = File(directory, ".tmp-${UUID.randomUUID()}")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination.toPath())
            }
            return destination
        } finally {
            temporary.delete()
        }
    }

    fun cleanOrphans(profileId: String, referencedPaths: Set<String>): CacheCleanupResult {
        require(SAFE_SEGMENT.matches(profileId))
        val directory = File(root, profileId)
        if (!directory.exists()) return CacheCleanupResult(0, 0L)
        val canonicalReferences = referencedPaths.mapTo(mutableSetOf()) { File(it).canonicalPath }
        var deletedFiles = 0
        var deletedBytes = 0L
        directory.listFiles().orEmpty().forEach { file ->
            if (file.isFile && (file.name.startsWith(".tmp-") || file.canonicalPath !in canonicalReferences)) {
                val size = file.length()
                if (file.delete()) {
                    deletedFiles += 1
                    deletedBytes += size
                }
            }
        }
        return CacheCleanupResult(deletedFiles, deletedBytes)
    }

    private companion object {
        val SAFE_SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val SHA256 = Regex("[0-9a-f]{64}")
        val EXTENSION = Regex("[a-z0-9]{1,10}")
    }
}
