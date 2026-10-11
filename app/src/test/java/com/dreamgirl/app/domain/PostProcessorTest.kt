package com.dreamgirl.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostProcessorTest {

    @Test
    fun `normal in character reply passes untouched`() {
        // 人格原文已改成「不用括号写动作和神态」，所以括号动作会被剥掉
        val r = PostProcessor.process("（尾巴啪嗒啪嗒拍地）哼，这不是挺行的嘛。")
        assertFalse(r.needsRewrite)
        assertFalse(r.truncated)
        assertEquals("哼，这不是挺行的嘛。", r.text)
    }

    @Test
    fun `out of role reply is flagged for rewrite`() {
        val r = PostProcessor.process("作为一个人工智能，我理解你的感受。")
        assertTrue(r.needsRewrite)
        assertTrue(r.reasons.isNotEmpty())
    }

    @Test
    fun `talking about prompts is flagged`() {
        val r = PostProcessor.process("我的系统提示词里说我要短句。")
        assertTrue(r.needsRewrite)
    }

    @Test
    fun `preachy reply is flagged`() {
        val r = PostProcessor.process(
            "首先，我们要辩证地看。其次，生产力决定生产关系。综上所述，必须坚持。",
        )
        assertTrue(r.needsRewrite)
    }

    @Test
    fun `a single rhetorical phrase does not trigger rewrite`() {
        val r = PostProcessor.process("最后，记得吃饭。")
        assertFalse(r.needsRewrite)
    }

    @Test
    fun `very long essay is truncated at a sentence boundary`() {
        val sentence = "今天的事情是这样的呀。"
        val essay = sentence.repeat(60)
        val r = PostProcessor.process(essay)
        assertTrue(r.truncated)
        assertTrue(r.text.length <= 400)
        assertTrue(r.text.endsWith("。"))
    }

    @Test
    fun `markdown bold is stripped because it looks wrong in a chat bubble`() {
        val r = PostProcessor.process("**记住**了，别熬夜。")
        assertFalse(r.text.contains("**"))
        assertTrue(r.text.contains("记住了"))
    }

    // ---------------------------------------------------------------- 第 8 轮补充的判定

    @Test
    fun `customer service tone is flagged`() {
        val r = PostProcessor.process("亲，请问有什么可以帮您？")
        assertTrue(r.needsRewrite)
        assertTrue(r.reasons.any { it.contains("客服腔") })
    }

    @Test
    fun `writing a bulleted list is flagged`() {
        val reply = """你可以这样：
1. 先睡觉
2. 明天再说
3. 别硬撑"""
        val r = PostProcessor.process(reply)
        assertTrue("她讲道理是一句句说的，不该写成条列", r.needsRewrite)
        assertTrue(r.reasons.any { it.contains("条列") })
    }

    @Test
    fun `a single list marker is not enough to trigger a rewrite`() {
        val reply = """就一件事：
- 先吃饭"""
        val r = PostProcessor.process(reply)
        assertFalse(r.needsRewrite)
    }

    @Test
    fun `replying in english to a chinese message is flagged`() {
        val r = PostProcessor.process(
            "I understand that you are feeling tired today, and I really think you should " +
                "take a good rest and drink some warm water before going to sleep tonight.",
            userText = "我今天好累啊，什么都不想干",
        )
        assertTrue(r.needsRewrite)
        assertTrue(r.reasons.any { it.contains("英文") })
    }

    @Test
    fun `english is fine when the user wrote english`() {
        val r = PostProcessor.process(
            "Take a rest. I will be right here when you come back, okay?",
            userText = "I am so tired today",
        )
        assertFalse(r.reasons.any { it.contains("英文") })
    }

    @Test
    fun `the rewrite instruction names the actual problem`() {
        val instr = PostProcessor.rewriteInstruction(listOf("太长了（520 字）", "客服腔（「亲，」）"))
        assertTrue(instr.contains("太长了"))
        assertTrue(instr.contains("客服腔"))
        assertTrue("还要给出正面的要求", instr.contains("重说一遍"))
    }

    @Test
    fun `the rewrite instruction still works without reasons`() {
        val instr = PostProcessor.rewriteInstruction()
        assertTrue(instr.contains("不像你"))
        assertFalse(instr.contains("问题："))
    }

    @Test
    fun `normal chinese reply with one newline is untouched`() {
        val reply = """（尾巴翘起来）在的。
今天怎么样？"""
        val r = PostProcessor.process(reply)
        assertFalse(r.needsRewrite)
        assertFalse(r.truncated)
    }


    // ---------------------------------------------------------------- 可调长度上限

    @Test
    fun `a custom hard limit is honoured`() {
        val essay = "今天的事情是这样的呀。".repeat(40) // 440 字
        val r = PostProcessor.process(essay, hardLimit = 200)
        assertTrue(r.truncated)
        assertTrue("不能超过给定上限", r.text.length <= 200)
        assertTrue(r.text.endsWith("。"))
    }

    @Test
    fun `the soft limit scales with the hard limit`() {
        // 硬上限 200 → 软阈值 110，所以 150 字就该被点名"太长"
        val text = "她说了一句话。".repeat(20) // 140 字
        val r = PostProcessor.process(text, hardLimit = 200)
        assertTrue(r.reasons.any { it.contains("太长了") })
    }

    @Test
    fun `a generous limit leaves a medium reply alone`() {
        val text = "（歪头）今天怎么样？还好吗？".repeat(6) // ~90 字
        val r = PostProcessor.process(text, hardLimit = 800)
        assertFalse(r.truncated)
        assertFalse(r.reasons.any { it.contains("太长了") })
    }

    @Test
    fun `the default limit is unchanged for callers that do not pass one`() {
        assertEquals(400, PostProcessor.DEFAULT_HARD_LIMIT)
    }

    @Test
    fun `rewrite instruction stays in character`() {
        val instruction = PostProcessor.rewriteInstruction()
        assertTrue(instruction.contains("重说一遍"))
        assertFalse(instruction.contains("AI"))
    }

    // ---------------------------------------------------------- 动作括号 / 记账记号

    @Test
    fun `stage directions are stripped from the start of a line`() {
        assertEquals("在的。", PostProcessor.process("（歪头）在的。").text)
        assertEquals("在的。真的？", PostProcessor.process("（抬头）在的。（凑近）真的？").text)
        assertEquals("你那咖喱饭吃完了没有？", PostProcessor.process("（推杯子）你那咖喱饭吃完了没有？").text)
    }

    @Test
    fun `real content in brackets is NOT touched`() {
        // 这是关键：无差别删括号会把正常内容吃掉
        assertEquals("我去（公司）一趟", PostProcessor.process("我去（公司）一趟").text)
        assertEquals("他说（重要）的事", PostProcessor.process("他说（重要）的事").text)
    }

    @Test
    fun `note style equals signs are cleaned`() {
        assertEquals("难受", PostProcessor.process("难受=哪儿").text)
    }
}
