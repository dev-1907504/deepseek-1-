package com.dreamgirl.app.llm

/**
 * HTTP / IO 错误 → 用户看得懂的一句话（方案 §5「断网 / 401 / 429 分别给明确提示」）。
 *
 * 单独拆出来是因为：这段逻辑必须在**没有网络、没有 Android** 的情况下也能被单测覆盖。
 * 「Key 失效时不崩溃、给明确提示」是验收标准第 6 条，值得一条测试。
 */
object LlmErrors {

    fun kindFor(httpCode: Int): LlmErrorKind = when (httpCode) {
        400 -> LlmErrorKind.BAD_REQUEST
        401 -> LlmErrorKind.INVALID_KEY
        402 -> LlmErrorKind.INSUFFICIENT_BALANCE
        403 -> LlmErrorKind.INVALID_KEY
        422 -> LlmErrorKind.BAD_REQUEST
        429 -> LlmErrorKind.RATE_LIMITED
        in 500..599 -> LlmErrorKind.SERVER_ERROR
        else -> LlmErrorKind.UNKNOWN
    }

    /** 服务端给了人话就用人话，否则给我们的分类文案。 */
    fun message(kind: LlmErrorKind, apiMessage: String?, httpCode: Int?): String {
        apiMessage?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return when (kind) {
            LlmErrorKind.MISSING_KEY -> "还没配置 DeepSeek API Key"
            LlmErrorKind.INVALID_KEY -> "API Key 无效或已失效"
            LlmErrorKind.INSUFFICIENT_BALANCE -> "DeepSeek 账户余额不足"
            LlmErrorKind.RATE_LIMITED -> "请求太频繁了，歇一下再试"
            LlmErrorKind.SERVER_ERROR -> "DeepSeek 服务器出问题了" + (httpCode?.let { "（HTTP $it）" } ?: "")
            LlmErrorKind.TIMEOUT -> "等太久了，网络好像卡住了"
            LlmErrorKind.NETWORK -> "连不上网，或者 DNS 有问题"
            LlmErrorKind.BAD_REQUEST -> "这个请求 DeepSeek 不认" + (httpCode?.let { "（HTTP $it）" } ?: "")
            LlmErrorKind.PARSE_ERROR -> "响应看不懂"
            LlmErrorKind.CANCELLED -> "已取消"
            LlmErrorKind.UNKNOWN -> "请求失败" + (httpCode?.let { "（HTTP $it）" } ?: "")
        }
    }

    /** 值不值得自动重试。Key 错、余额不足这种重试一万次也没用。 */
    fun isRetryable(kind: LlmErrorKind): Boolean = when (kind) {
        LlmErrorKind.RATE_LIMITED,
        LlmErrorKind.SERVER_ERROR,
        LlmErrorKind.NETWORK,
        LlmErrorKind.TIMEOUT,
        -> true

        else -> false
    }
}
