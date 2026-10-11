package com.dreamgirl.app.data

/**
 * 会话列表的一行 —— 微信那种「头像 + 标题 + 最后一句 + 时间」。
 *
 * 我们的「好友」不是某个人，而是**和她的一天**（方案 §6.1 按天分会话）。
 */
data class SessionPreview(
    val sessionDate: String,
    val content: String,
    val role: String,
    val createdAt: Long,
    val messageCount: Int,
    val proactive: Boolean = false,
)
