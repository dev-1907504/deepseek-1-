package com.dreamgirl.app.llm

/**
 * 纯逻辑的 SSE 解析器——**不碰网络、不碰 Android**，因此可以在 JVM 上单测。
 *
 * 严格遵守 SSE 规范里我们用得到的那部分：
 *   - 以空行分隔事件
 *   - 一个事件里可以有多个 `data:` 行，用 `\n` 连接
 *   - `data: [DONE]`（DeepSeek/OpenAI 约定）表示流结束
 *   - 以 `:` 开头的是注释/心跳，忽略
 *   - `event:` / `id:` / `retry:` 字段我们不关心，忽略
 *
 * 用法：网络层每读到一行就 [feedLine]，读到流结尾调用 [endOfStream]。
 */
class SseStreamParser(
    private val onEvent: (SseEvent) -> Unit,
) {
    sealed interface SseEvent {
        /** 一条完整的 data 负载（JSON 字符串）。 */
        data class Data(val payload: String) : SseEvent

        /** 服务端宣布结束（`data: [DONE]`）。 */
        data object Done : SseEvent
    }

    private val dataLines = StringBuilder()
    private var sawAnyDataLine = false
    private var done = false

    /** 送一行（不含行尾换行符）。 */
    fun feedLine(rawLine: String) {
        if (done) return
        val line = rawLine.removeSuffix("\r")
        if (line.isEmpty()) {
            dispatch()
            return
        }
        if (line.startsWith(":")) return // 注释 / 心跳
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        if (field != "data") return
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        if (dataLines.isNotEmpty()) dataLines.append('\n')
        dataLines.append(value)
        sawAnyDataLine = true
    }

    /** 流自然结束：把还没派发的事件补发掉。 */
    fun endOfStream() {
        if (done) return
        dispatch()
        // 没有显式 [DONE] 的流，也要让调用方知道结束了
        if (!done) {
            done = true
            onEvent(SseEvent.Done)
        }
    }

    private fun dispatch() {
        if (!sawAnyDataLine) return
        val payload = dataLines.toString()
        dataLines.setLength(0)
        sawAnyDataLine = false
        if (payload.trim() == "[DONE]") {
            done = true
            onEvent(SseEvent.Done)
            return
        }
        onEvent(SseEvent.Data(payload))
    }
}
