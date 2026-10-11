package com.dreamgirl.app.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** OpenAI 兼容的对话消息。DeepSeek 直接沿用这套 schema。 */
@Serializable
data class ChatMessage(
    /** 请求里我们总是显式指定；响应里万一缺省，按 assistant 兜底，别让整轮解析失败。 */
    val role: String = ROLE_ASSISTANT,
    val content: String,
) {
    companion object {
        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        fun system(c: String) = ChatMessage(ROLE_SYSTEM, c)
        fun user(c: String) = ChatMessage(ROLE_USER, c)
        fun assistant(c: String) = ChatMessage(ROLE_ASSISTANT, c)
    }
}

/** 请求体。`stream=true` 时走 SSE。 */
@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = true,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("frequency_penalty") val frequencyPenalty: Double? = null,
    @SerialName("presence_penalty") val presencePenalty: Double? = null,
)

/** 流式分片。只取我们真正要用的字段，其余忽略。 */
@Serializable
data class ChatCompletionChunk(
    val id: String? = null,
    val model: String? = null,
    val choices: List<Choice> = emptyList(),
    val usage: Usage? = null,
) {
    @Serializable
    data class Choice(
        val index: Int = 0,
        val delta: Delta? = null,
        @SerialName("finish_reason") val finishReason: String? = null,
    )

    @Serializable
    data class Delta(
        val role: String? = null,
        val content: String? = null,
        /** deepseek-reasoner 的思考链；普通对话为空。 */
        @SerialName("reasoning_content") val reasoningContent: String? = null,
    )

    @Serializable
    data class Usage(
        @SerialName("prompt_tokens") val promptTokens: Int = 0,
        @SerialName("completion_tokens") val completionTokens: Int = 0,
        @SerialName("total_tokens") val totalTokens: Int = 0,
        @SerialName("prompt_cache_hit_tokens") val promptCacheHitTokens: Int = 0,
        @SerialName("prompt_cache_miss_tokens") val promptCacheMissTokens: Int = 0,
    )
}

/** 非流式响应（用于记忆抽取这类"廉价小请求"）。 */
@Serializable
data class ChatCompletionResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<Choice> = emptyList(),
    val usage: ChatCompletionChunk.Usage? = null,
) {
    @Serializable
    data class Choice(
        val index: Int = 0,
        val message: ChatMessage? = null,
        @SerialName("finish_reason") val finishReason: String? = null,
    )
}

/** DeepSeek 出错时的响应体。 */
@Serializable
data class ApiErrorEnvelope(
    val error: ApiError? = null,
)

@Serializable
data class ApiError(
    val message: String = "",
    val type: String? = null,
    val code: String? = null,
    val param: String? = null,
)
