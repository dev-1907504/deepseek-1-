package com.dreamgirl.app.llm

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * 对着**真实的** api.deepseek.com 打一次，验证最后一公里：
 * TLS 握手、HTTP 版本协商、错误体解析，都要在真服务器上成立。
 *
 * 默认跳过（会联网），跑法：
 *   DREAMGIRL_LIVE=1 gradle testDebugUnitTest --tests "*LiveEndpointSmokeTest*"
 *
 * 用无效 Key 是有意的：不花钱、不消耗额度，但能把
 * "网络能通 + 我们的错误解析对得上真服务器" 这两件事一起验掉。
 */
class LiveEndpointSmokeTest {

    private val baseUrl = "https://api.deepseek.com"

    private fun request() = ChatCompletionRequest(
        model = "deepseek-chat",
        messages = listOf(ChatMessage.system("你是 dream。"), ChatMessage.user("在吗")),
        stream = true,
        temperature = 1.0,
        maxTokens = 16,
    )

    @Test
    fun `a bad key against the real endpoint is understood as an invalid key`() = runTest {
        Assume.assumeTrue(
            "设置 DREAMGIRL_LIVE=1 才跑这条（会真的联网）",
            System.getenv("DREAMGIRL_LIVE") == "1",
        )

        val client = DeepSeekClient(apiKeyProvider = { "sk-0000000000000000000000000000000000000000" })
        val events = client.streamChat(request(), baseUrl).toList()

        val failed = events.filterIsInstance<LlmEvent.Failed>().singleOrNull()
            ?: error("应该收到一个 Failed，实际收到：$events")

        assertEquals(LlmErrorKind.INVALID_KEY, failed.error.kind)
        assertEquals(401, failed.error.httpCode)
        assertFalse("Key 错不该重试", failed.error.retryable)
        assertTrue("要有能给人看的话", failed.error.message.isNotBlank())
        // 真服务器给了人话就用人话
        assertTrue(
            "应当把服务端的说明透出来，实际是：${failed.error.message}",
            failed.error.message.contains("Authentication", ignoreCase = true) ||
                failed.error.message.contains("invalid", ignoreCase = true),
        )
    }

    @Test
    fun `the non streaming path reaches the real endpoint too`() = runTest {
        Assume.assumeTrue(
            "设置 DREAMGIRL_LIVE=1 才跑这条（会真的联网）",
            System.getenv("DREAMGIRL_LIVE") == "1",
        )
        val client = DeepSeekClient(apiKeyProvider = { "sk-0000000000000000000000000000000000000000" })
        val r = client.complete(request().copy(stream = false), baseUrl)
        assertTrue("无效 Key 应当失败，而不是抛异常", r.isFailure)
        assertEquals(LlmErrorKind.INVALID_KEY, (r.exceptionOrNull() as LlmException).kind)
    }
}
