package com.ebbinghaus.review.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

fun interface CredentialInvalidationHandler {
    fun cancelRequestsUsing(credentialAlias: String)
}

interface CredentialStore {
    fun contains(credentialAlias: String): Boolean
    fun save(credentialAlias: String, personalAccessToken: String)
    fun remove(credentialAlias: String)
    fun <T> withCredential(credentialAlias: String, block: (CharArray) -> T): T?
}

class KeystoreCredentialStore(
    context: Context,
    private val invalidationHandler: CredentialInvalidationHandler = CredentialInvalidationHandler { }
) : CredentialStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override fun contains(credentialAlias: String): Boolean = synchronized(this) {
        preferences.contains(preferenceKey(credentialAlias))
    }

    override fun save(credentialAlias: String, personalAccessToken: String) {
        require(credentialAlias.isNotBlank()) { "Credential alias cannot be blank" }
        require(personalAccessToken.isNotBlank()) { "Personal access token cannot be blank" }
        synchronized(this) {
            invalidationHandler.cancelRequestsUsing(credentialAlias)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(keyAlias(credentialAlias)))
            val ciphertext = cipher.doFinal(personalAccessToken.toByteArray(Charsets.UTF_8))
            val payload = ByteBuffer.allocate(2 + cipher.iv.size + ciphertext.size)
                .put(VERSION)
                .put(cipher.iv.size.toByte())
                .put(cipher.iv)
                .put(ciphertext)
                .array()
            check(
                preferences.edit()
                    .putString(preferenceKey(credentialAlias), Base64.encodeToString(payload, Base64.NO_WRAP))
                    .commit()
            ) { "Unable to persist encrypted credential" }
        }
    }

    override fun remove(credentialAlias: String) {
        synchronized(this) {
            invalidationHandler.cancelRequestsUsing(credentialAlias)
            preferences.edit().remove(preferenceKey(credentialAlias)).commit()
            val keyAlias = keyAlias(credentialAlias)
            if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
        }
    }

    override fun <T> withCredential(credentialAlias: String, block: (CharArray) -> T): T? {
        val credential = synchronized(this) {
            val encoded = preferences.getString(preferenceKey(credentialAlias), null)
                ?: return@synchronized null
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            val buffer = ByteBuffer.wrap(payload)
            require(buffer.get() == VERSION) { "Unsupported encrypted credential version" }
            val ivSize = buffer.get().toInt() and 0xff
            require(ivSize in 12..16 && buffer.remaining() > ivSize) {
                "Malformed encrypted credential"
            }
            val iv = ByteArray(ivSize).also(buffer::get)
            val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                keyStore.getKey(keyAlias(credentialAlias), null) as SecretKey,
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            val plaintextBytes = cipher.doFinal(ciphertext)
            val decrypted = plaintextBytes.toString(Charsets.UTF_8).toCharArray()
            plaintextBytes.fill(0)
            decrypted
        } ?: return null
        return try {
            block(credential)
        } finally {
            credential.fill('\u0000')
        }
    }

    private fun getOrCreateKey(alias: String): SecretKey {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }

    private fun keyAlias(credentialAlias: String): String =
        "$KEY_ALIAS_PREFIX${sha256(credentialAlias)}"

    private fun preferenceKey(credentialAlias: String): String =
        "$PREFERENCE_KEY_PREFIX${sha256(credentialAlias)}"

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val PREFERENCES_NAME = "secure_gitee_credentials"
        const val KEY_ALIAS_PREFIX = "ebbinghaus.gitee."
        const val PREFERENCE_KEY_PREFIX = "credential."
        const val VERSION: Byte = 1
    }
}
