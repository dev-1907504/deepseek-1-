package com.dreamgirl.app.llm

/** 流式对话对外暴露的事件。UI 只认这几种，网络细节不出这一层。 */
sealed interface LlmEvent {
    /** 增量文本（已经过 SSE 解码，可直接追加到气泡）。 */
    data class Delta(val text: String) : LlmEvent

    /** 思考链增量（deepseek-reasoner 专用，普通对话不会有）。 */
    data class Reasoning(val text: String) : LlmEvent

    /** 本轮结束。usage 可能为空（部分分片不带）。 */
    data class Completed(val usage: TokenUsage?) : LlmEvent

    /** 失败。调用方按 [LlmErrorKind] 决定提示文案与是否重试。 */
    data class Failed(val error: LlmException) : LlmEvent
}

data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val cacheHitTokens: Int = 0,
    val cacheMissTokens: Int = 0,
)

/** 错误分类——方案 §5「断网 / 401 / 429 分别给明确提示」。 */
enum class LlmErrorKind {
    MISSING_KEY,
    INVALID_KEY,
    INSUFFICIENT_BALANCE,
    RATE_LIMITED,
    SERVER_ERROR,
    NETWORK,
    TIMEOUT,
    BAD_REQUEST,
    PARSE_ERROR,
    CANCELLED,
    UNKNOWN,
}

class LlmException(
    val kind: LlmErrorKind,
    override val message: String,
    val httpCode: Int? = null,
    val retryable: Boolean = when (kind) {
        LlmErrorKind.RATE_LIMITED,
        LlmErrorKind.SERVER_ERROR,
        LlmErrorKind.NETWORK,
        LlmErrorKind.TIMEOUT,
        -> true
        else -> false
    },
    cause: Throwable? = null,
) : Exception(message, cause)
