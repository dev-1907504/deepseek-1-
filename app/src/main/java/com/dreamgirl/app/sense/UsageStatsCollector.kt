package com.dreamgirl.app.sense

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import com.dreamgirl.app.data.DayClock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 感知层（方案 §7-4）：**原始日志永不进模型**。
 * 这里只做本地聚合，产出一句话摘要交给 [com.dreamgirl.app.domain.TriggerEngine]。
 *
 * 只读 PACKAGE_USAGE_STATS —— 通知和读屏在 v1/v2 一律默认关。
 */
class UsageStatsCollector(private val context: Context) : SenseProvider {

    private val usm: UsageStatsManager? =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    /** 用户是否授权了「使用情况访问」。 */
    override fun hasPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = try {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } catch (t: Throwable) {
            return false
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** 今天（04:00 切）到现在为止的聚合结果。 */
    override fun snapshot(now: Long): SenseSnapshot {
        val date: LocalDate = DayClock.sessionDateOf(now)
        val start = DayClock.startOfSessionDay(date)
        if (!hasPermission() || usm == null) {
            return SenseSnapshot()
        }
        val events = usm.queryEvents(start, now)
        val ev = UsageEvents.Event()

        var screenOnMillis = 0L
        var lastResumeAt = 0L
        var lastPauseAt = 0L
        val perApp = LinkedHashMap<String, Long>()
        var currentForeground: String? = null
        var lastInteraction = 0L

        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            when (ev.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE, UsageEvents.Event.KEYGUARD_HIDDEN -> {
                    if (lastPauseAt == 0L) lastResumeAt = ev.timeStamp
                }

                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.KEYGUARD_SHOWN -> {
                    if (lastResumeAt > 0) {
                        screenOnMillis += (ev.timeStamp - lastResumeAt).coerceAtLeast(0)
                        lastResumeAt = 0
                    }
                }

                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    currentForeground = ev.packageName
                    lastInteraction = ev.timeStamp
                    val prev = perApp[ev.packageName] ?: 0L
                    perApp[ev.packageName] = prev // 时长在 MOVE_TO_BACKGROUND 时结算
                }

                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    val prev = perApp[ev.packageName] ?: 0L
                    // 用事件间隔近似单次使用时长
                    perApp[ev.packageName] = prev + 1
                }

                UsageEvents.Event.USER_INTERACTION ->
                    lastInteraction = ev.timeStamp
            }
        }

        // 没配 SCREEN_INTERACTIVE 事件时，退化成"有前台就当作在亮屏"
        if (screenOnMillis == 0L && lastResumeAt > 0) {
            screenOnMillis = (now - lastResumeAt).coerceAtLeast(0)
        }

        val continuous = if (currentForeground != null && lastInteraction > 0) {
            ((now - lastInteraction) / 60_000L).toInt().coerceAtLeast(0)
        } else {
            0
        }

        return SenseSnapshot(
            screenOnMinutesToday = (screenOnMillis / 60_000L).toInt(),
            continuousUseMinutes = continuous,
            foregroundPackage = currentForeground,
            foregroundLabel = currentForeground?.let { labelOf(it) },
            topApps = perApp.entries.sortedByDescending { it.value }.take(5)
                .map { labelOf(it.key) to it.value.toInt() },
        )
    }

    /** 把包名换成人类可读的名字；拿不到就退回包名。 */
    fun labelOf(pkg: String): String = try {
        val pm = context.packageManager
        val info = pm.getApplicationInfo(pkg, 0)
        pm.getApplicationLabel(info).toString()
    } catch (t: Throwable) {
        pkg
    }

    /** 最后一次熄屏的时刻。 */
    override fun lastScreenOffAt(now: Long): Long? {
        if (!hasPermission() || usm == null) return null
        val start = DayClock.startOfSessionDay(DayClock.sessionDateOf(now))
        val events = usm.queryEvents(start, now)
        val ev = UsageEvents.Event()
        var last: Long? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            if (ev.eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE) last = ev.timeStamp
        }
        return last
    }

    /** 给「用机日报」用：某天每个 App 的使用时长（分钟）。 */
    fun dailyReport(date: LocalDate, now: Long = System.currentTimeMillis()): List<Pair<String, Int>> {
        if (!hasPermission() || usm == null) return emptyList()
        val start = DayClock.startOfSessionDay(date)
        val end = minOf(DayClock.endOfSessionDay(date), now)
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end).orEmpty()
        return stats
            .filter { it.totalTimeInForeground > 60_000 }
            .sortedByDescending { it.totalTimeInForeground }
            .take(12)
            .map { labelOf(it.packageName) to (it.totalTimeInForeground / 60_000L).toInt() }
    }

    /** 今天几点睡（最后一次屏幕关闭），没有就是 null。 */
    fun sleepTime(date: LocalDate, now: Long = System.currentTimeMillis()): LocalDateTime? {
        if (!hasPermission() || usm == null) return null
        val start = DayClock.startOfSessionDay(date)
        val events = usm.queryEvents(start, minOf(now, DayClock.endOfSessionDay(date)))
        val ev = UsageEvents.Event()
        var lastOff: Long? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            if (ev.eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE) lastOff = ev.timeStamp
        }
        return lastOff?.let {
            LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it), ZoneId.systemDefault())
        }
    }
}
