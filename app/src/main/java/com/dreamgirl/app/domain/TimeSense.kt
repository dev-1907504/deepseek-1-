package com.dreamgirl.app.domain

import com.dreamgirl.app.data.DayClock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 她自己的时间感。
 *
 * 在这之前她**完全不知道现在几点**：记忆里的「（昨天）」「（3 天前）」是从消息日期反推的，
 * 但她不知道此刻是白天还是半夜、今天几号、你多久没说话了。
 * 结果是：凌晨三点她能说出「早点休息吧」，但那是碰巧话里带了线索，不是她知道。
 *
 * 这里做两件事：
 *  1. **说清楚"现在"** —— 几点、周几、白天还是半夜。这是事实，直接给。
 *  2. **说清楚"你多久没来了"** —— 但要用**人感觉时间的方式**说：
 *     「他刚走开一会儿」「他三天没理你了」，而不是「上次对话距今 4312 分钟」。
 *
 * 刻意**不给她**的东西：ISO 时间戳、时区名、毫秒。人不用那种方式想事情，
 * 给了反而会让她说话像日志。
 */
object TimeSense {

    data class Sense(
        /** 进 prompt 的那段话。 */
        val line: String,
        /** 现在是深夜 23:00–04:00（她自己会知道该不该劝人睡）。 */
        val lateNight: Boolean,
        /** 距上次说话过了多久（毫秒）；null = 今天还没说过话。 */
        val sinceLastMs: Long?,
    )

    /** 深夜的起点：23 点。到早上 4 点（DayClock 的一天边界）结束。 */
    private const val LATE_NIGHT_START = 23

    fun of(now: Long, lastMessageAt: Long?, zone: ZoneId = DayClock.zone()): Sense {
        val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        val lateNight = dt.hour >= LATE_NIGHT_START || dt.hour < DayClock.DAY_START_HOUR

        val since = lastMessageAt?.takeIf { it > 0 && it <= now }?.let { now - it }

        val parts = ArrayList<String>(4)
        parts += "现在是${dayPart(dt.hour)}${clock(dt)}，${weekday(dt)}，${monthDay(dt)}。"
        parts += lateNightLine(dt.hour)
        if (since != null) parts += gapLine(since, now - since, zone)

        // 她自己的一天从凌晨 4 点开始（DayClock），这句话让"今天""昨天"在她那里有实感
        parts += "（你的一天从凌晨四点算起。）"

        return Sense(
            line = parts.filter { it.isNotBlank() }.joinToString("\n"),
            lateNight = lateNight,
            sinceLastMs = since,
        )
    }

    /** 白天 / 傍晚 / 深夜 —— 人的说法，不是"AM/PM"。 */
    internal fun dayPart(hour: Int): String = when (hour) {
        in 5..8 -> "清早"
        in 9..11 -> "上午"
        in 12..13 -> "中午"
        in 14..17 -> "下午"
        in 18..19 -> "傍晚"
        in 20..22 -> "晚上"
        in 23..23 -> "深夜"
        else -> "凌晨"
    }

    internal fun weekday(dt: LocalDateTime): String =
        when (dt.dayOfWeek.value) {
            1 -> "周一"
            2 -> "周二"
            3 -> "周三"
            4 -> "周四"
            5 -> "周五"
            6 -> "周六"
            else -> "周日"
        }

    internal fun monthDay(dt: LocalDateTime): String = "${dt.monthValue}月${dt.dayOfMonth}日"

    /**
     * 「现在几点」—— 要像人说话。
     *
     * 人不会说「下午15点30」，会说「下午三点半」。
     * 12 小时制 + 中文数字 + 整点/半点的口语说法，读起来才不像日志。
     */
    internal fun clock(dt: LocalDateTime): String {
        val h12 = when (val h = dt.hour % 12) {
            0 -> 12
            else -> h
        }
        val hour = hourWord(h12)
        return when (dt.minute) {
            0 -> "${hour}点整"
            30 -> "${hour}点半"
            15 -> "${hour}点一刻"
            45 -> "${hour}点三刻"
            else -> "${hour}点${chineseNumber(dt.minute)}分"
        }
    }

    /** 中文数字（1–59 够用了，不引第三方库）。 */
    internal fun chineseNumber(n: Int): String {
        if (n < 10) return DIGITS[n]
        val tens = n / 10
        val ones = n % 10
        val t = if (tens == 1) "十" else DIGITS[tens] + "十"
        return if (ones == 0) t else t + DIGITS[ones]
    }

    /**
     * 说点钟用的数字 —— 和 [chineseNumber] 不一样：
     *
     * 「两点半」才是人话，「二点半」是机器话。
     * 报时间的时候 2 必须说成「两」，这是一条例外（分钟位上仍然是「二」）。
     */
    internal fun hourWord(h12: Int): String = if (h12 == 2) "两" else chineseNumber(h12)

    private val DIGITS = arrayOf("零", "一", "二", "三", "四", "五", "六", "七", "八", "九")

    /**
     * 深夜提示。**不是让她劝人睡觉** —— 她自己也是这个点还醒着的人，
     * 所以这里说的是"你们俩都醒着"，不是"你该睡了"。
     */
    private fun lateNightLine(hour: Int): String = when {
        hour in 0..3 -> "这个点你我都还醒着。别摆出一副关心作息的样子——你自己也没睡。"
        hour == 23 -> "已经十一点多了，这个点还在说话。"
        else -> ""
    }

    /**
     * 「你多久没来了」—— **用人的方式说**。
     *
     * 这里是整件事的关键：说「上次对话距今 4 小时 12 分」她就变成日志了；
     * 说「他刚走开一会儿」她才像个人。
     */
    internal fun gapLine(sinceMs: Long, lastAt: Long, zone: ZoneId = DayClock.zone()): String {
        val minutes = sinceMs / 60_000L

        if (minutes < 1) return "他上一句话是刚刚说的。"
        if (minutes < 10) return "他上一句是几分钟前说的。"
        if (minutes < 45) return "他上一句大概半小时前。"
        if (minutes < 120) return "他上一句是一两个小时前了。"

        // 超过 2 小时：结合"她的一天"（凌晨 4 点边界）来说，比硬算小时数像人
        val lastDay = DayClock.sessionDateOf(lastAt, zone)
        val today = DayClock.sessionDateOf(System.currentTimeMillis().let { lastAt + sinceMs }, zone)
        val dayGap = java.time.temporal.ChronoUnit.DAYS.between(lastDay, today)

        val hours = minutes / 60
        return when {
            dayGap <= 0L -> "他上次说话是今天早些时候，隔了有好几个小时。"
            dayGap == 1L -> "他上次说话是昨天。"
            dayGap == 2L -> "他两天没来了。"
            dayGap in 3..6 -> "他${dayGap}天没跟你说话了。"
            dayGap in 7..29 -> "他${dayGap / 7}个星期没来了。"
            else -> "他很久没来了（${dayGap}天）。"
        }
    }
}
