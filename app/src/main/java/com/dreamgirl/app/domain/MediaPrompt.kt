package com.dreamgirl.app.domain

import com.dreamgirl.app.data.MessageEntity

/**
 * 非文字消息在 prompt 里的样子。
 *
 * 模型看不见图片本身，所以我们只告诉它"对方发来一张图"，
 * 让它能自然接话 —— 而不是把二进制塞过去（也不可能）。
 * 这里不涉及任何文件内容外发。
 */
object MediaPrompt {

    fun describe(kind: String, who: Who, fileName: String? = null): String = when (kind) {
        MessageEntity.KIND_IMAGE -> if (who == Who.USER) "[他发来一张图片]" else "[我发了一张图片]"
        MessageEntity.KIND_VIDEO -> if (who == Who.USER) "[他发来一段视频]" else "[我发了一段视频]"
        MessageEntity.KIND_STICKER -> if (who == Who.USER) "[他发来一个表情]" else "[我发了一个表情]"
        MessageEntity.KIND_FILE ->
            if (who == Who.USER) "[他发来一个文件：${fileName ?: "未命名"}]"
            else "[我发了一个文件：${fileName ?: "未命名"}]"

        else -> ""
    }

    enum class Who { USER, HER }

    /** 把一条消息渲染成 prompt 里的一行。 */
    fun line(message: MessageEntity): String {
        val who = if (message.role == "user") Who.USER else Who.HER
        return if (message.isText) message.content else describe(message.kind, who, message.content)
    }
}
