package com.dreamgirl.app.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * DeepSeek 的 OpenAI 兼容接口客户端（方案 §5）。
 *
 * - 流式：POST /chat/completions, stream=true → SSE → [LlmEvent]
 * - 非流式：给记忆抽取这种「廉价小请求」用
 * - Key 由调用方通过 [apiKeyProvider] 现取，客户端自己不缓存明文
 */
class DeepSeekClient(
    private val apiKeyProvider: () -> String?,
    private val httpClient: OkHttpClient = defaultClient(),
) : LlmApi {
    private val json = Json {
        ignoreUnknownKeys = true
        // 必须是 true：stream 的默认值就是 true，若按"等于默认就省略"来编码，
        // 请求体里根本不会出现 stream，DeepSeek 会当成非流式请求 —— 整条 SSE 就哑了。
        // （这个坑是 DeepSeekClientHttpTest 拿真实 OkHttp 打本地假服务器才逮到的。）
        encodeDefaults = true
        explicitNulls = false
    }

    override fun streamChat(request: ChatCompletionRequest, baseUrl: String): Flow<LlmEvent> = callbackFlow {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            trySend(LlmEvent.Failed(LlmException(LlmErrorKind.MISSING_KEY, "还没配置 DeepSeek API Key")))
            close()
            return@callbackFlow
        }

        val call = httpClient.newCall(buildRequest(request, baseUrl, apiKey))
        val worker = launch(Dispatchers.IO) {
            var sawTerminal = false
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        trySend(LlmEvent.Failed(errorFor(response)))
                        sawTerminal = true
                        return@use
                    }
                    val body = response.body
                    if (body == null) {
                        trySend(LlmEvent.Failed(LlmException(LlmErrorKind.PARSE_ERROR, "响应体为空")))
                        sawTerminal = true
                        return@use
                    }
                    val parser = SseStreamParser { event ->
                        when (event) {
                            is SseStreamParser.SseEvent.Data -> {
                                val chunk = runCatching {
                                    json.decodeFromString(ChatCompletionChunk.serializer(), event.payload)
                                }.getOrNull()
                                if (chunk == null) {
                                    // 单条分片坏掉不该中断整轮对话
                                    return@SseStreamParser
                                }
                                val delta = chunk.choices.firstOrNull()?.delta
                                delta?.reasoningContent?.takeIf { it.isNotEmpty() }
                                    ?.let { trySend(LlmEvent.Reasoning(it)) }
                                delta?.content?.takeIf { it.isNotEmpty() }
                                    ?.let { trySend(LlmEvent.Delta(it)) }
                                chunk.usage?.let {
                                    usage = TokenUsage(
                                        promptTokens = it.promptTokens,
                                        completionTokens = it.completionTokens,
                                        totalTokens = it.totalTokens,
                                        cacheHitTokens = it.promptCacheHitTokens,
                                        cacheMissTokens = it.promptCacheMissTokens,
                                    )
                                }
                            }

                            SseStreamParser.SseEvent.Done -> {
                                sawTerminal = true
                                trySend(LlmEvent.Completed(usage))
                            }
                        }
                    }
                    val source = body.source()
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        parser.feedLine(line)
                        if (sawTerminal) break
                    }
                    if (!sawTerminal) parser.endOfStream()
                }
            } catch (e: IOException) {
                if (!sawTerminal) trySend(LlmEvent.Failed(errorFor(e)))
            } catch (e: Throwable) {
                if (!sawTerminal) {
                    trySend(LlmEvent.Failed(LlmException(LlmErrorKind.UNKNOWN, e.message ?: "未知错误", cause = e)))
                }
            } finally {
                close()
            }
        }
        awaitClose {
            call.cancel()
            worker.cancel()
        }
    }

    /** 非流式一次性补全，用于记忆抽取 / 日总结。 */
    override suspend fun complete(request: ChatCompletionRequest, baseUrl: String): Result<CompletionResult> =
        withContext(Dispatchers.IO) {
            val apiKey = apiKeyProvider()
            if (apiKey.isNullOrBlank()) {
                return@withContext Result.failure(
                    LlmException(LlmErrorKind.MISSING_KEY, "还没配置 DeepSeek API Key"),
                )
            }
            try {
                httpClient.newCall(buildRequest(request.copy(stream = false), baseUrl, apiKey)).execute()
                    .use { response ->
                        val text = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            return@withContext Result.failure(errorFor(response, text))
                        }
                        val parsed = runCatching {
                            json.decodeFromString(ChatCompletionResponse.serializer(), text)
                        }.getOrElse {
                            return@withContext Result.failure(
                                LlmException(LlmErrorKind.PARSE_ERROR, "无法解析响应：${it.message}", cause = it),
                            )
                        }
                        val content = parsed.choices.firstOrNull()?.message?.content.orEmpty()
                        Result.success(
                            CompletionResult(
                                text = content,
                                usage = parsed.usage?.let {
                                    TokenUsage(
                                        promptTokens = it.promptTokens,
                                        completionTokens = it.completionTokens,
                                        totalTokens = it.totalTokens,
                                        cacheHitTokens = it.promptCacheHitTokens,
                                        cacheMissTokens = it.promptCacheMissTokens,
                                    )
                                },
                            ),
                        )
                    }
            } catch (e: IOException) {
                Result.failure(errorFor(e))
            } catch (e: Throwable) {
                Result.failure(LlmException(LlmErrorKind.UNKNOWN, e.message ?: "未知错误", cause = e))
            }
        }

    /** 拿 Key 探一下活：给设置页的「测试连接」用。 */
    override suspend fun ping(baseUrl: String, model: String): Result<Unit> {
        val r = complete(
            ChatCompletionRequest(
                model = model,
                messages = listOf(ChatMessage.user("ping")),
                stream = false,
                maxTokens = 1,
                temperature = 0.0,
            ),
            baseUrl,
        )
        return r.map { }
    }

    private fun buildRequest(
        request: ChatCompletionRequest,
        baseUrl: String,
        apiKey: String,
    ): Request {
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val payload = json.encodeToString(ChatCompletionRequest.serializer(), request)
        return Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
    }

    private fun errorFor(response: Response, bodyText: String? = null): LlmException {
        val raw = bodyText ?: runCatching { response.body?.string() }.getOrNull().orEmpty()
        val apiMessage = runCatching {
            json.decodeFromString(ApiErrorEnvelope.serializer(), raw).error?.message
        }.getOrNull()
        val kind = LlmErrors.kindFor(response.code)
        return LlmException(
            kind = kind,
            message = LlmErrors.message(kind, apiMessage, response.code),
            httpCode = response.code,
        )
    }

    private fun errorFor(e: IOException): LlmException = when (e) {
        is SocketTimeoutException -> LlmException(LlmErrorKind.TIMEOUT, "等太久了，网络好像卡住了", cause = e)
        is UnknownHostException -> LlmException(LlmErrorKind.NETWORK, "连不上网，或者 DNS 有问题", cause = e)
        else -> LlmException(LlmErrorKind.NETWORK, "网络断了：${e.message ?: "未知原因"}", cause = e)
    }

    private var usage: TokenUsage? = null

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS) // 流式不设总时限
            .retryOnConnectionFailure(true)
            .build()
    }
}
