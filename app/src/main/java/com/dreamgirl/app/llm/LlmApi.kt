package com.dreamgirl.app.llm

import kotlinx.coroutines.flow.Flow

/** 一次非流式补全的结果。 */
data class CompletionResult(val text: String, val usage: TokenUsage?)

/**
 * 模型接口。放在接口后面有两个理由：
 *  1. 核心编排（ChatEngine / MemoryEngine）不该知道 OkHttp、SSE、HTTP 状态码的存在；
 *  2. 没有网络、没有手机时也能用替身把整条编排链路测穿。
 */
interface LlmApi {
    fun streamChat(request: ChatCompletionRequest, baseUrl: String): Flow<LlmEvent>
    suspend fun complete(request: ChatCompletionRequest, baseUrl: String): Result<CompletionResult>
    suspend fun ping(baseUrl: String, model: String): Result<Unit>
}
