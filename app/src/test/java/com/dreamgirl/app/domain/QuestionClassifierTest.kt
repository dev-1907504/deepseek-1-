package com.dreamgirl.app.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 方案 §5：reasoner「只在认真分析场景按需切换」。宁可漏判，不要误判。 */
class QuestionClassifierTest {

    @Test
    fun `a real dilemma counts as hard`() {
        assertTrue(QuestionClassifier.isHardQuestion("我想辞职去搞自媒体，你说该怎么办"))
    }

    @Test
    fun `asking for analysis counts as hard`() {
        assertTrue(QuestionClassifier.isHardQuestion("帮我分析一下这两个 offer 的利弊"))
    }

    @Test
    fun `a should i question counts as hard`() {
        assertTrue(QuestionClassifier.isHardQuestion("这个手机到底该不该换啊"))
    }

    @Test
    fun `small talk is never hard`() {
        assertFalse(QuestionClassifier.isHardQuestion("今天好累啊，什么都不想干"))
        assertFalse(QuestionClassifier.isHardQuestion("在吗"))
        assertFalse(QuestionClassifier.isHardQuestion("晚安，明天见"))
    }

    @Test
    fun `a long emotional message is still not hard`() {
        val s = "今天真的好累，什么都不想干，感觉整个人都被掏空了，好难过"
        assertFalse(QuestionClassifier.isHardQuestion(s))
    }

    @Test
    fun `a blunt cry for help counts as hard even though it is short`() {
        assertTrue(QuestionClassifier.isHardQuestion("怎么办"))
    }

    @Test
    fun `weak markers still need a reasonably long message`() {
        assertFalse("一句'为什么'不该触发分析模式", QuestionClassifier.isHardQuestion("为什么"))
        assertTrue(
            QuestionClassifier.isHardQuestion("我一直想不明白为什么会这样，你能帮我理一下吗"),
        )
    }

    @Test
    fun `venting that happens to contain a marker is not treated as a decision`() {
        assertFalse(QuestionClassifier.isHardQuestion("今天好累啊，怎么办啊什么都不想干"))
    }

    @Test
    fun `plain banter is not hard`() {
        assertFalse(QuestionClassifier.isHardQuestion("哈哈哈你今天怎么这么逗"))
    }

    @Test
    fun `empty input is not hard`() {
        assertFalse(QuestionClassifier.isHardQuestion(""))
        assertFalse(QuestionClassifier.isHardQuestion("   "))
    }
}
