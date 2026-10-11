package com.dreamgirl.app.ui

import com.dreamgirl.app.data.MessageEntity
import com.dreamgirl.app.ui.chat.quoteAuthor
import com.dreamgirl.app.ui.chat.quotePreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长按菜单里那些"显示用"的纯函数。
 * 它们不碰 Compose，但错了用户一眼就能看见（引用条上写着"她： "后面一片空白）。
 */
class MessageActionsUiTest {

    private fun msg(
        role: String,
        content: String,
        kind: String = MessageEntity.KIND_TEXT,
    ) = MessageEntity(
        id = 1, sessionDate = "2026-10-11", role = role, content = content,
        createdAt = 0, kind = kind,
    )

    @Test
    fun `quote author says me for mine and her for hers`() {
        assertEquals("我", quoteAuthor(msg("user", "在吗")))
        assertEquals("她", quoteAuthor(msg("assistant", "在。")))
    }

    @Test
    fun `quote preview collapses newlines so the strip stays one line`() {
        val preview = quotePreview(msg("assistant", "第一行\n第二行\n第三行"))
        assertEquals("第一行 第二行 第三行", preview)
        assertTrue("引用条只有一行，不能带换行", !preview.contains("\n"))
    }

    @Test
    fun `media messages get a readable placeholder instead of a blank strip`() {
        assertEquals("[图片]", quotePreview(msg("user", "", MessageEntity.KIND_IMAGE)))
        assertEquals("[视频]", quotePreview(msg("user", "", MessageEntity.KIND_VIDEO)))
        assertEquals("[文件]", quotePreview(msg("assistant", "", MessageEntity.KIND_FILE)))
        assertEquals("[表情]", quotePreview(msg("user", "", MessageEntity.KIND_STICKER)))
    }

    @Test
    fun `a media message with a caption keeps the caption`() {
        // 带文字说明的图片，引用时显示文字比显示"[图片]"有用
        assertEquals("今天的晚饭", quotePreview(msg("user", "今天的晚饭", MessageEntity.KIND_IMAGE)))
    }
}
