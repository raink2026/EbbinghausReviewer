package com.ebbinghaus.review.data.sync

import android.content.Context
import com.ebbinghaus.review.data.AppDatabase
import com.ebbinghaus.review.data.security.KeystoreCredentialStore
import com.ebbinghaus.review.data.sync.remote.CredentialCallRegistry
import com.ebbinghaus.review.data.sync.remote.OkHttpGiteeTransport
import com.ebbinghaus.review.data.sync.remote.RepositoryConnectionService
import com.ebbinghaus.review.data.sync.remote.GiteePullService
import com.ebbinghaus.review.data.sync.remote.GiteePushService

class SyncServices private constructor(context: Context) {
    val database: AppDatabase = AppDatabase.getDatabase(context)
    val callRegistry = CredentialCallRegistry()
    val credentialStore = KeystoreCredentialStore(context, callRegistry)
    val transport = OkHttpGiteeTransport(credentialStore, callRegistry)
    val connectionService = RepositoryConnectionService(transport) { assetPath ->
        context.assets.open(assetPath).use { it.readBytes() }
    }
    val bindingService = RepositoryBindingService(database)
    val pullService = GiteePullService(context, database, transport)
    val pushService = GiteePushService(database, transport, pullService)

    companion object {
        @Volatile
        private var instance: SyncServices? = null

        fun get(context: Context): SyncServices = instance ?: synchronized(this) {
            instance ?: SyncServices(context.applicationContext).also { instance = it }
        }
    }
}
