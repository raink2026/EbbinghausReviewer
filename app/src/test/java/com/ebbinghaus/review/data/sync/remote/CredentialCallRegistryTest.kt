package com.ebbinghaus.review.data.sync.remote

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialCallRegistryTest {
    @Test
    fun credentialRotationCancelsOnlyCallsUsingReplacedAlias() {
        val registry = CredentialCallRegistry()
        val client = OkHttpClient()
        val oldAliasCall = client.newCall(Request.Builder().url("https://example.com/old").build())
        val otherAliasCall = client.newCall(Request.Builder().url("https://example.com/other").build())
        registry.register("old-alias", oldAliasCall)
        registry.register("other-alias", otherAliasCall)

        registry.cancelRequestsUsing("old-alias")

        assertTrue(oldAliasCall.isCanceled())
        assertFalse(otherAliasCall.isCanceled())
    }

    @Test
    fun unregisteredCallIsNotCancelledLater() {
        val registry = CredentialCallRegistry()
        val call = OkHttpClient().newCall(Request.Builder().url("https://example.com/request").build())
        registry.register("alias", call)
        registry.unregister("alias", call)

        registry.cancelRequestsUsing("alias")

        assertFalse(call.isCanceled())
    }
}
