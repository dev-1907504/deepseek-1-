package com.dreamgirl.app.domain

/**
 * 「这句话要不要用 deepseek-reasoner 来想」的判定（方案 §5 模型表）。
 *
 * 为什么需要它：reasoner 更慢、更爱讲道理，**日常闲聊用它反而更不像她**。
 * 只有用户真的在纠结一件事、需要"先调查再分析"时（行为原则 4），才值得切过去。
 *
 * 宁可漏判也不要误判 —— 误判会让一句"今天好累"变成一段分析报告。
 */
object QuestionClassifier {

    /**
     * 强标记：一出现就基本可以确定是在求助决策。
     * 这类话往往很短（"该不该换啊"），所以**不设长度门槛**。
     */
    private val STRONG_MARKERS = listOf(
        "怎么办", "该不该", "要不要", "值不值得", "值得吗", "利弊",
        "帮我分析", "帮我看看", "帮我判断", "给我个建议", "怎么选", "选哪个",
        "可行性", "要不要换", "要不要辞",
    )

    /**
     * 弱标记：单看有歧义，需要话本身够长才认。
     * 比如"为什么"可能只是随口一问。
     */
    private val WEAK_MARKERS = listOf(
        "为什么", "原因是什么", "规划", "计划一下", "能不能", "投资", "风险",
    )

    /** 纯情绪、纯寒暄 —— 出现这些就绝不切。 */
    private val SOFT_MARKERS = listOf(
        "好累", "困", "晚安", "早安", "在吗", "哈哈", "想你了",
        "无聊", "开心", "难过", "抱抱", "摸摸",
    )

    private const val WEAK_MIN_LENGTH = 12

    fun isHardQuestion(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false

        val hasStrong = STRONG_MARKERS.any { t.contains(it) }
        val hasWeak = WEAK_MARKERS.any { t.contains(it) }
        if (!hasStrong && !hasWeak) return false

        // 在宣泄情绪的时候，就算夹着"怎么办"，她要做的也是接住情绪而不是出分析报告
        if (SOFT_MARKERS.any { t.contains(it) }) return false

        return if (hasStrong) t.length >= 2 else t.length >= WEAK_MIN_LENGTH
    }
}
