package com.immichframe.immichframe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RpcSecurityTest {
    @Test fun requiresNonEmptyExactBearerToken() {
        assertFalse(RpcSecurity.authorized("", ""))
        assertFalse(RpcSecurity.authorized("secret", null))
        assertFalse(RpcSecurity.authorized("secret", "Secret"))
        assertTrue(RpcSecurity.authorized("secret", "secret"))
        assertEquals("abc", RpcSecurity.bearerToken("Bearer abc"))
    }

    @Test fun limiterRejectsBurstAndRecovers() {
        val limiter = SlidingWindowRateLimiter(limit = 2, windowMillis = 100)
        assertTrue(limiter.allow(0))
        assertTrue(limiter.allow(1))
        assertFalse(limiter.allow(2))
        assertTrue(limiter.allow(101))
    }
}
