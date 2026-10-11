package com.dreamgirl.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 她自己的时间感。
 *
 * 这套测试守的是两件事：
 *  1. **她知道现在是什么时候**（在这之前她完全不知道）
 *  2. **"隔了多久"要用人的方式说**，不能变成一面时钟/日志
 */
class TimeSenseTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 造一个本地时刻对应的毫秒。 */
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    private fun sense(now: Long, last: Long? = null) = TimeSense.of(now, last, zone)

    // ---------------------------------------------------------- 现在是什么时候

    @Test
    fun `she knows the clock, the weekday and the date`() {
        // 2026-10-11 是周日
        val line = sense(at(2026, 10, 11, 14, 30)).line
        assertTrue("要有几点，而且要说人话：$line", line.contains("下午两点半"))
        assertTrue("要有周几：$line", line.contains("周日"))
        assertTrue("要有几月几号：$line", line.contains("10月11日"))
    }

    @Test
    fun `day parts are spoken like a person not like am pm`() {
        assertEquals("清早", TimeSense.dayPart(6))
        assertEquals("上午", TimeSense.dayPart(10))
        assertEquals("中午", TimeSense.dayPart(12))
        assertEquals("下午", TimeSense.dayPart(15))
        assertEquals("傍晚", TimeSense.dayPart(18))
        assertEquals("晚上", TimeSense.dayPart(21))
        assertEquals("深夜", TimeSense.dayPart(23))
        assertEquals("凌晨", TimeSense.dayPart(2))
    }

    @Test
    fun `there is no machine-readable timestamp anywhere`() {
        // 给了 ISO 时间戳她就会说话像日志
        val line = sense(at(2026, 10, 11, 3, 7)).line
        assertFalse("不该有 ISO 时间：$line", line.contains("2026-10-11T"))
        assertFalse("不该有时区名：$line", line.contains("Asia/"))
        assertFalse("不该有秒：$line", Regex("""\d+:\d\d:\d\d""").containsMatchIn(line))
    }

    // ---------------------------------------------------------- 深夜

    @Test
    fun `late night is recognised at both ends of the night`() {
        assertTrue("23 点算深夜", sense(at(2026, 10, 11, 23, 10)).lateNight)
        assertTrue("凌晨 2 点算深夜", sense(at(2026, 10, 11, 2, 0)).lateNight)
        assertTrue("凌晨 3:59 还算深夜", sense(at(2026, 10, 11, 3, 59)).lateNight)
        assertFalse("下午不算", sense(at(2026, 10, 11, 15, 0)).lateNight)
        assertFalse("早上 4 点过后就不算了（她的一天从这里开始）", sense(at(2026, 10, 11, 4, 1)).lateNight)
    }

    @Test
    fun `the late night line does not turn her into a sleep coach`() {
        // 她自己也是这个点还醒着的人，不该摆出关心作息的架子
        val line = sense(at(2026, 10, 11, 1, 30)).line
        assertTrue("要说清是深夜", line.contains("这个点"))
        assertFalse("不该劝人睡觉：$line", line.contains("早点睡") || line.contains("该休息"))
    }

    // ---------------------------------------------------------- 隔了多久

    @Test
    fun `a short gap is spoken as a short gap`() {
        val now = at(2026, 10, 11, 15, 0)
        assertTrue(sense(now, now - 30_000).line.contains("刚刚"))
        assertTrue(sense(now, now - 5 * 60_000).line.contains("几分钟"))
        assertTrue(sense(now, now - 30 * 60_000).line.contains("半小时"))
        assertTrue(sense(now, now - 90 * 60_000).line.contains("一两个小时"))
    }

    @Test
    fun `a long silence is counted in days, not in minutes`() {
        val now = at(2026, 10, 11, 15, 0)
        val twoDaysAgo = now - 2 * 24 * 3600_000L
        val line = sense(now, twoDaysAgo).line
        assertTrue("要说两天：$line", line.contains("两天"))
        assertFalse("不该报分钟数给一个过了两天的人：$line", line.contains("2880"))
    }

    @Test
    fun `weeks are spoken as weeks`() {
        val now = at(2026, 10, 11, 15, 0)
        val line = sense(now, now - 14 * 24 * 3600_000L).line
        assertTrue("两周要说成两个星期：$line", line.contains("个星期"))
    }

    @Test
    fun `no last message means no gap line at all`() {
        val s = sense(at(2026, 10, 11, 15, 0), null)
        assertNull(s.sinceLastMs)
        assertFalse("今天还没说过话时不该编一个间隔", s.line.contains("他上"))
    }

    @Test
    fun `a last message in the future is ignored instead of producing nonsense`() {
        val now = at(2026, 10, 11, 15, 0)
        val s = sense(now, now + 3600_000)
        assertNull("时钟错乱时宁可不说话，也不能说「他 60 分钟前说过」", s.sinceLastMs)
    }

    @Test
    fun `she always knows her day starts at four in the morning`() {
        // 否则"今天""昨天"在她那里是没有实感的
        assertTrue(sense(at(2026, 10, 11, 22, 0)).line.contains("凌晨四点"))
    }

    @Test
    fun `the line is never empty`() {
        listOf(0, 3, 6, 12, 18, 23).forEach { h ->
            assertTrue("$h 点时的时间感不能是空的", sense(at(2026, 10, 11, h, 0)).line.isNotBlank())
        }
    }

    @Test
    fun `the clock reads like a person, not like a timetable`() {
        val d = { h: Int, m: Int -> LocalDateTime.of(2026, 10, 11, h, m) }
        assertEquals("两点半", TimeSense.clock(d(14, 30)))
        assertEquals("三点整", TimeSense.clock(d(15, 0)))
        assertEquals("九点一刻", TimeSense.clock(d(9, 15)))
        assertEquals("七点三刻", TimeSense.clock(d(19, 45)))
        assertEquals("十二点整", TimeSense.clock(d(12, 0)))
        assertEquals("十二点整", TimeSense.clock(d(0, 0)))
        assertEquals("十一点五分", TimeSense.clock(d(23, 5)))
    }

    @Test
    fun `the clock never uses 24 hour numbers a person would not say`() {
        (0..23).forEach { h ->
            val out = TimeSense.clock(LocalDateTime.of(2026, 10, 11, h, 0))
            assertFalse("没人会说「${h}点」这种 24 小时制：$out", Regex("""\b(1[3-9]|2[0-3])点""").containsMatchIn(out))
        }
    }

    @Test
    fun `chinese numbers work across the range we need`() {
        assertEquals("五", TimeSense.chineseNumber(5))
        assertEquals("十", TimeSense.chineseNumber(10))
        assertEquals("十五", TimeSense.chineseNumber(15))
        assertEquals("二十一", TimeSense.chineseNumber(21))
        assertEquals("三十", TimeSense.chineseNumber(30))
        assertEquals("五十九", TimeSense.chineseNumber(59))
    }

    @Test
    fun `two oclock is spoken as liang not er`() {
        // 「两点半」是人话，「二点半」是机器话
        assertEquals("两点整", TimeSense.clock(LocalDateTime.of(2026, 10, 11, 14, 0)))
        assertEquals("两点半", TimeSense.clock(LocalDateTime.of(2026, 10, 11, 14, 30)))
        assertEquals("两点五分", TimeSense.clock(LocalDateTime.of(2026, 10, 11, 2, 5)))
        // 分钟位上仍然是「二」
        assertEquals("三点二十分", TimeSense.clock(LocalDateTime.of(2026, 10, 11, 15, 20)))
    }
}
