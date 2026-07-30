package com.ebbinghaus.review.data.sync

import androidx.room.withTransaction
import com.ebbinghaus.review.data.AppDatabase

data class RepositoryProfileIdentity(
    val repositoryId: String,
    val profileId: String,
    val timezone: String,
    val algorithmId: String,
    val algorithmVersion: Int,
    val algorithmParametersJson: String
)

data class RepositoryLocation(
    val owner: String,
    val name: String,
    val branch: String = "main"
)

sealed interface RepositoryBindingResult {
    data class Bound(
        val profileId: String,
        val repositoryId: String,
        val importedProfile: Boolean
    ) : RepositoryBindingResult

    data class SelectExistingProfile(val profileId: String) : RepositoryBindingResult

    data class Rejected(val reason: String) : RepositoryBindingResult
}

class RepositoryBindingService(private val database: AppDatabase) {
    suspend fun bindInitializedRepository(
        identity: RepositoryProfileIdentity,
        location: RepositoryLocation,
        credentialAlias: String,
        displayName: String
    ): RepositoryBindingResult = database.withTransaction {
        val profileDao = database.profileDao()
        val repositoryDao = database.remoteRepositoryDao()
        val identityBinding = repositoryDao.findIdentityBinding(identity.repositoryId)

        if (identityBinding != null && identityBinding.profileId != identity.profileId) {
            return@withTransaction RepositoryBindingResult.SelectExistingProfile(
                identityBinding.profileId
            )
        }

        val existingProfile = profileDao.getProfile(identity.profileId)
        val existingProfileRepository = repositoryDao.getForProfile(identity.profileId)
        if (
            existingProfileRepository != null &&
            existingProfileRepository.repositoryId != identity.repositoryId
        ) {
            return@withTransaction RepositoryBindingResult.Rejected(
                "Profile ${identity.profileId} is already bound to another repository identity"
            )
        }

        val importedProfile = existingProfile == null
        if (existingProfile == null) {
            profileDao.insert(
                Profile(
                    profileId = identity.profileId,
                    displayName = displayName,
                    timezone = identity.timezone,
                    algorithmId = identity.algorithmId,
                    algorithmVersion = identity.algorithmVersion,
                    algorithmParametersJson = identity.algorithmParametersJson
                )
            )
        } else if (!existingProfile.matches(identity)) {
            return@withTransaction RepositoryBindingResult.Rejected(
                "Repository profile metadata does not match the existing local profile"
            )
        }

        val now = System.currentTimeMillis()
        if (existingProfileRepository == null) {
            repositoryDao.insert(
                RemoteRepository(
                    repositoryId = identity.repositoryId,
                    profileId = identity.profileId,
                    owner = location.owner,
                    name = location.name,
                    branch = location.branch,
                    credentialAlias = credentialAlias,
                    createdAt = now,
                    updatedAt = now
                )
            )
        } else {
            check(
                repositoryDao.updateMutableConfiguration(
                    profileId = identity.profileId,
                    repositoryId = identity.repositoryId,
                    owner = location.owner,
                    name = location.name,
                    branch = location.branch,
                    credentialAlias = credentialAlias,
                    isBound = true,
                    autoSync = existingProfileRepository.autoSync,
                    wifiOnly = existingProfileRepository.wifiOnly,
                    updatedAt = now
                ) == 1
            )
        }

        RepositoryBindingResult.Bound(
            profileId = identity.profileId,
            repositoryId = identity.repositoryId,
            importedProfile = importedProfile
        )
    }

    suspend fun initializeEmptyRepository(
        profile: Profile,
        repositoryId: String,
        location: RepositoryLocation,
        credentialAlias: String
    ): RepositoryBindingResult = database.withTransaction {
        val repositoryDao = database.remoteRepositoryDao()
        val existingIdentity = repositoryDao.findIdentityBinding(repositoryId)
        if (existingIdentity != null) {
            return@withTransaction RepositoryBindingResult.SelectExistingProfile(
                existingIdentity.profileId
            )
        }
        if (database.profileDao().getProfile(profile.profileId) != null) {
            return@withTransaction RepositoryBindingResult.Rejected(
                "A new repository requires a new profile identity"
            )
        }

        database.profileDao().insert(profile)
        repositoryDao.insert(
            RemoteRepository(
                repositoryId = repositoryId,
                profileId = profile.profileId,
                owner = location.owner,
                name = location.name,
                branch = location.branch,
                credentialAlias = credentialAlias
            )
        )
        RepositoryBindingResult.Bound(profile.profileId, repositoryId, importedProfile = true)
    }

    suspend fun unbind(profileId: String): Boolean = database.withTransaction {
        val repository = database.remoteRepositoryDao().getForProfile(profileId)
            ?: return@withTransaction false
        database.remoteRepositoryDao().markUnbound(
            profileId = profileId,
            repositoryId = repository.repositoryId,
            updatedAt = System.currentTimeMillis()
        ) == 1
    }

    private fun Profile.matches(identity: RepositoryProfileIdentity): Boolean =
        timezone == identity.timezone &&
            algorithmId == identity.algorithmId &&
            algorithmVersion == identity.algorithmVersion &&
            algorithmParametersJson == identity.algorithmParametersJson
}
