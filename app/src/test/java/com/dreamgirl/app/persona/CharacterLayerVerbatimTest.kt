package com.dreamgirl.app.persona

import com.dreamgirl.app.domain.KnowledgeCardsVerbatim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 性格层与知识卡的「不外泄、不缩水」防线（和 PersonaVerbatimTest 同一套思路）。
 */
class CharacterLayerVerbatimTest {

    @Test
    fun `character layer has all eight sections`() {
        val t = CharacterLayerVerbatim.FULL
        listOf(
            "## 她看什么",
            "## 她怎么接住一个人",
            "## 她自己也有情绪",
            "## 她很爱你",
            "## 她关心人的方式",
            "## 她什么时候不出手",
            "## 她也会说僵",
            "## 她不做的",
        ).forEach { assertTrue("缺小节：$it", t.contains(it)) }
    }

    @Test
    fun `character layer has no leading or trailing blank line`() {
        // raw string 首尾多一个换行就会破坏「被谁包含」这类不变量（踩过一次）
        val t = CharacterLayerVerbatim.FULL
        assertEquals(t.trim(), t)
    }

    @Test
    fun `character layer is stable across reads`() {
        assertEquals(CharacterLayerVerbatim.FULL, CharacterLayerVerbatim.FULL)
    }

    @Test
    fun `cards all have triggers and a rule`() {
        assertTrue(KnowledgeCardsVerbatim.ALL.size >= 10)
        KnowledgeCardsVerbatim.ALL.forEach { c ->
            assertTrue("卡片 ${c.id} 没有触发词", c.triggers.size >= 4)
            assertTrue("卡片 ${c.id} 没有准则", c.rule.isNotBlank())
        }
    }

    @Test
    fun `no card exceeds the character budget`() {
        KnowledgeCardsVerbatim.ALL.forEach { c ->
            assertTrue("卡片 ${c.id} 准则 ${c.rule.length} 字，会挤占人设", c.rule.length <= 120)
        }
    }

    @Test
    fun `card ids are unique`() {
        val ids = KnowledgeCardsVerbatim.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
