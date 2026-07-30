package com.ebbinghaus.review.data.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRedactorTest {
    @Test
    fun removesBearerQueryAndExplicitSecretValues() {
        val token = "private-token-123"
        val message = "Authorization: Bearer $token access_token=$token body=$token"

        val redacted = SecretRedactor.redact(message, listOf(token))

        assertFalse(redacted.contains(token))
        assertTrue(redacted.contains("Authorization: Bearer <redacted>"))
        assertTrue(redacted.contains("access_token=<redacted>"))
    }

    @Test
    fun emptySensitiveValueDoesNotRewriteMessage() {
        val message = "request failed without credentials"

        assertTrue(SecretRedactor.redact(message, listOf("")) == message)
    }
}
