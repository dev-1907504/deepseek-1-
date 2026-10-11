package com.dreamgirl.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 她的三段输出，和"回不回"这两件事。
 *
 * 守三样：
 *  1. **心想不进气泡** —— 她的推导过程不该被他看见
 *  2. **不回 = 对他不满**，不是心情差
 *  3. **不回的连续次数有硬上限** —— 不能变成冷暴力
 */
class InnerOsTest {

    // ---------------------------------------------------------- 三段输出

    @Test
    fun `the three markers are separated`() {
        val p = MoodParser.parse(
            "[心想] 他又问在干嘛。上次问完就没影了。\n" +
                "[嘴上] 在收拾本子呢。",
        )
        assertEquals("在收拾本子呢。", p.said)
        assertEquals("他又问在干嘛。上次问完就没影了。", p.inner)
        assertFalse("嘴上说了就不算没回", p.silent)
    }

    @Test
    fun `inner thoughts never leak into the bubble`() {
        val p = MoodParser.parse("[心想] 烦死了，不想理。\n[嘴上] 嗯。")
        assertFalse("心想不能进气泡", p.said.contains("烦死了"))
        assertEquals("嗯。", p.said)
    }

    @Test
    fun `a silent reply keeps the inner line and says nothing`() {
        val p = MoodParser.parse("[心想] 又是这样。\n[不回] ……嗯。")
        assertTrue(p.silent)
        assertEquals("", p.said)
        assertEquals("……嗯。", p.inner)
    }

    @Test
    fun `silent falls back to the first inner line when no silent marker`() {
        val p = MoodParser.parse("[心想] 算了，不想说。")
        assertTrue(p.silent)
        assertTrue(p.inner!!.contains("不想说"))
    }

    @Test
    fun `colons are optional and half width brackets work too`() {
        assertEquals("在。", MoodParser.parse("[嘴上]: 在。").said)
        assertEquals("在。", MoodParser.parse("[嘴上]在。").said)
        assertEquals("在。", MoodParser.parse("[嘴上：在。").said)
    }

    @Test
    fun `old single block输出 is treated as her saying it`() {
        // 兼容她没按格式写的情况
        val p = MoodParser.parse("在的，刚回来。")
        assertEquals("在的，刚回来。", p.said)
        assertFalse(p.silent)
    }

    @Test
    fun `the legacy mood marker is still stripped`() {
        val p = MoodParser.parse("在的。[心情:尾巴翘起来了]")
        assertFalse("老写法不能漏进气泡", p.said.contains("心情"))
        assertTrue(p.said.contains("在的"))
    }

    // ---------------------------------------------------------- 流式

    @Test
    fun `streaming shows nothing until she actually speaks`() {
        // 写到 [心想] 的时候，气泡必须是空的
        assertEquals("", MoodParser.stripForDisplay("[心"))
        assertEquals("", MoodParser.stripForDisplay("[心想] 我在想"))
        assertEquals("", MoodParser.stripForDisplay("[心想] 我在想\n[嘴"))
    }

    @Test
    fun `streaming reveals only what she says out loud`() {
        assertEquals("在", MoodParser.stripForDisplay("[心想] 想半天\n[嘴上] 在"))
        assertEquals("在的。", MoodParser.stripForDisplay("[心想] 想半天\n[嘴上] 在的。"))
    }

    @Test
    fun `streaming of a plain reply works as before`() {
        assertEquals("在的", MoodParser.stripForDisplay("在的"))
    }

    // ---------------------------------------------------------- 回不回

    @Test
    fun `a good mood never makes her silent`() {
        // 这是核心：心情差不等于不回他
        val sad = EmotionState.Mood(label = "有点蔫", valence = -0.9, at = 1)
        assertFalse("她自己蔫着也照样会应他", EmotionState.decide(
            EmotionState.Resentment(level = 0.0), silentStreak = 0, dice = 0.0,
        ).silent)
        // 而情绪本身不变
        assertEquals(-0.9, sad.valence, 0.0001)
    }

    @Test
    fun `only resentment makes her go quiet`() {
        val calm = EmotionState.decide(EmotionState.Resentment(), 0, dice = 0.99)
        assertFalse(calm.silent)

        val angry = EmotionState.decide(EmotionState.Resentment(level = 0.95), 0, dice = 0.0)
        assertTrue("气到这份上就不说话了", angry.silent)
    }

    @Test
    fun `the angrier she is the more likely she stays quiet`() {
        fun odds(level: Double): Int =
            (0..99).count { EmotionState.decide(EmotionState.Resentment(level = level), 0, it / 100.0).silent }
        val mild = odds(0.4)
        val hot = odds(0.9)
        assertTrue("越气越可能不说话（$mild vs $hot）", hot > mild)
    }

    @Test
    fun `silence is capped so it never becomes stonewalling`() {
        // 不管多气，连续两次之后就必回
        val very = EmotionState.Resentment(level = 1.0)
        assertTrue(EmotionState.decide(very, silentStreak = 0, dice = 0.0).silent)
        assertTrue(EmotionState.decide(very, silentStreak = 1, dice = 0.0).silent)
        assertFalse(
            "已经晾了两次，不能再晾了",
            EmotionState.decide(very, silentStreak = EmotionState.MAX_SILENT_STREAK, dice = 0.0).silent,
        )
    }

    @Test
    fun `resentment is only raised by his behaviour`() {
        // 反复的强烈不满会被兜在 1.0；道歉能大幅卸掉
        var r = EmotionState.Resentment()
        r = EmotionState.applyResentment(r, EmotionState.Offence.HURT, "说了很重的话", now = 1000)
        assertTrue(r.level > 0.3)
        assertEquals("说了很重的话", r.reason)

        val after = EmotionState.applyResentment(r, EmotionState.Offence.APOLOGY, "", now = 2000)
        assertTrue("道歉要能卸掉一大半（${r.level} → ${after.level}）", after.level < r.level * 0.4)
    }

    @Test
    fun `an apology that clears it also clears the reason`() {
        var r = EmotionState.applyResentment(
            EmotionState.Resentment(), EmotionState.Offence.DISMISSIVE, "敷衍我", now = 1000,
        )
        repeat(3) { r = EmotionState.applyResentment(r, EmotionState.Offence.APOLOGY, "", now = 2000L + it) }
        assertTrue(r.level <= 0.05)
        assertTrue("气消了就别记着了", r.reason.isEmpty())
    }

    @Test
    fun `resentment fades far more slowly than mood`() {
        val now0 = 1_000_000L
        val hour = 3600_000L
        val mood = EmotionState.decay(EmotionState.Mood("烦", -0.8, now0), now0 + 12 * hour)
        val resent = EmotionState.decayResentment(EmotionState.Resentment(0.8, "气", now0), now0 + 12 * hour)
        assertTrue(
            "她记仇比记情绪久（情绪剩 ${"%.3f".format(mood.valence)}，不满剩 ${"%.3f".format(resent.level)}）",
            resent.level > kotlin.math.abs(mood.valence),
        )
    }

    // ---------------------------------------------------------- 注入 prompt 的说法

    @Test
    fun `mood is described without numbers`() {
        val line = EmotionState.describe(EmotionState.Mood("有点蔫", -0.5, 0))!!
        assertFalse("她不该知道自己是 -0.5", line.contains("-0.5"))
        assertTrue(line.contains("有点蔫"))
    }

    @Test
    fun `a calm state adds nothing to the prompt`() {
        assertNull(EmotionState.describe(EmotionState.Mood()))
        assertNull(EmotionState.describeResentment(EmotionState.Resentment()))
    }
}
