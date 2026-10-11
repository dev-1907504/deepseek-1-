package com.dreamgirl.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解析器的**宽容度**和**泄漏兜底**。
 *
 * 这一组是从真实的"她崩了"现象倒推出来的：
 * 模型不会老老实实写 `[嘴上]`，它会写成 `【嘴上】`、`**嘴上**：`、`[嘴上：]`、
 * 行首裸写……旧解析器只认一种写法，**标记就原样漏进了气泡** ——
 * 用户看到的就是"一堆不明所以的话"。
 *
 * 铁律：**用户永远不该在气泡里看到标记。**
 */
class ParserToleranceTest {

    // ---------------------------------------------------------- 各种写歪的形式

    @Test
    fun `square bracket variants are recognised`() {
        assertEquals("嗯。", MoodParser.parse("【心想】随便想想\n【嘴上】嗯。").said)
        assertEquals("嗯。", MoodParser.parse("〔心想〕想想\n〔嘴上〕嗯。").said)
    }

    @Test
    fun `markdown bold variants are recognised`() {
        val p = MoodParser.parse("**心想**：他怎么了\n**嘴上**：没事。")
        assertEquals("没事。", p.said)
        assertEquals("他怎么了", p.inner)
    }

    @Test
    fun `colon inside the bracket is recognised`() {
        val p = MoodParser.parse("[心想：他怎么了]\n[嘴上：]嗯。")
        assertEquals("嗯。", p.said)
        assertEquals("他怎么了", p.inner)
    }

    @Test
    fun `parenthesis variants are recognised`() {
        assertEquals("嗯。", MoodParser.parse("（心想）想想\n（嘴上）嗯。").said)
    }

    @Test
    fun `bare word with colon at line start is recognised`() {
        val p = MoodParser.parse("心想：他怎么了\n嘴上：没事。")
        assertEquals("没事。", p.said)
        assertEquals("他怎么了", p.inner)
    }

    @Test
    fun `mixed messy formats still resolve`() {
        val p = MoodParser.parse("【心想】乱写一气\n**嘴上**：行吧。")
        assertEquals("行吧。", p.said)
    }

    // ---------------------------------------------------------- 泄漏兜底

    @Test
    fun `a marker never survives into the bubble`() {
        // 这是本轮最要紧的一条：遍历所有变体，气泡里一个字标记都不许有
        val variants = listOf(
            "[心想] 想想\n[嘴上] 嗯。",
            "【心想】想想\n【嘴上】嗯。",
            "**心想**：想想\n**嘴上**：嗯。",
            "[心想：想想]\n[嘴上：]嗯。",
            "（心想）想想\n（嘴上）嗯。",
            "心想：想想\n嘴上：嗯。",
            "[心声] 想想\n[回复] 嗯。",
            "【内心】想想\n【开口】嗯。",
        )
        variants.forEach { raw ->
            val p = MoodParser.parse(raw)
            assertFalse("这条泄漏了标记：$raw → ${p.said}", MoodParser.hasLeak(p.said))
            assertFalse("内心也别泄漏：$raw → ${p.inner}", p.inner?.let { MoodParser.hasLeak(it) } == true)
            p.segments.forEach {
                assertFalse("分段里泄漏了标记：$raw → $it", MoodParser.hasLeak(it))
            }
        }
    }

    @Test
    fun `scrub removes anything that looks like a marker`() {
        assertEquals("嗯。", MoodParser.scrub("[嘴上]嗯。").trim())
        assertEquals("嗯。", MoodParser.scrub("**嘴上**：嗯。").trim())
        assertEquals("嗯。", MoodParser.scrub("【不回】嗯。").trim())
    }

    @Test
    fun `hasLeak only fires on marker shapes, not on normal brackets`() {
        assertFalse("正常括号不该误报", MoodParser.hasLeak("我去（公司）一趟"))
        assertFalse(MoodParser.hasLeak("他说「明天见」"))
        assertFalse(MoodParser.hasLeak("嗯，睡吧。"))
        assertTrue(MoodParser.hasLeak("[嘴上] 嗯"))
        assertTrue(MoodParser.hasLeak("**心想**：哦"))
    }

    // ---------------------------------------------------------- 分段守卫

    @Test
    fun `without a marker, a multi line reply is NOT chopped up`() {
        // 实测踩过：一段连贯的三行散文被切成三个气泡，看起来像她崩了
        val prose = "我想了想，还是觉得这件事要说清楚。\n" +
            "不是因为我在意，是因为你今天的语气不对。\n" +
            "算了，当我没说。"
        val p = MoodParser.parse(prose)
        assertEquals("没有标记就不该分段", 1, p.segments.size)
        assertEquals(prose, p.segments.first())
    }

    @Test
    fun `with a marker, short lines do become separate messages`() {
        val p = MoodParser.parse("[嘴上] 在吗\n问你个事\n算了没事")
        assertEquals(listOf("在吗", "问你个事", "算了没事"), p.segments)
    }

    @Test
    fun `a marked reply that is really prose stays one message`() {
        // 就算标了 [嘴上]，几行都很长就说明她在写散文，不该拆
        val p = MoodParser.parse(
            "[嘴上] 我今天想了一整天，觉得我们之间有些话还是要摊开来讲清楚比较好。\n" +
                "不然这样一直悬着，你我都不好受，我也不知道该怎么面对你。",
        )
        assertEquals("散文不该切成两条", 1, p.segments.size)
    }

    @Test
    fun `a single short marked line is one message`() {
        assertEquals(listOf("嗯，睡吧。"), MoodParser.parse("[嘴上] 嗯，睡吧。").segments)
    }

    // ---------------------------------------------------------- 流式不能泄漏

    @Test
    fun `streaming never shows a marker`() {
        listOf(
            "[心", "[心想]", "[心想] 想", "[嘴上]", "[嘴上] 嗯",
            "【心", "**心", "[嘴上：", "心想：",
        ).forEach { partial ->
            val shown = MoodParser.stripForDisplay(partial)
            assertFalse("流式泄漏了：$partial → $shown", MoodParser.hasLeak(shown))
        }
    }

    @Test
    fun `streaming still reveals the last line being typed`() {
        assertEquals("问你个", MoodParser.stripForDisplay("[嘴上] 在吗\n问你个"))
        assertEquals("嗯。", MoodParser.stripForDisplay("[心想] 想想\n[嘴上] 嗯。"))
    }

    @Test
    fun `streaming of plain text is unchanged`() {
        assertEquals("在的", MoodParser.stripForDisplay("在的"))
    }
}
