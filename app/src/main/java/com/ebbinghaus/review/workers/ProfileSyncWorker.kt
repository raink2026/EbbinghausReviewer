package com.ebbinghaus.review.workers

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ebbinghaus.review.data.sync.SyncServices
import com.ebbinghaus.review.data.sync.remote.GiteePullService
import com.ebbinghaus.review.data.sync.remote.GiteePushService
import com.ebbinghaus.review.data.sync.remote.SyncFailureKind
import com.ebbinghaus.review.data.sync.remote.SyncPipelineException
import java.util.concurrent.TimeUnit

class ProfileSyncWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val profileId = inputData.getString(KEY_PROFILE_ID) ?: return Result.failure()
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val services = SyncServices.get(applicationContext)
        val repository = services.database.remoteRepositoryDao().getForProfile(profileId)
            ?: return Result.failure(workDataOf(KEY_ERROR to "Repository binding is missing"))
        if (!repository.isBound || (!repository.autoSync && !manual)) return Result.success()
        val pullService = GiteePullService(
            applicationContext,
            services.database,
            services.transport
        )
        val pushService = GiteePushService(services.database, services.transport, pullService)
        return try {
            pushService.push(profileId)
            services.database.remoteRepositoryDao().updateSyncHealth(
                profileId,
                repository.repositoryId,
                "IDLE",
                null,
                System.currentTimeMillis()
            )
            Result.success()
        } catch (error: SyncPipelineException) {
            services.database.remoteRepositoryDao().updateSyncHealth(
                profileId,
                repository.repositoryId,
                if (error.kind == SyncFailureKind.TRANSIENT) "RETRY" else "PAUSED",
                error.message.orEmpty().take(1000),
                System.currentTimeMillis()
            )
            if (error.kind == SyncFailureKind.TRANSIENT) {
                Result.retry()
            } else {
                Result.failure(workDataOf(KEY_ERROR to error.message.orEmpty().take(1000)))
            }
        } catch (error: Exception) {
            services.database.remoteRepositoryDao().updateSyncHealth(
                profileId,
                repository.repositoryId,
                "ERROR",
                error.message.orEmpty().take(1000),
                System.currentTimeMillis()
            )
            Result.failure(workDataOf(KEY_ERROR to error.message.orEmpty().take(1000)))
        }
    }

    companion object {
        const val KEY_PROFILE_ID = "profile_id"
        const val KEY_MANUAL = "manual"
        const val KEY_ERROR = "error"
    }
}

object SyncScheduler {
    fun cancel(context: Context, profileId: String) {
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork("profile-sync-$profileId")
    }

    fun enqueue(
        context: Context,
        profileId: String,
        wifiOnly: Boolean,
        manual: Boolean = false
    ) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<ProfileSyncWorker>()
            .setInputData(
                Data.Builder()
                    .putString(ProfileSyncWorker.KEY_PROFILE_ID, profileId)
                    .putBoolean(ProfileSyncWorker.KEY_MANUAL, manual)
                    .build()
            )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("profile-sync-$profileId")
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "profile-sync-$profileId",
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}
