package com.ebbinghaus.review.data.security

import android.util.Log

object SecretRedactor {
    private val bearerPattern = Regex("(?i)(authorization\\s*[:=]\\s*bearer\\s+)[^\\s,;]+")
    private val tokenParameterPattern = Regex("(?i)(access_token\\s*[=:]\\s*)[^&\\s,;}]+")

    fun redact(message: String, sensitiveValues: Iterable<String> = emptyList()): String {
        var sanitized = bearerPattern.replace(message, "$1<redacted>")
        sanitized = tokenParameterPattern.replace(sanitized, "$1<redacted>")
        sensitiveValues.filter { it.isNotEmpty() }.forEach { value ->
            sanitized = sanitized.replace(value, "<redacted>")
        }
        return sanitized
    }
}

interface SecureLogger {
    fun debug(tag: String, message: String, sensitiveValues: Iterable<String> = emptyList())
    fun warning(tag: String, message: String, sensitiveValues: Iterable<String> = emptyList())
    fun error(
        tag: String,
        message: String,
        throwable: Throwable? = null,
        sensitiveValues: Iterable<String> = emptyList()
    )
}

object AndroidSecureLogger : SecureLogger {
    override fun debug(tag: String, message: String, sensitiveValues: Iterable<String>) {
        Log.d(tag, SecretRedactor.redact(message, sensitiveValues))
    }

    override fun warning(tag: String, message: String, sensitiveValues: Iterable<String>) {
        Log.w(tag, SecretRedactor.redact(message, sensitiveValues))
    }

    override fun error(
        tag: String,
        message: String,
        throwable: Throwable?,
        sensitiveValues: Iterable<String>
    ) {
        Log.e(tag, SecretRedactor.redact(message, sensitiveValues), throwable)
    }
}
