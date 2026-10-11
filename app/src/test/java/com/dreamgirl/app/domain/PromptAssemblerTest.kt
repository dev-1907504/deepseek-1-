package com.dreamgirl.app.domain

import com.dreamgirl.app.data.MessageEntity
import com.dreamgirl.app.persona.PersonaVerbatim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptAssemblerTest {

    private val assembler = PromptAssembler()

    private fun msg(role: String, content: String, at: Long) = MessageEntity(
        id = at,
        sessionDate = "2026-10-11",
        role = role,
        content = content,
        createdAt = at,
    )

    @Test
    fun `system prefix embeds the persona verbatim`() {
        val prefix = assembler.staticSystemPrefix("你")
        assertTrue(prefix.contains(PersonaVerbatim.FULL))
    }

    @Test
    fun `system prefix is byte identical across calls`() {
        // 这是上下文缓存的前提（方案 §5）：前缀一变，缓存全废。
        val a = assembler.staticSystemPrefix("你")
        val b = assembler.staticSystemPrefix("你")
        assertEquals(a, b)
    }

    @Test
    fun `system prefix does not leak per request state`() {
        // 动态内容（记忆、时间、摘要）必须排在 system 前缀之后
        val prefix = assembler.staticSystemPrefix("你")
        assertFalse("前缀里不该有时间", prefix.contains("2026-"))
        assertFalse("前缀里不该有记忆注入", prefix.contains("【你记得的"))
    }

    @Test
    fun `first message is always the static system prefix`() {
        val built = assembler.build(
            personaName = "",
            userAddress = "你",
            memoryLines = listOf("（昨天）他在减肥"),
            history = listOf(msg("user", "早", 1), msg("assistant", "早呀", 2)),
            currentUserText = "今天吃什么",
            daySummaryLine = "昨天聊了减肥",
        )
        assertEquals("system", built.messages.first().role)
        assertEquals(assembler.staticSystemPrefix("你"), built.messages.first().content)
        assertEquals("user", built.messages.last().role)
        assertEquals("今天吃什么", built.messages.last().content)
    }

    @Test
    fun `memory goes into a system message placed after history`() {
        val built = assembler.build(
            personaName = "",
            userAddress = "你",
            memoryLines = listOf("（昨天）他开始跑步"),
            memories = listOf(7L to "他开始跑步"),
            history = listOf(msg("user", "在吗", 1)),
            currentUserText = "跑了吗",
        )
        val memoryIndex = built.messages.indexOfFirst { it.content.contains("他开始跑步") }
        val lastUserIndex = built.messages.indexOfLast { it.role == "user" }
        assertTrue("记忆必须排在最后一条 user 之前", memoryIndex in 0 until lastUserIndex)
        assertEquals(listOf(7L), built.usedMemoryIds)
    }

    @Test
    fun `no memory block is added when nothing matched`() {
        val built = assembler.build(
            personaName = "",
            userAddress = "你",
            memoryLines = emptyList(),
            history = emptyList(),
            currentUserText = "你好",
        )
        assertEquals(2, built.messages.size)
        assertEquals("system", built.messages[0].role)
        assertEquals("user", built.messages[1].role)
    }

    @Test
    fun `few shot block is present and static`() {
        val prefix = assembler.staticSystemPrefix("你")
        assertTrue(prefix.contains("说话示范"))
        assertTrue(prefix.contains("反面示范"))
    }

    @Test
    fun `system prefix carries both the persona verbatim and the character layer`() {
        val prefix = assembler.staticSystemPrefix("你")
        // 人格原文一个字没动，仍然在里面
        assertTrue(prefix.contains(PersonaVerbatim.FULL))
        // 性格层是另加的一层，不能反过来盖掉原文
        assertTrue(prefix.contains("## 她很爱你"))
        assertTrue("性格层必须排在人格原文之后", prefix.indexOf(PersonaVerbatim.FULL) < prefix.indexOf("## 她很爱你"))
    }

    @Test
    fun `knowledge cards are only injected when something actually hits`() {
        val nothing = assembler.build(
            personaName = "",
            userAddress = "你",
            memoryLines = emptyList(),
            history = emptyList(),
            currentUserText = "嗯",
            knowledgeLine = null,
        )
        assertFalse(
            "没命中就一个字都不该出现",
            nothing.messages.any { it.content.contains("你这些年学到的") },
        )

        val hit = assembler.build(
            personaName = "",
            userAddress = "你",
            memoryLines = emptyList(),
            history = emptyList(),
            currentUserText = "我好累",
            knowledgeLine = "【你这些年学到的、跟眼前这件事有关的准绳】\n- 他身体累：先让他歇着。",
        )
        assertTrue(
            "命中时应该出现在动态区，而不是固定前缀",
            hit.messages.last().content.contains("我好累") &&
                hit.messages.any { it.content.contains("你这些年学到的") },
        )
        assertFalse(
            "固定前缀必须保持稳定，知识卡不能进前缀（否则缓存全废）",
            assembler.staticSystemPrefix("你").contains("你这些年学到的"),
        )
    }
}
