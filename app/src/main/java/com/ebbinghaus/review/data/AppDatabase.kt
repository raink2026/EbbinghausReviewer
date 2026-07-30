package com.ebbinghaus.review.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ebbinghaus.review.data.sync.Asset
import com.ebbinghaus.review.data.sync.AssetDao
import com.ebbinghaus.review.data.sync.Note
import com.ebbinghaus.review.data.sync.NoteProjectionDao
import com.ebbinghaus.review.data.sync.NoteRevision
import com.ebbinghaus.review.data.sync.Profile
import com.ebbinghaus.review.data.sync.ProfileDao
import com.ebbinghaus.review.data.sync.RemoteRepository
import com.ebbinghaus.review.data.sync.RemoteRepositoryDao
import com.ebbinghaus.review.data.sync.ReviewEvent
import com.ebbinghaus.review.data.sync.SyncCheckpoint
import com.ebbinghaus.review.data.sync.SyncOutbox
import com.ebbinghaus.review.data.sync.SyncStateDao
import com.ebbinghaus.review.utils.AppConstants

@Database(
    entities = [
        ReviewItem::class,
        ReviewLog::class,
        PlanItem::class,
        User::class,
        Profile::class,
        RemoteRepository::class,
        Note::class,
        NoteRevision::class,
        ReviewEvent::class,
        Asset::class,
        SyncOutbox::class,
        SyncCheckpoint::class
    ],
    version = 6,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun reviewDao(): ReviewDao
    abstract fun planDao(): PlanDao
    abstract fun userDao(): UserDao
    abstract fun profileDao(): ProfileDao
    abstract fun remoteRepositoryDao(): RemoteRepositoryDao
    abstract fun noteProjectionDao(): NoteProjectionDao
    abstract fun assetDao(): AssetDao
    abstract fun syncStateDao(): SyncStateDao

    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE users ADD COLUMN themeColor INTEGER")
                database.execSQL("ALTER TABLE users ADD COLUMN fontScale REAL NOT NULL DEFAULT 1.0")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE users ADD COLUMN showMenuLabels INTEGER NOT NULL DEFAULT 1")
                database.execSQL("ALTER TABLE users ADD COLUMN homeIcon TEXT NOT NULL DEFAULT 'Home'")
                database.execSQL("ALTER TABLE users ADD COLUMN planIcon TEXT NOT NULL DEFAULT 'DateRange'")
                database.execSQL("ALTER TABLE users ADD COLUMN profileIcon TEXT NOT NULL DEFAULT 'Person'")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `sync_profiles` (`profileId` TEXT NOT NULL, `displayName` TEXT NOT NULL, `timezone` TEXT NOT NULL, `algorithmId` TEXT NOT NULL, `algorithmVersion` INTEGER NOT NULL, `algorithmParametersJson` TEXT NOT NULL, `isCurrent` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`profileId`))")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_profiles_isCurrent` ON `sync_profiles` (`isCurrent`)")
                database.execSQL("CREATE TABLE IF NOT EXISTS `remote_repositories` (`repositoryId` TEXT NOT NULL, `profileId` TEXT NOT NULL, `owner` TEXT NOT NULL, `name` TEXT NOT NULL, `branch` TEXT NOT NULL, `credentialAlias` TEXT NOT NULL, `isBound` INTEGER NOT NULL, `autoSync` INTEGER NOT NULL, `wifiOnly` INTEGER NOT NULL, `syncState` TEXT NOT NULL, `lastSyncError` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`repositoryId`), FOREIGN KEY(`profileId`) REFERENCES `sync_profiles`(`profileId`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_remote_repositories_profileId` ON `remote_repositories` (`profileId`)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_remote_repositories_repositoryId_profileId` ON `remote_repositories` (`repositoryId`, `profileId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_remote_repositories_owner_name_branch` ON `remote_repositories` (`owner`, `name`, `branch`)")
                database.execSQL("CREATE TABLE IF NOT EXISTS `sync_notes` (`noteId` TEXT NOT NULL, `profileId` TEXT NOT NULL, `title` TEXT NOT NULL, `activeRevisionId` TEXT, `projectionState` TEXT NOT NULL, `reviewStage` INTEGER NOT NULL, `nextReviewAt` INTEGER, `isReviewComplete` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`noteId`), FOREIGN KEY(`profileId`) REFERENCES `sync_profiles`(`profileId`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`activeRevisionId`, `profileId`) REFERENCES `note_revisions`(`revisionId`, `profileId`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_notes_profileId` ON `sync_notes` (`profileId`)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_notes_noteId_profileId` ON `sync_notes` (`noteId`, `profileId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_notes_profileId_projectionState` ON `sync_notes` (`profileId`, `projectionState`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_notes_activeRevisionId_profileId` ON `sync_notes` (`activeRevisionId`, `profileId`)")
                database.execSQL("CREATE TABLE IF NOT EXISTS `note_revisions` (`revisionId` TEXT NOT NULL, `profileId` TEXT NOT NULL, `noteId` TEXT NOT NULL, `parentRevisionIdsJson` TEXT NOT NULL, `revisionKind` TEXT NOT NULL, `authoredAt` INTEGER NOT NULL, `learningStartedAt` INTEGER NOT NULL, `sourceDeviceId` TEXT NOT NULL, `contentSha256` TEXT NOT NULL, `markdownCachePath` TEXT NOT NULL, `repositoryPath` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`revisionId`), FOREIGN KEY(`profileId`) REFERENCES `sync_profiles`(`profileId`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`noteId`, `profileId`) REFERENCES `sync_notes`(`noteId`, `profileId`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_note_revisions_profileId` ON `note_revisions` (`profileId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_note_revisions_noteId` ON `note_revisions` (`noteId`)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_note_revisions_revisionId_profileId` ON `note_revisions` (`revisionId`, `profileId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_note_revisions_noteId_profileId` ON `note_revisions` (`noteId`, `profileId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_note_revisions_profileId_learningStartedAt` ON `note_revisions` (`profileId`, `learningStartedAt`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_note_revisions_contentSha256` ON `note_revisions` (`contentSha256`)")
                database.execSQL("CREATE TABLE IF NOT EXISTS `sync_review_events` (`eventId` TEXT NOT NULL, `profileId` TEXT NOT NULL, `noteId` TEXT NOT NULL, `revisionId` TEXT, `streamId` TEXT NOT NULL, `parentEventIdsJson` TEXT NOT NULL, `eventType` TEXT NOT NULL, `occurredAt` INTEGER NOT NULL, `sourceDeviceId` TEXT NOT NULL, `algorithmId` TEXT NOT NULL, `algorithmVersion` INTEGER NOT NULL, `payloadJson` TEXT NOT NULL, `contentSha256` TEXT NOT NULL, `repositoryPath` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`eventId`), FOREIGN KEY(`profileId`) REFERENCES `sync_profiles`(`profileId`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`noteId`, `profileId`) REFERENCES `sync_notes`(`noteId`, `profileId`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`revisionId`, `profileId`) REFERENCES `note_revisions`(`revisionId`, `profileId`) ON UPDATE NO ACTION ON DELETE NO ACTION )")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_review_events_profileId` ON `sync_review_events` (`profileId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_review_events_noteId` ON `sync_review_events` (`noteId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_review_events_revisionId` ON `sync_review_events` (`revisionId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_review_events_noteId_profileId` ON `sync_review_events` (`noteId`, `profileId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_review_events_revisionId_profileId` ON `sync_review_events` (`revisionId`, `profileId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_review_events_profileId_streamId` ON `sync_review_events` (`profileId`, `streamId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_review_events_contentSha256` ON `sync_review_events` (`contentSha256`)")
                database.execSQL("CREATE TABLE IF NOT EXISTS `sync_assets` (`assetId` TEXT NOT NULL, `profileId` TEXT NOT NULL, `sha256` TEXT NOT NULL, `extension` TEXT NOT NULL, `byteSize` INTEGER NOT NULL, `cachePath` TEXT NOT NULL, `referenceCount` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`assetId`), FOREIGN KEY(`profileId`) REFERENCES `sync_profiles`(`profileId`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_assets_profileId` ON `sync_assets` (`profileId`)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_assets_profileId_sha256_extension` ON `sync_assets` (`profileId`, `sha256`, `extension`)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_assets_cachePath` ON `sync_assets` (`cachePath`)")
                database.execSQL("CREATE TABLE IF NOT EXISTS `sync_outbox` (`operationId` TEXT NOT NULL, `profileId` TEXT NOT NULL, `repositoryId` TEXT NOT NULL, `operationType` TEXT NOT NULL, `repositoryPath` TEXT NOT NULL, `contentSha256` TEXT NOT NULL, `payloadCachePath` TEXT NOT NULL, `dependencyIdsJson` TEXT NOT NULL, `profileDate` TEXT NOT NULL, `batchId` TEXT, `status` TEXT NOT NULL, `attemptCount` INTEGER NOT NULL, `nextAttemptAt` INTEGER, `lastError` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`operationId`), FOREIGN KEY(`profileId`) REFERENCES `sync_profiles`(`profileId`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`repositoryId`, `profileId`) REFERENCES `remote_repositories`(`repositoryId`, `profileId`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_outbox_profileId` ON `sync_outbox` (`profileId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_outbox_repositoryId` ON `sync_outbox` (`repositoryId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_outbox_repositoryId_profileId` ON `sync_outbox` (`repositoryId`, `profileId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_outbox_profileId_status` ON `sync_outbox` (`profileId`, `status`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_outbox_batchId` ON `sync_outbox` (`batchId`)")
                database.execSQL("CREATE TABLE IF NOT EXISTS `sync_checkpoints` (`checkpointId` TEXT NOT NULL, `profileId` TEXT NOT NULL, `repositoryId` TEXT NOT NULL, `remoteCommitSha` TEXT, `lastPullAt` INTEGER, `lastPushAt` INTEGER, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`checkpointId`), FOREIGN KEY(`profileId`) REFERENCES `sync_profiles`(`profileId`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`repositoryId`, `profileId`) REFERENCES `remote_repositories`(`repositoryId`, `profileId`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_checkpoints_profileId` ON `sync_checkpoints` (`profileId`)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_checkpoints_repositoryId` ON `sync_checkpoints` (`repositoryId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_checkpoints_repositoryId_profileId` ON `sync_checkpoints` (`repositoryId`, `profileId`)")
            }
        }

        @Volatile
        private var Instance: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return Instance ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    AppConstants.DB_NAME
                )
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build().also { Instance = it }
            }
        }
    }
}
