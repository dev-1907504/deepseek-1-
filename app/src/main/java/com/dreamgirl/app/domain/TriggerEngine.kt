package com.dreamgirl.app.domain

import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 主动消息的规则引擎（方案 §4「一次主动消息的流程」/ §7-4）。
 *
 * 设计约束：
 *  - 感知层只送**本地聚合好的一句话**进来，原始日志永不进模型（方案 §7-4）。
 *  - 什么时候该说话、什么时候闭嘴，由这里决定；她本人只管怎么说。
 *  - 每条规则有冷却时间，避免变成骚扰。
 */
class TriggerEngine {

    enum class Reason(val label: String) {
        LATE_NIGHT("熬夜"),
        LONG_SCREEN("久坐/长时间盯屏"),
        LONG_GAME("连续游戏"),
        DEEP_NIGHT("深夜未睡"),
        NO_CONTACT("好久没说话"),
        MORNING("新的一天"),
    }

    data class Snapshot(
        val now: LocalDateTime,
        /** 今天（按 04:00 切）累计亮屏分钟数。 */
        val screenOnMinutesToday: Int,
        /** 当前连续使用分钟数（前台有 App 且屏幕亮）。 */
        val continuousUseMinutes: Int,
        /** 当前前台 App 的包名，可能为空（拿不到使用权限时）。 */
        val foregroundPackage: String?,
        val foregroundLabel: String?,
        /** 距上次和她说人话已经过去多久（分钟）。 */
        val minutesSinceLastChat: Int?,
    )

    data class Decision(
        val reason: Reason,
        /** 给模型看的那一句本地摘要，不含原始日志。 */
        val situationLine: String,
    )

    /** 每条规则的冷却时间（分钟）。 */
    private val cooldownMinutes = mapOf(
        Reason.LATE_NIGHT to 90,
        Reason.LONG_SCREEN to 60,
        Reason.LONG_GAME to 75,
        Reason.DEEP_NIGHT to 120,
        Reason.NO_CONTACT to 6 * 60,
        Reason.MORNING to 20 * 60,
    )

    /**
     * @param lastFiredAt 上次主动开口的时间戳（毫秒），无则 null
     */
    fun decide(snapshot: Snapshot, lastFiredAt: Long?, nowMillis: Long): Decision? {
        val time = snapshot.now.toLocalTime()

        val candidates = buildList {
            if (snapshot.continuousUseMinutes >= 90) {
                val isGame = isGamePackage(snapshot.foregroundPackage)
                if (isGame) {
                    add(
                        Decision(
                            Reason.LONG_GAME,
                            "现在 ${hhmm(time)}，他还在玩${snapshot.foregroundLabel ?: "游戏"}，" +
                                "已经连着 ${snapshot.continuousUseMinutes} 分钟没停了。",
                        ),
                    )
                } else {
                    add(
                        Decision(
                            Reason.LONG_SCREEN,
                            "现在 ${hhmm(time)}，他已经连续盯着屏幕 ${snapshot.continuousUseMinutes} 分钟了。",
                        ),
                    )
                }
            }
            if (time.hour in 0..4) {
                add(
                    Decision(
                        Reason.DEEP_NIGHT,
                        "现在 ${hhmm(time)}，他还没睡。今天累计亮屏约 ${snapshot.screenOnMinutesToday} 分钟。",
                    ),
                )
            } else if (time.hour >= 23) {
                add(
                    Decision(
                        Reason.LATE_NIGHT,
                        "现在 ${hhmm(time)}，已经到这个点了，他还醒着。今天累计亮屏约 ${snapshot.screenOnMinutesToday} 分钟。",
                    ),
                )
            }
            val since = snapshot.minutesSinceLastChat
            if (since != null && since >= 8 * 60 && time.hour in 9..22) {
                add(
                    Decision(
                        Reason.NO_CONTACT,
                        "他已经大约 ${since / 60} 小时没来找你说过话了。",
                    ),
                )
            }
            if (time.hour in 7..9 && snapshot.screenOnMinutesToday in 1..20) {
                add(
                    Decision(
                        Reason.MORNING,
                        "现在是 ${hhmm(time)}，他刚醒没多久，今天的屏幕时间才 ${snapshot.screenOnMinutesToday} 分钟。",
                    ),
                )
            }
        }

        if (candidates.isEmpty()) return null
        // 优先级：深夜/熬夜 > 久坐/游戏 > 久未联系 > 早上
        val ordered = candidates.sortedBy { priority(it.reason) }
        val top = ordered.first()
        val cd = cooldownMinutes.getValue(top.reason)
        if (lastFiredAt != null && nowMillis - lastFiredAt < cd * 60_000L) {
            // 冷却中 → 退而求其次，找下一条不在冷却里的
            return ordered.firstOrNull { d ->
                val c = cooldownMinutes.getValue(d.reason)
                lastFiredAt == null || nowMillis - lastFiredAt >= c * 60_000L
            }
        }
        return top
    }

    private fun priority(r: Reason): Int = when (r) {
        Reason.DEEP_NIGHT -> 0
        Reason.LATE_NIGHT -> 1
        Reason.LONG_GAME -> 2
        Reason.LONG_SCREEN -> 3
        Reason.NO_CONTACT -> 4
        Reason.MORNING -> 5
    }

    private val gameHints = listOf(
        "game", "miHoYo", "tencent.tmgp", "netease.x19", "hypergryph",
        "sankuai", "aweme", "bili", "taptap",
    )

    private fun isGamePackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        return gameHints.any { pkg.contains(it, ignoreCase = true) }
    }

    private fun hhmm(t: LocalTime): String = "%02d:%02d".format(t.hour, t.minute)
}
