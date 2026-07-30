package com.ebbinghaus.review.data.sync

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

class ImmutableRevisionStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, REVISION_CACHE_DIRECTORY)

    fun install(profileId: String, revisionId: String, bytes: ByteArray): File {
        require(SAFE_SEGMENT.matches(profileId)) { "Invalid profileId" }
        require(SAFE_SEGMENT.matches(revisionId)) { "Invalid revisionId" }
        val directory = File(root, profileId).apply { mkdirs() }
        val destination = File(directory, "$revisionId.md")
        if (destination.exists()) {
            check(destination.readBytes().contentEquals(bytes)) {
                "Revision cache collision for $revisionId"
            }
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
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination.toPath())
            }
            return destination
        } finally {
            temporary.delete()
        }
    }

    fun cleanOrphans(profileId: String, referencedPaths: Set<String>): CacheCleanupResult {
        require(SAFE_SEGMENT.matches(profileId)) { "Invalid profileId" }
        val directory = File(root, profileId)
        if (!directory.exists()) return CacheCleanupResult(0, 0L)
        val canonicalReferences = referencedPaths.mapTo(mutableSetOf()) { File(it).canonicalPath }
        var count = 0
        var bytes = 0L
        directory.listFiles().orEmpty().forEach { file ->
            if (file.isFile && (file.name.startsWith(".tmp-") || file.canonicalPath !in canonicalReferences)) {
                val size = file.length()
                if (file.delete()) {
                    count += 1
                    bytes += size
                }
            }
        }
        return CacheCleanupResult(count, bytes)
    }

    fun usage(profileId: String): CacheUsage {
        require(SAFE_SEGMENT.matches(profileId)) { "Invalid profileId" }
        val files = File(root, profileId).listFiles().orEmpty().filter(File::isFile)
        return CacheUsage(files.size, files.sumOf(File::length))
    }

    private companion object {
        const val REVISION_CACHE_DIRECTORY = "ebbinghaus_revision_cache"
        val SAFE_SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}
