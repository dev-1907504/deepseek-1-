package com.dreamgirl.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分段发送 —— 借鉴自 QQ 群 bot 的「分段回复」思路。
 *
 * **真人敲一句发一句**，不会把三句话塞进一个大气泡。
 * 这一组测试守的是：她一次能连发几条短消息，而且每条都短。
 */
class SegmentationTest {

    @Test
    fun `each line becomes its own message`() {
        val p = MoodParser.parse(
            "[心想] 他今天话挺少。\n" +
                "[嘴上] 在吗\n" +
                "问你个事\n" +
                "算了没事",
        )
        assertEquals(listOf("在吗", "问你个事", "算了没事"), p.segments)
    }

    @Test
    fun `blank lines are dropped`() {
        val p = MoodParser.parse("[嘴上] 在\n\n\n说啊")
        assertEquals(listOf("在", "说啊"), p.segments)
    }

    @Test
    fun `a single message stays a single bubble`() {
        val p = MoodParser.parse("[嘴上] 嗯，睡吧。")
        assertEquals(listOf("嗯，睡吧。"), p.segments)
    }

    @Test
    fun `she never sends more than five in a row`() {
        val body = (1..12).joinToString("\n") { i -> "第${i}句" }
        val p = MoodParser.parse("[嘴上] $body")
        assertEquals(MoodParser.MAX_SEGMENTS, p.segments.size)
    }

    @Test
    fun `a long single line is not chopped in half`() {
        // 不硬切 —— 把一句话切成两半比长一点更假
        val long = "我今天想了想还是觉得这件事得跟你说清楚因为我确实有点在意"
        val p = MoodParser.parse("[嘴上] $long")
        assertEquals(listOf(long), p.segments)
    }

    @Test
    fun `a silent reply has no segments at all`() {
        val p = MoodParser.parse("[心想] 算了。\n[不回] ……")
        assertTrue(p.segments.isEmpty())
    }

    @Test
    fun `without a marker even a multi line reply stays one message`() {
        // 设计如此：没有 [嘴上] 标记就没有"一行一条"的依据。
        // 实测踩过：不加这条守卫，一段连贯的话会被切成碎片，看起来像她崩了。
        val p = MoodParser.parse("在吗\n问你个事")
        assertEquals(listOf("在吗\n问你个事"), p.segments)
    }

    // ---------------------------------------------------------- 流式

    @Test
    fun `while typing, only the message being typed is shown`() {
        // 前面的已经作为独立气泡发出去了，不能重复显示
        val shown = MoodParser.stripForDisplay("[嘴上] 在吗\n问你个")
        assertEquals("问你个", shown)
    }

    @Test
    fun `a finished line stays visible while she starts the next one`() {
        // 打完一行还没写下一行时，不能突然空掉 —— 那看起来像消息被吞了
        assertEquals("在吗", MoodParser.stripForDisplay("[嘴上] 在吗\n"))
    }

    // ---------------------------------------------------------- 编造过往

    @Test
    fun `with no memory at all, claiming a shared past is a fabrication`() {
        val r = PostProcessor.process(
            "你上次说想去看海，我一直记着呢。",
            hardLimit = 400,
            memorySupported = false,
        )
        assertTrue("没有任何记忆依据还声称「你上次说」，这是编造", r.needsRewrite)
        assertTrue(r.reasons.any { it.contains("编造") })
    }

    @Test
    fun `with real memories, the same sentence is not flagged`() {
        // 代码分不清她引用的是不是真记忆，有记忆时交给提示词约束
        val r = PostProcessor.process(
            "你上次说想去看海。",
            hardLimit = 400,
            memorySupported = true,
        )
        assertFalse(r.needsRewrite)
    }

    @Test
    fun `ordinary speech is never mistaken for a fabricated past`() {
        // 「我一直觉得」这类日常话不能误伤，否则每轮都要重写（费钱）
        listOf(
            "我一直觉得这样挺好。",
            "今天有点累。",
            "嗯，睡吧。",
            "你还别说，这挺有意思。",
        ).forEach { line ->
            val r = PostProcessor.process(line, hardLimit = 400, memorySupported = false)
            assertFalse("不该把「$line」当成编造", r.needsRewrite)
        }
    }
}
