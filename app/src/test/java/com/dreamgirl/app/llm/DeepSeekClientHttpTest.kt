package com.dreamgirl.app.llm

import com.dreamgirl.app.fake.FakeKeys
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/**
 * 把**真实的** [DeepSeekClient]（含 OkHttp、序列化、SSE 解码）对着一个本地假服务器跑穿。
 *
 * 这一步补上了之前测试的空档：SSE 解析器单测只验证了"给我一行字符串我能解析对"，
 * 而这里验证的是真网络栈那一整条：URL 拼接、请求头、请求体、逐行读取、[DONE]、usage、
 * 以及 401/429/500 的错误体解析。不需要 API Key，也不会发出任何外部请求。
 */
class DeepSeekClientHttpTest {

    private lateinit var server: MockWebServer
    private var baseUrl: String = ""

    /** 最近一次收到的请求，供断言使用。 */
    private var lastPath: String? = null
    private var lastAuth: String? = null
    private var lastBody: String? = null

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("/").toString().trimEnd('/')
    }

    @After
    fun stop() {
        runCatching { server.shutdown() }
    }

    /** 记录下这次请求，便于断言。 */
    private fun record() {
        val r = server.takeRequest()
        lastPath = r.path
        lastAuth = r.getHeader("Authorization")
        lastBody = r.body.readUtf8()
    }

    private fun reply(code: Int, body: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(body),
        )
    }

    /** 按 DeepSeek 的格式吐一段 SSE。 */
    private fun sse(chunks: List<String>, done: Boolean = true) {
        val sb = StringBuilder()
        chunks.forEach { sb.append("data: ").append(it).append("\n\n") }
        if (done) sb.append("data: [DONE]\n\n")
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sb.toString()),
        )
    }

    private fun client(key: String? = "sk-test-key-123456") = DeepSeekClient(apiKeyProvider = { key })

    private fun req(stream: Boolean = true) = ChatCompletionRequest(
        model = "deepseek-chat",
        messages = listOf(ChatMessage.system("人设"), ChatMessage.user("在吗")),
        stream = stream,
        temperature = 1.0,
        maxTokens = 300,
    )

    // ------------------------------------------------------------------ 流式

    @Test
    fun `streams deltas then completes`() = runTest {
        sse(
            listOf(
                """{"choices":[{"delta":{"role":"assistant","content":""}}]}""",
                """{"choices":[{"delta":{"content":"（尾巴翘起来）"}}]}""",
                """{"choices":[{"delta":{"content":"在的。"}}]}""",
                """{"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":12,"completion_tokens":7,"total_tokens":19,"prompt_cache_hit_tokens":10,"prompt_cache_miss_tokens":2}}""",
            ),
        )

        val events = client().streamChat(req(), baseUrl).toList()

        val text = events.filterIsInstance<LlmEvent.Delta>().joinToString("") { it.text }
        assertEquals("（尾巴翘起来）在的。", text)

        val done = events.filterIsInstance<LlmEvent.Completed>().single()
        assertEquals(12, done.usage?.promptTokens)
        assertEquals(19, done.usage?.totalTokens)
        assertEquals(10, done.usage?.cacheHitTokens)
        assertFalse(events.any { it is LlmEvent.Failed })
    }

    @Test
    fun `request is well formed`() = runTest {
        sse(listOf("""{"choices":[{"delta":{"content":"ok"}}]}"""))
        client().streamChat(req(), baseUrl).toList()
        record()

        assertEquals("/chat/completions", lastPath)
        assertEquals("Bearer sk-test-key-123456", lastAuth)
        val b = lastBody!!
        assertTrue("必须带 stream=true", b.contains("\"stream\":true"))
        assertTrue(b.contains("\"model\":\"deepseek-chat\""))
        assertTrue(b.contains("人设"))
        assertTrue(b.contains("max_tokens") || b.contains("maxTokens") || b.contains("300"))
    }

    @Test
    fun `regression - stream true must actually be sent on the wire`() = runTest {
        // 曾经的真实 bug：encodeDefaults=false 让 stream 被省略，
        // DeepSeek 于是按非流式回，聊天页永远收不到字。
        sse(listOf("""{"choices":[{"delta":{"content":"x"}}]}"""))
        client().streamChat(req(stream = true), baseUrl).toList()
        record()
        assertTrue("请求体里必须出现 \"stream\":true", lastBody!!.contains("\"stream\":true"))
    }

    @Test
    fun `reasoning content is surfaced separately`() = runTest {
        sse(
            listOf(
                """{"choices":[{"delta":{"reasoning_content":"先想一下"}}]}""",
                """{"choices":[{"delta":{"content":"答案。"}}]}""",
            ),
        )
        val events = client().streamChat(req(), baseUrl).toList()
        assertEquals("先想一下", events.filterIsInstance<LlmEvent.Reasoning>().single().text)
        assertEquals("答案。", events.filterIsInstance<LlmEvent.Delta>().single().text)
    }

    @Test
    fun `a stream without an explicit done sentinel still completes`() = runTest {
        sse(listOf("""{"choices":[{"delta":{"content":"半句"}}]}"""), done = false)
        val events = client().streamChat(req(), baseUrl).toList()
        assertEquals("半句", events.filterIsInstance<LlmEvent.Delta>().single().text)
        assertTrue(events.last() is LlmEvent.Completed)
    }

    @Test
    fun `a malformed chunk does not kill the whole turn`() = runTest {
        sse(
            listOf(
                """{"choices":[{"delta":{"content":"好"}}]}""",
                """{ this is not json """,
                """{"choices":[{"delta":{"content":"的"}}]}""",
            ),
        )
        val events = client().streamChat(req(), baseUrl).toList()
        assertEquals("好的", events.filterIsInstance<LlmEvent.Delta>().joinToString("") { it.text })
        assertFalse(events.any { it is LlmEvent.Failed })
    }

    // ------------------------------------------------------------------ 错误

    @Test
    fun `401 becomes an invalid key failure and is not retryable`() = runTest {
        reply(401, """{"error":{"message":"Authentication Fails, Your api key: ****test is invalid","type":"authentication_error"}}""")
        val ev = client().streamChat(req(), baseUrl).toList().filterIsInstance<LlmEvent.Failed>().single()
        assertEquals(LlmErrorKind.INVALID_KEY, ev.error.kind)
        assertTrue(ev.error.message.contains("Authentication Fails"))
        assertFalse(ev.error.retryable)
    }

    @Test
    fun `429 is retryable`() = runTest {
        reply(429, """{"error":{"message":"Rate limit reached"}}""")
        val ev = client().streamChat(req(), baseUrl).toList().filterIsInstance<LlmEvent.Failed>().single()
        assertEquals(LlmErrorKind.RATE_LIMITED, ev.error.kind)
        assertTrue(ev.error.retryable)
    }

    @Test
    fun `402 means out of balance`() = runTest {
        reply(402, """{"error":{"message":"Insufficient Balance"}}""")
        val ev = client().streamChat(req(), baseUrl).toList().filterIsInstance<LlmEvent.Failed>().single()
        assertEquals(LlmErrorKind.INSUFFICIENT_BALANCE, ev.error.kind)
        assertFalse(ev.error.retryable)
    }

    @Test
    fun `500 is retryable and carries the code`() = runTest {
        reply(503, """{"error":{"message":"server busy"}}""")
        val ev = client().streamChat(req(), baseUrl).toList().filterIsInstance<LlmEvent.Failed>().single()
        assertEquals(LlmErrorKind.SERVER_ERROR, ev.error.kind)
        assertEquals(503, ev.error.httpCode)
        assertTrue(ev.error.retryable)
    }

    @Test
    fun `a missing key never leaves the device`() = runTest {
        val events = client(key = null).streamChat(req(), baseUrl).toList()
        val ev = events.filterIsInstance<LlmEvent.Failed>().single()
        assertEquals(LlmErrorKind.MISSING_KEY, ev.error.kind)
        assertEquals("根本没发请求出去", null, lastPath)
    }

    // ------------------------------------------------------------------ 非流式

    @Test
    fun `non streaming completion returns content and usage`() = runTest {
        reply(200, """{"choices":[{"message":{"role":"assistant","content":"{\"l1\":\"主线\"}"}}],"usage":{"prompt_tokens":30,"completion_tokens":9,"total_tokens":39}}""")
        val r = client().complete(req(stream = false), baseUrl).getOrThrow()
        record()
        assertEquals("""{"l1":"主线"}""", r.text)
        assertEquals(39, r.usage?.totalTokens)
        assertTrue("非流式请求不能带 stream=true", lastBody!!.contains("\"stream\":false"))
    }

    @Test
    fun `non streaming failure is returned as a failure not an exception`() = runTest {
        reply(401, """{"error":{"message":"bad key"}}""")
        val r = client().complete(req(stream = false), baseUrl)
        assertTrue(r.isFailure)
        assertEquals(LlmErrorKind.INVALID_KEY, (r.exceptionOrNull() as LlmException).kind)
    }

    @Test
    fun `ping reports success against a healthy endpoint`() = runTest {
        reply(200, """{"choices":[{"message":{"role":"assistant","content":"pong"}}]}""")
        assertTrue(client().ping(baseUrl, "deepseek-chat").isSuccess)
    }

    @Test
    fun `a trailing slash in the base url does not produce a double slash`() = runTest {
        reply(200, """{"choices":[{"message":{"role":"assistant","content":"ok"}}]}""")
        client().complete(req(stream = false), "$baseUrl/").getOrThrow()
        record()
        assertEquals("/chat/completions", lastPath)
    }
}
