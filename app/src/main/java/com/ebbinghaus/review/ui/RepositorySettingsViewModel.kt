package com.ebbinghaus.review.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ebbinghaus.review.data.migration.LegacyMigrationManager
import com.ebbinghaus.review.data.migration.LegacyMigrationPreview
import com.ebbinghaus.review.data.migration.LegacyMigrationStatus
import com.ebbinghaus.review.data.sync.Profile
import com.ebbinghaus.review.data.sync.CacheUsage
import com.ebbinghaus.review.data.sync.ImmutableAssetCache
import com.ebbinghaus.review.data.sync.ImmutableRevisionStore
import com.ebbinghaus.review.data.sync.RemoteRepository
import com.ebbinghaus.review.data.sync.RepositoryBindingResult
import com.ebbinghaus.review.data.sync.RepositoryLocation
import com.ebbinghaus.review.data.sync.SyncCheckpoint
import com.ebbinghaus.review.data.sync.SyncServices
import com.ebbinghaus.review.data.sync.protocol.ALGORITHM_ID
import com.ebbinghaus.review.data.sync.protocol.ALGORITHM_VERSION
import com.ebbinghaus.review.data.sync.remote.RepositoryInspection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.UUID
import com.ebbinghaus.review.workers.SyncScheduler

data class RepositoryConnectRequest(
    val displayName: String,
    val owner: String,
    val repository: String,
    val branch: String,
    val timezone: String,
    val personalAccessToken: String,
    val autoSync: Boolean,
    val wifiOnly: Boolean
)

class RepositorySettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val services = SyncServices.get(application)
    private val database = services.database
    private val profileDao = database.profileDao()
    private val repositoryDao = database.remoteRepositoryDao()
    private val syncStateDao = database.syncStateDao()
    private val migrationManager = LegacyMigrationManager(application, database)
    private val assetCache = ImmutableAssetCache(application, database.assetDao())
    private val revisionStore = ImmutableRevisionStore(application)

    val profiles: StateFlow<List<Profile>> = profileDao.observeProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val currentProfile: StateFlow<Profile?> = profileDao.observeCurrentProfile()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val currentRepository: StateFlow<RemoteRepository?> = profileDao.observeCurrentProfile()
        .flatMapLatest { profile ->
            profile?.let { repositoryDao.observeForProfile(it.profileId) } ?: flowOf(null)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val checkpoint: StateFlow<SyncCheckpoint?> = profileDao.observeCurrentProfile()
        .flatMapLatest { profile ->
            profile?.let { syncStateDao.observeCheckpoint(it.profileId) } ?: flowOf(null)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val pendingCount: StateFlow<Int> = profileDao.observeCurrentProfile()
        .flatMapLatest { profile ->
            profile?.let { syncStateDao.observePendingCount(it.profileId) } ?: flowOf(0)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val migrationPreview = MutableStateFlow<LegacyMigrationPreview?>(null)
    val migrationStatus = MutableStateFlow(LegacyMigrationStatus.NOT_STARTED)
    val cacheUsage = MutableStateFlow(CacheUsage(0, 0L))

    fun connect(request: RepositoryConnectRequest, onComplete: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch {
            busy.value = true
            message.value = null
            var provisionalAlias: String? = null
            var replacedRepository: RemoteRepository? = null
            val result = runCatching {
                require(request.displayName.isNotBlank())
                require(request.owner.isNotBlank() && request.repository.isNotBlank())
                require(request.branch.isNotBlank())
                val timezone = ZoneId.of(request.timezone).id
                val existing = currentRepository.value
                val replacingCredential = existing?.takeIf {
                    it.owner == request.owner &&
                        it.name == request.repository &&
                        it.branch == request.branch
                }
                replacingCredential?.let { repository ->
                    SyncScheduler.cancel(getApplication(), repository.profileId)
                    services.callRegistry.cancelRequestsUsing(repository.credentialAlias)
                    replacedRepository = repository
                }
                val alias = "gitee-${UUID.randomUUID()}"
                provisionalAlias = alias
                services.credentialStore.save(alias, request.personalAccessToken)
                val location = RepositoryLocation(request.owner, request.repository, request.branch)
                val inspection = services.connectionService.inspect(location, alias)
                val target = when (inspection) {
                    is RepositoryInspection.Initialized -> {
                        val binding = services.bindingService.bindInitializedRepository(
                            identity = inspection.identity,
                            location = location,
                            credentialAlias = alias,
                            displayName = request.displayName
                        )
                        val profileId = selectBinding(binding)
                        profileId to inspection.branch.headSha
                    }
                    is RepositoryInspection.Empty -> {
                        val profile = Profile(
                            profileId = UUID.randomUUID().toString(),
                            displayName = request.displayName,
                            timezone = timezone,
                            algorithmId = ALGORITHM_ID,
                            algorithmVersion = ALGORITHM_VERSION,
                            algorithmParametersJson = DEFAULT_ALGORITHM_PARAMETERS
                        )
                        val repositoryId = UUID.randomUUID().toString()
                        val commit = services.connectionService.initialize(
                            location,
                            alias,
                            profile,
                            repositoryId
                        )
                        val binding = services.bindingService.initializeEmptyRepository(
                            profile,
                            repositoryId,
                            location,
                            alias
                        )
                        selectBinding(binding) to commit.sha
                    }
                    is RepositoryInspection.Failed -> error(inspection.message)
                }
                profileDao.switchToProfile(target.first)
                val repository = repositoryDao.getForProfile(target.first)
                    ?: error("Repository binding was not persisted")
                repositoryDao.updateMutableConfiguration(
                    profileId = target.first,
                    repositoryId = repository.repositoryId,
                    owner = request.owner,
                    name = request.repository,
                    branch = request.branch,
                    credentialAlias = alias,
                    isBound = true,
                    autoSync = request.autoSync,
                    wifiOnly = request.wifiOnly,
                    updatedAt = System.currentTimeMillis()
                )
                syncStateDao.saveCheckpoint(
                    SyncCheckpoint(
                        checkpointId = syncStateDao.getCheckpoint(target.first)?.checkpointId
                            ?: UUID.randomUUID().toString(),
                        profileId = target.first,
                        repositoryId = repository.repositoryId,
                        remoteCommitSha = target.second
                    )
                )
                repositoryDao.updateSyncHealth(
                    target.first,
                    repository.repositoryId,
                    "IDLE",
                    null,
                    System.currentTimeMillis()
                )
                replacedRepository?.credentialAlias
                    ?.takeUnless { it == alias }
                    ?.let(services.credentialStore::remove)
                provisionalAlias = null
                SyncScheduler.enqueue(
                    getApplication(),
                    target.first,
                    request.wifiOnly,
                    manual = true
                )
                message.value = "连接成功"
            }
            result.exceptionOrNull()?.let { error ->
                provisionalAlias?.let(services.credentialStore::remove)
                replacedRepository?.let { repository ->
                    repositoryDao.updateSyncHealth(
                        repository.profileId,
                        repository.repositoryId,
                        "PAUSED",
                        "新令牌验证失败",
                        System.currentTimeMillis()
                    )
                }
                message.value = error.message ?: "连接失败"
            }
            busy.value = false
            onComplete(result)
        }
    }

    fun switchProfile(profileId: String) {
        viewModelScope.launch {
            currentRepository.value?.credentialAlias?.let(services.callRegistry::cancelRequestsUsing)
            profileDao.switchToProfile(profileId)
            repositoryDao.getForProfile(profileId)?.let {
                if (it.autoSync && it.isBound) {
                    SyncScheduler.enqueue(getApplication(), profileId, it.wifiOnly)
                }
            }
            migrationPreview.value = null
            migrationStatus.value = migrationManager.status(profileId)
        }
    }

    fun loadMigrationStatus() {
        migrationStatus.value = currentProfile.value?.let { migrationManager.status(it.profileId) }
            ?: LegacyMigrationStatus.NOT_STARTED
    }

    fun refreshCacheUsage() {
        val profileId = currentProfile.value?.profileId
        if (profileId == null) {
            cacheUsage.value = CacheUsage(0, 0L)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            cacheUsage.value = assetCache.usage(profileId) + revisionStore.usage(profileId)
        }
    }

    fun cleanReconstructibleCache() {
        val profileId = currentProfile.value?.profileId ?: return
        viewModelScope.launch {
            busy.value = true
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val unreferencedAssets = assetCache.removeReconstructibleUnreferenced(profileId)
                    val orphanAssets = assetCache.cleanOrphans(profileId)
                    val revisionPaths = database.noteProjectionDao().getAllRevisions(profileId)
                        .mapTo(mutableSetOf()) { it.markdownCachePath }
                    val orphanRevisions = revisionStore.cleanOrphans(profileId, revisionPaths)
                    val deletedFiles = unreferencedAssets.deletedFiles +
                        orphanAssets.deletedFiles + orphanRevisions.deletedFiles
                    val deletedBytes = unreferencedAssets.deletedBytes +
                        orphanAssets.deletedBytes + orphanRevisions.deletedBytes
                    cacheUsage.value = assetCache.usage(profileId) + revisionStore.usage(profileId)
                    deletedFiles to deletedBytes
                }
            }
            message.value = result.fold(
                onSuccess = { (files, bytes) -> "已清理 $files 个文件（${formatBytes(bytes)}）" },
                onFailure = { it.message ?: "缓存清理失败" }
            )
            busy.value = false
        }
    }

    fun previewLegacyMigration() {
        val profileId = currentProfile.value?.profileId ?: return
        viewModelScope.launch {
            busy.value = true
            val result = runCatching {
                withContext(Dispatchers.IO) { migrationManager.preview(profileId) }
            }
            migrationPreview.value = result.getOrNull()
            message.value = result.fold(
                onSuccess = { "旧数据检查完成" },
                onFailure = { it.message ?: "旧数据检查失败" }
            )
            busy.value = false
        }
    }

    fun queueLegacyMigration(acceptScheduleDifferences: Boolean) {
        val profileId = currentProfile.value?.profileId ?: return
        viewModelScope.launch {
            busy.value = true
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    migrationManager.queueMigration(profileId, acceptScheduleDifferences)
                }
            }
            migrationPreview.value = result.getOrNull() ?: migrationPreview.value
            migrationStatus.value = migrationManager.status(profileId)
            message.value = result.fold(
                onSuccess = { "迁移已通过校验并进入待发布队列" },
                onFailure = { it.message ?: "迁移失败" }
            )
            busy.value = false
        }
    }

    fun publishLegacyMigration() {
        val profile = currentProfile.value ?: return
        val repository = currentRepository.value ?: return
        require(migrationStatus.value == LegacyMigrationStatus.QUEUED)
        SyncScheduler.enqueue(
            getApplication(),
            profile.profileId,
            repository.wifiOnly,
            manual = true
        )
        message.value = "迁移发布任务已请求"
    }

    fun rollbackLegacyMigration() {
        val profileId = currentProfile.value?.profileId ?: return
        viewModelScope.launch {
            busy.value = true
            val result = runCatching {
                withContext(Dispatchers.IO) { migrationManager.rollbackBeforePublish(profileId) }
            }
            migrationStatus.value = migrationManager.status(profileId)
            migrationPreview.value = null
            message.value = result.fold(
                onSuccess = { "已回滚到未改动的旧数据视图" },
                onFailure = { it.message ?: "回滚失败" }
            )
            busy.value = false
        }
    }

    fun rehearseLegacyRestore() {
        val profileId = currentProfile.value?.profileId ?: return
        viewModelScope.launch {
            busy.value = true
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    migrationManager.rehearseRestore(profileId, services.pullService)
                }
            }
            migrationStatus.value = migrationManager.status(profileId)
            message.value = result.fold(
                onSuccess = {
                    if (it.matchesMigration) "空投影恢复演练通过" else "恢复演练与迁移清单不一致"
                },
                onFailure = { it.message ?: "恢复演练失败" }
            )
            busy.value = false
        }
    }

    fun updateSyncPreferences(autoSync: Boolean, wifiOnly: Boolean) {
        viewModelScope.launch {
            val repository = currentRepository.value ?: return@launch
            repositoryDao.updateMutableConfiguration(
                repository.profileId,
                repository.repositoryId,
                repository.owner,
                repository.name,
                repository.branch,
                repository.credentialAlias,
                repository.isBound,
                autoSync,
                wifiOnly,
                System.currentTimeMillis()
            )
        }
    }

    fun clearToken() {
        val repository = currentRepository.value ?: return
        viewModelScope.launch {
            SyncScheduler.cancel(getApplication(), repository.profileId)
            services.callRegistry.cancelRequestsUsing(repository.credentialAlias)
            services.credentialStore.remove(repository.credentialAlias)
            repositoryDao.updateSyncHealth(
                repository.profileId,
                repository.repositoryId,
                "PAUSED",
                "令牌已清除",
                System.currentTimeMillis()
            )
            message.value = "令牌已清除"
        }
    }

    fun unbind() {
        viewModelScope.launch {
            val profile = currentProfile.value ?: return@launch
            currentRepository.value?.credentialAlias?.let(services.callRegistry::cancelRequestsUsing)
            services.bindingService.unbind(profile.profileId)
            message.value = "已解除绑定，本地数据和待推送操作已保留"
        }
    }

    fun requestManualSync() {
        val profile = currentProfile.value ?: return
        val repository = currentRepository.value ?: return
        SyncScheduler.enqueue(getApplication(), profile.profileId, repository.wifiOnly, manual = true)
        message.value = "同步任务已请求"
    }

    private fun selectBinding(binding: RepositoryBindingResult): String = when (binding) {
        is RepositoryBindingResult.Bound -> binding.profileId
        is RepositoryBindingResult.SelectExistingProfile -> binding.profileId
        is RepositoryBindingResult.Rejected -> error(binding.reason)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> "%.1f KiB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private companion object {
        const val DEFAULT_ALGORITHM_PARAMETERS =
            "{\"interval_days\":[1,2,4,7,15,30,60,120]}"
    }
}
