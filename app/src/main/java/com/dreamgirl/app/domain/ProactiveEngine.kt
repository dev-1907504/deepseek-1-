package com.dreamgirl.app.domain

import com.dreamgirl.app.data.ApiKeyProvider
import com.dreamgirl.app.data.MessageDao
import com.dreamgirl.app.data.DayClock
import com.dreamgirl.app.data.AppSettingsProvider
import com.dreamgirl.app.llm.LlmEvent
import com.dreamgirl.app.sense.SenseProvider
import java.time.LocalDate
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 主动消息的「该不该开口」这一半（方案 §4 第二条流程）。
 *
 * 流程：
 *   感知层聚合出本地一句摘要 → [TriggerEngine] 判定命中 → 走和聊天同一条 prompt 流程
 *   → 写回 Room + 发通知栏。
 *
 * 触发条件里**没有任何原始日志**离开过这台手机；进模型的只有
 * [TriggerEngine.Decision.situationLine] 这一句本地生成的摘要。
 */
class ProactiveEngine(
    private val messageDao: MessageDao,
    private val settings: AppSettingsProvider,
    private val apiKeys: ApiKeyProvider,
    private val sense: SenseProvider,
    private val chat: ChatEngine,
    private val triggers: TriggerEngine = TriggerEngine(),
) {

    sealed interface Outcome {
        data object Skipped : Outcome
        data class SkippedReason(val why: String) : Outcome
        data class Spoke(val text: String, val reason: TriggerEngine.Reason) : Outcome
        data class Failed(val message: String) : Outcome
    }

    /** 检查一次，命中就开口。幂等、可被 Worker / 前台服务反复调用。 */
    suspend fun checkOnce(now: Long = System.currentTimeMillis()): Outcome {
        val cfg = settings.current()
        if (!cfg.proactiveEnabled) return Outcome.SkippedReason("主动消息开关是关的")
        if (!apiKeys.hasKey) return Outcome.SkippedReason("还没配置 API Key")
        if (sense.hasPermission() && !cfg.usageStatsEnabled) {
            return Outcome.SkippedReason("使用时长读取没打开")
        }

        // 用调用方给的时间戳推时刻，而不是 LocalDateTime.now() —— 
        // 否则「几点该不该开口」这件事在测试里根本无法复现。
        val nowLdt = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault())
        val today = DayClock.today()
        val agg = runCatching { sense.snapshot(now) }.getOrNull()
        val lastChatAt = messageDao.lastMessage()?.createdAt
        val sinceLastChat = lastChatAt?.let { ((now - it) / 60_000L).toInt().coerceAtLeast(0) }

        val snapshot = TriggerEngine.Snapshot(
            now = nowLdt,
            screenOnMinutesToday = agg?.screenOnMinutesToday ?: 0,
            continuousUseMinutes = agg?.continuousUseMinutes ?: 0,
            foregroundPackage = agg?.foregroundPackage,
            foregroundLabel = agg?.foregroundLabel,
            minutesSinceLastChat = sinceLastChat,
        )

        val decision = triggers.decide(
            snapshot = snapshot,
            lastFiredAt = cfg.lastProactiveAt.takeIf { it > 0 },
            nowMillis = now,
        ) ?: return Outcome.Skipped

        // 刚聊完就别插嘴：她该闭嘴的时候要闭嘴（方案 v3「打断策略」的 v1 简化版）
        if (sinceLastChat != null && sinceLastChat < 20 &&
            decision.reason != TriggerEngine.Reason.LATE_NIGHT &&
            decision.reason != TriggerEngine.Reason.DEEP_NIGHT
        ) {
            return Outcome.SkippedReason("刚聊完没多久，先不打扰")
        }

        val sb = StringBuilder()
        var failure: String? = null
        chat.proactive(decision.situationLine).collect { ev ->
            when (ev) {
                is ChatEngine.ChatEvent.Delta -> sb.append(ev.text)
                is ChatEngine.ChatEvent.Failed -> failure = ev.error.message
                else -> Unit
            }
        }
        if (failure != null) return Outcome.Failed(failure!!)
        val text = sb.toString().trim()
        if (text.isEmpty()) return Outcome.Failed("她没说出话来")

        settings.setLastProactiveAt(now)
        return Outcome.Spoke(text, decision.reason)
    }
}
