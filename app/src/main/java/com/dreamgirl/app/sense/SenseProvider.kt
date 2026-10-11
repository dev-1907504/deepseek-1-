package com.dreamgirl.app.sense

/**
 * 感知层对外只暴露这一张「本地聚合后的快照」。
 *
 * 原始使用日志永远不出这一层（方案 §7-4），所以主动消息引擎拿不到、
 * 也就不可能把原始日志送进模型。
 */
data class SenseSnapshot(
    val screenOnMinutesToday: Int = 0,
    val continuousUseMinutes: Int = 0,
    val foregroundPackage: String? = null,
    val foregroundLabel: String? = null,
    /** 今天每个 App 用了多久（给日报用）。 */
    val topApps: List<Pair<String, Int>> = emptyList(),
)

interface SenseProvider {
    /** 用户是否授予了「使用情况访问」。 */
    fun hasPermission(): Boolean

    /** 今天（04:00 切）到现在为止的聚合结果。 */
    fun snapshot(now: Long = System.currentTimeMillis()): SenseSnapshot

    /** 最后一次熄屏时间，拿不到就 null。 */
    fun lastScreenOffAt(now: Long = System.currentTimeMillis()): Long?
}
