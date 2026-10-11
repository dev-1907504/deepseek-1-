package com.dreamgirl.app.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验收标准第 6 条：断网 / Key 失效要给出明确提示，而不是崩溃。 */
class LlmErrorsTest {

    @Test
    fun `401 maps to invalid key`() {
        assertEquals(LlmErrorKind.INVALID_KEY, LlmErrors.kindFor(401))
        assertEquals("API Key 无效或已失效", LlmErrors.message(LlmErrorKind.INVALID_KEY, null, 401))
    }

    @Test
    fun `429 maps to rate limited`() {
        assertEquals(LlmErrorKind.RATE_LIMITED, LlmErrors.kindFor(429))
        assertTrue(LlmErrors.message(LlmErrorKind.RATE_LIMITED, null, 429).contains("太频繁"))
    }

    @Test
    fun `402 maps to insufficient balance`() {
        assertEquals(LlmErrorKind.INSUFFICIENT_BALANCE, LlmErrors.kindFor(402))
    }

    @Test
    fun `5xx maps to server error and carries the code`() {
        assertEquals(LlmErrorKind.SERVER_ERROR, LlmErrors.kindFor(503))
        assertTrue(LlmErrors.message(LlmErrorKind.SERVER_ERROR, null, 503).contains("503"))
    }

    @Test
    fun `unknown 4xx does not get misreported as a key problem`() {
        assertEquals(LlmErrorKind.UNKNOWN, LlmErrors.kindFor(418))
    }

    @Test
    fun `server supplied message wins over ours`() {
        val msg = LlmErrors.message(LlmErrorKind.INVALID_KEY, "Authentication Fails", 401)
        assertEquals("Authentication Fails", msg)
    }

    @Test
    fun `blank server message falls back to ours`() {
        assertEquals("API Key 无效或已失效", LlmErrors.message(LlmErrorKind.INVALID_KEY, "   ", 401))
    }

    @Test
    fun `network and server problems are retryable`() {
        assertTrue(LlmErrors.isRetryable(LlmErrorKind.NETWORK))
        assertTrue(LlmErrors.isRetryable(LlmErrorKind.TIMEOUT))
        assertTrue(LlmErrors.isRetryable(LlmErrorKind.RATE_LIMITED))
        assertTrue(LlmErrors.isRetryable(LlmErrorKind.SERVER_ERROR))
    }

    @Test
    fun `bad key and bad balance are never retried`() {
        assertFalse(LlmErrors.isRetryable(LlmErrorKind.INVALID_KEY))
        assertFalse(LlmErrors.isRetryable(LlmErrorKind.INSUFFICIENT_BALANCE))
        assertFalse(LlmErrors.isRetryable(LlmErrorKind.BAD_REQUEST))
    }

    @Test
    fun `every kind has a human readable message`() {
        LlmErrorKind.entries.forEach { kind ->
            val m = LlmErrors.message(kind, null, 500)
            assertTrue("$kind 没有文案", m.isNotBlank())
        }
    }

    @Test
    fun `exception retryable flag follows the kind`() {
        assertTrue(LlmException(LlmErrorKind.NETWORK, "x").retryable)
        assertFalse(LlmException(LlmErrorKind.INVALID_KEY, "x").retryable)
    }
}
