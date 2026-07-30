package com.ebbinghaus.review.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ebbinghaus.review.data.AppDatabase
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ImmutableAssetCacheTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var cache: ImmutableAssetCache
    private val profileIds = mutableListOf<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        cache = ImmutableAssetCache(context, database.assetDao())
    }

    @After
    fun tearDown() {
        database.close()
        profileIds.forEach { profileId ->
            File(context.filesDir, "ebbinghaus_asset_cache/$profileId").deleteRecursively()
        }
    }

    @Test
    fun cleanupDeletesCrashFilesButKeepsInstalledAssets() = runBlocking {
        val profileId = insertProfile()
        val installed = cache.install(profileId, "png", ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        val directory = installed.file.parentFile!!
        val temporary = File(directory, ".tmp-interrupted").apply { writeBytes(byteArrayOf(4)) }
        val orphan = File(directory, "orphan.bin").apply { writeBytes(byteArrayOf(5, 6)) }

        val result = cache.cleanOrphans(profileId)

        assertEquals(2, result.deletedFiles)
        assertEquals(3L, result.deletedBytes)
        assertTrue(installed.file.isFile)
        assertFalse(temporary.exists())
        assertFalse(orphan.exists())
    }

    @Test
    fun oversizedWriteLeavesNoTemporaryFileOrDatabaseRow() = runBlocking {
        val profileId = insertProfile()
        val failure = runCatching {
            cache.install(profileId, "png", ByteArrayInputStream(byteArrayOf(1, 2)), maxBytes = 1)
        }

        assertTrue(failure.isFailure)
        assertTrue(database.assetDao().getAll(profileId).isEmpty())
        val directory = File(context.filesDir, "ebbinghaus_asset_cache/$profileId")
        assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith(".tmp-") })
    }

    @Test
    fun identicalBytesRemainIsolatedByProfile() = runBlocking {
        val profileA = insertProfile()
        val profileB = insertProfile()
        val bytes = byteArrayOf(7, 8, 9)

        val assetA = cache.install(profileA, "jpg", ByteArrayInputStream(bytes))
        val assetB = cache.install(profileB, "jpg", ByteArrayInputStream(bytes))

        assertEquals(assetA.sha256, assetB.sha256)
        assertNotEquals(assetA.file.absolutePath, assetB.file.absolutePath)
        cache.cleanOrphans(profileA)
        assertTrue(assetA.file.isFile)
        assertTrue(assetB.file.isFile)
        assertEquals(1, database.assetDao().getAll(profileA).size)
        assertEquals(1, database.assetDao().getAll(profileB).size)
    }

    private suspend fun insertProfile(): String {
        val profileId = UUID.randomUUID().toString()
        profileIds += profileId
        database.profileDao().insert(
            Profile(
                profileId = profileId,
                displayName = profileId,
                timezone = "Asia/Shanghai",
                algorithmId = "ebbinghaus-8-stage",
                algorithmVersion = 1,
                algorithmParametersJson = "{\"interval_days\":[1,2,4,7,15,30,60,120]}"
            )
        )
        return profileId
    }
}
