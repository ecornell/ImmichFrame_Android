package com.immichframe.immichframe

import java.security.MessageDigest
import java.util.ArrayDeque

internal object RpcSecurity {
    fun authorized(expected: String, supplied: String?): Boolean {
        if (expected.isBlank() || supplied.isNullOrBlank()) return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            supplied.toByteArray(Charsets.UTF_8),
        )
    }

    fun bearerToken(authorization: String?): String? {
        val prefix = "Bearer "
        return authorization?.takeIf { it.startsWith(prefix, ignoreCase = true) }
            ?.substring(prefix.length)?.trim()
    }
}

internal class SlidingWindowRateLimiter(
    private val limit: Int = 30,
    private val windowMillis: Long = 60_000,
) {
    private val requests = ArrayDeque<Long>()

    @Synchronized
    fun allow(nowMillis: Long = System.currentTimeMillis()): Boolean {
        while (requests.isNotEmpty() && nowMillis - requests.first() >= windowMillis) {
            requests.removeFirst()
        }
        if (requests.size >= limit) return false
        requests.addLast(nowMillis)
        return true
    }
}
