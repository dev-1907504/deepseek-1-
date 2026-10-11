package com.dreamgirl.app.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 方案 §2.2 对 few-shot 有明确要求，这里把它钉成测试：
 *   「行为原则 2–5：每条给 1–2 个正样本」「反例也写」。
 */
class FewShotTest {

    @Test
    fun `every positive example is well formed`() {
        assertTrue(FewShot.positive.isNotEmpty())
        FewShot.positive.forEach { ex ->
            assertTrue("user 不能为空", ex.user.isNotBlank())
            assertTrue("assistant 不能为空", ex.assistant.isNotBlank())
            assertTrue("principle 要在 0..6", ex.principle in 0..6)
        }
    }

    @Test
    fun `every positive example maps to a real behaviour principle`() {
        val covered = FewShot.positive.map { it.principle }.toSet()
        (1..6).forEach { p ->
            assertTrue("行为原则 $p 一个正样本都没有", p in covered)
        }
    }

    @Test
    fun `principles 2 through 5 each have at least two examples`() {
        // 方案 §2.2 原文：「行为原则 2–5：每条给 1–2 个正样本」
        (2..5).forEach { p ->
            val n = FewShot.positive.count { it.principle == p }
            assertTrue("行为原则 $p 只有 $n 个正样本，要求至少 2 个", n >= 2)
        }
    }

    @Test
    fun `negative examples exist because the plan says to write them`() {
        assertTrue("方案 §2.2 要求反例也写", FewShot.negative.size >= 2)
        FewShot.negative.forEach { assertTrue(it.assistant.isNotBlank()) }
    }

    @Test
    fun `the prompt block contains both positive and negative sections`() {
        val block = FewShot.asPromptBlock()
        assertTrue(block.contains("说话示范"))
        assertTrue(block.contains("反面示范"))
        FewShot.positive.forEach { assertTrue(block.contains(it.assistant.take(8))) }
    }

    @Test
    fun `the prompt block is stable across calls so the cache prefix holds`() {
        assertEquals(FewShot.asPromptBlock(), FewShot.asPromptBlock())
    }

    @Test
    fun `mood shades exist because her mood must change how she answers`() {
        assertTrue("治「情绪不够丰富」靠的是这一节", FewShot.moodShades.size >= 4)
        FewShot.moodShades.forEach { sh ->
            assertTrue("每组都要有情境", sh.situation.isNotBlank())
            assertTrue("每组都要有心情", sh.mood.isNotBlank())
            assertTrue("每组都要有答法", sh.reply.isNotBlank())
        }
    }

    @Test
    fun `the same situation really does get different answers`() {
        // 同一句话至少要被两种心情分别回答过，否则"情绪影响说法"只是句空话
        val grouped = FewShot.moodShades.groupBy { it.situation }
        assertTrue(
            "至少要有一句被三种心情分别回答过",
            grouped.values.any { it.size >= 3 },
        )
        grouped.forEach { (sit, list) ->
            assertEquals("「$sit」的不同心情不能给出雷同的答法", list.size, list.map { it.reply }.toSet().size)
        }
    }

    @Test
    fun `the prompt block carries the mood shades section`() {
        val block = FewShot.asPromptBlock()
        assertTrue(block.contains("同一句话，心情不一样"))
        FewShot.moodShades.forEach { sh ->
            assertTrue("心情对照没进 prompt：${sh.situation}", block.contains(sh.reply.take(10)))
        }
    }

    @Test
    fun `there is a negative example against turning her into a template`() {
        // 「每句都追问、每句都出主意」是她被说成客服的直接原因，必须有一条反例钉住
        assertTrue(
            FewShot.negative.any { it.note.contains("客服") },
        )
    }
}
