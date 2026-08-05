package com.youtv.app.domain.model

import java.security.MessageDigest
import java.util.Locale

object SourceIdentity {
    fun channelKey(name: String): String = name
        .lowercase(Locale.ROOT)
        .replace('＋', '+')
        .replace(Regex("[\\s_\\-—–·()（）\\[\\]【】「」]+"), "")

    fun fingerprint(source: StreamSource): String {
        val canonicalHeaders = source.headers.entries
            .sortedBy { it.key.lowercase(Locale.ROOT) }
            .joinToString("\u0001") { "${it.key.lowercase(Locale.ROOT)}=${it.value}" }
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("${source.url}\u0000$canonicalHeaders".toByteArray())
        return bytes.take(16).joinToString("") { "%02x".format(it) }
    }
}

data class BlockedSource(
    val channelKey: String,
    val sourceFingerprint: String,
    val channelId: String,
    val channelName: String,
    val sourceNumber: Int,
    val sourceUrl: String = "",
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val blockedAt: Long = System.currentTimeMillis(),
)
