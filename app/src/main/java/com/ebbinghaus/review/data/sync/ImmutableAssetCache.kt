package com.ebbinghaus.review.data.sync

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

data class CachedAsset(
    val assetId: String,
    val profileId: String,
    val sha256: String,
    val extension: String,
    val byteSize: Long,
    val file: File
)

class ImmutableAssetCache(
    context: Context,
    private val assetDao: AssetDao
) {
    private val root = File(context.applicationContext.filesDir, CACHE_DIRECTORY)

    suspend fun install(
        profileId: String,
        extension: String,
        input: InputStream,
        maxBytes: Long = DEFAULT_MAX_ASSET_BYTES
    ): CachedAsset {
        val safeProfileId = requireSafeSegment(profileId, "profileId")
        val normalizedExtension = normalizeExtension(extension)
        val profileDirectory = File(root, safeProfileId).apply { mkdirs() }
        val temporary = File(profileDirectory, "$TEMP_PREFIX${UUID.randomUUID()}")
        val digest = MessageDigest.getInstance("SHA-256")
        var byteSize = 0L

        try {
            FileOutputStream(temporary).buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    byteSize += read
                    require(byteSize <= maxBytes) {
                        "Asset exceeds the ${maxBytes}-byte limit"
                    }
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
                output.flush()
            }
            FileOutputStream(temporary, true).use { it.fd.sync() }

            val sha256 = digest.digest().toHex()
            val destination = File(profileDirectory, "$sha256.$normalizedExtension")
            if (destination.exists()) {
                check(hash(destination) == sha256) {
                    "Immutable cache collision for $sha256.$normalizedExtension"
                }
                temporary.delete()
            } else {
                atomicMove(temporary, destination)
            }

            val existing = assetDao.find(profileId, sha256, normalizedExtension)
            if (existing != null) return existing.toCachedAsset()

            val asset = Asset(
                assetId = UUID.randomUUID().toString(),
                profileId = profileId,
                sha256 = sha256,
                extension = normalizedExtension,
                byteSize = byteSize,
                cachePath = destination.absolutePath
            )
            assetDao.insert(asset)
            return (assetDao.find(profileId, sha256, normalizedExtension) ?: asset).toCachedAsset()
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    suspend fun find(
        profileId: String,
        sha256: String,
        extension: String,
        verifyHash: Boolean = false
    ): CachedAsset? {
        require(SHA256_REGEX.matches(sha256)) { "Invalid SHA-256" }
        val asset = assetDao.find(profileId, sha256, normalizeExtension(extension)) ?: return null
        val file = File(asset.cachePath)
        if (!file.isFile) return null
        if (verifyHash && hash(file) != sha256) return null
        return asset.toCachedAsset()
    }

    suspend fun retain(profileId: String, assetId: String) {
        check(assetDao.adjustReferenceCount(profileId, assetId, 1) == 1) {
            "Unknown asset $assetId for profile $profileId"
        }
    }

    suspend fun release(profileId: String, assetId: String) {
        check(assetDao.adjustReferenceCount(profileId, assetId, -1) == 1) {
            "Unknown asset or negative reference count for $assetId"
        }
    }

    suspend fun cleanOrphans(profileId: String): CacheCleanupResult {
        val safeProfileId = requireSafeSegment(profileId, "profileId")
        val directory = File(root, safeProfileId)
        if (!directory.exists()) return CacheCleanupResult(0, 0L)
        val referencedPaths = assetDao.getAll(profileId)
            .mapTo(mutableSetOf()) { File(it.cachePath).canonicalPath }
        var deletedFiles = 0
        var deletedBytes = 0L

        directory.listFiles().orEmpty().forEach { candidate ->
            val isTemporary = candidate.name.startsWith(TEMP_PREFIX)
            val isUnreferenced = candidate.canonicalPath !in referencedPaths
            if (candidate.isFile && (isTemporary || isUnreferenced)) {
                val size = candidate.length()
                if (candidate.delete()) {
                    deletedFiles += 1
                    deletedBytes += size
                }
            }
        }
        return CacheCleanupResult(deletedFiles, deletedBytes)
    }

    suspend fun removeReconstructibleUnreferenced(profileId: String): CacheCleanupResult {
        var deletedFiles = 0
        var deletedBytes = 0L
        assetDao.getAll(profileId).filter { it.referenceCount == 0 }.forEach { asset ->
            if (assetDao.deleteIfUnreferenced(profileId, asset.assetId) == 1) {
                val file = File(asset.cachePath)
                val size = file.length()
                if (!file.exists() || file.delete()) {
                    deletedFiles += 1
                    deletedBytes += size
                }
            }
        }
        return CacheCleanupResult(deletedFiles, deletedBytes)
    }

    fun usage(profileId: String): CacheUsage {
        val directory = File(root, requireSafeSegment(profileId, "profileId"))
        val files = directory.listFiles().orEmpty().filter(File::isFile)
        return CacheUsage(files.size, files.sumOf(File::length))
    }

    private fun atomicMove(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath())
        }
    }

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun normalizeExtension(extension: String): String {
        val normalized = extension.trim().removePrefix(".").lowercase()
        require(EXTENSION_REGEX.matches(normalized)) { "Invalid asset extension" }
        return normalized
    }

    private fun requireSafeSegment(value: String, label: String): String {
        require(PATH_SEGMENT_REGEX.matches(value)) { "Invalid $label" }
        return value
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun Asset.toCachedAsset(): CachedAsset = CachedAsset(
        assetId = assetId,
        profileId = profileId,
        sha256 = sha256,
        extension = extension,
        byteSize = byteSize,
        file = File(cachePath)
    )

    private companion object {
        const val CACHE_DIRECTORY = "ebbinghaus_asset_cache"
        const val TEMP_PREFIX = ".tmp-"
        const val DEFAULT_MAX_ASSET_BYTES = 10L * 1024L * 1024L
        val PATH_SEGMENT_REGEX = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val EXTENSION_REGEX = Regex("[a-z0-9]{1,10}")
        val SHA256_REGEX = Regex("[0-9a-f]{64}")
    }
}

data class CacheCleanupResult(val deletedFiles: Int, val deletedBytes: Long)

data class CacheUsage(val fileCount: Int, val byteSize: Long) {
    operator fun plus(other: CacheUsage): CacheUsage = CacheUsage(
        fileCount + other.fileCount,
        byteSize + other.byteSize
    )
}
