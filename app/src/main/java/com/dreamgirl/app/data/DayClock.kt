package com.dreamgirl.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 「一天」的边界不是午夜，而是本地时间 04:00（方案 §6.1）。
 * 熬夜到凌晨 2 点说的话，算前一天 —— 这符合她的语境。
 */
object DayClock {
    const val DAY_START_HOUR = 4

    fun zone(): ZoneId = ZoneId.systemDefault()

    /** 给定时刻属于哪个「会话日」。 */
    fun sessionDateOf(epochMillis: Long, zone: ZoneId = zone()): LocalDate {
        val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone)
        return if (dt.hour < DAY_START_HOUR) dt.toLocalDate().minusDays(1) else dt.toLocalDate()
    }

    fun today(zone: ZoneId = zone()): LocalDate = sessionDateOf(System.currentTimeMillis(), zone)

    /** 会话日的起点（04:00）对应的毫秒。 */
    fun startOfSessionDay(date: LocalDate, zone: ZoneId = zone()): Long =
        date.atTime(DAY_START_HOUR, 0).atZone(zone).toInstant().toEpochMilli()

    fun endOfSessionDay(date: LocalDate, zone: ZoneId = zone()): Long =
        startOfSessionDay(date.plusDays(1), zone) - 1

    /** 展示用：「今天 / 昨天 / 10月9日」。 */
    fun label(date: LocalDate, today: LocalDate = today()): String = when (date) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        today.minusDays(2) -> "前天"
        else -> "%d月%d日".format(date.monthValue, date.dayOfMonth)
    }
}
