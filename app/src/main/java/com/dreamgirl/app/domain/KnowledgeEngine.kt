package com.dreamgirl.app.domain

import com.dreamgirl.app.persona.CharacterLayerVerbatim
import kotlin.math.abs

/**
 * 她「懂的那些事」——知识卡检索。
 *
 * 设计要点（成本优先）：
 *  1. **默认不注入**。命中不够就一张都不给，宁可不用，也不要硬塞一张不相关的卡
 *     —— 聊吃饭的时候她掏出「挽回话术」，比不懂更糟。
 *  2. **一次最多 [MAX_INJECTED] 张**，注入总量有字符上限，绝不挤占人设和记忆。
 *  3. 纯本地、纯字符串匹配，不联网、不额外调模型 —— **不命中的时候一个 token 都不花**。
 *
 * 检索分三层，从稳到松：
 *  - 精确：触发词（长词优先）出现在他这句话里
 *  - 字符：中文 2-gram 重叠（同一套切分和记忆检索用，行为一致好解释）
 *  - 心情：她很爱他，所以**她自己今天的心情**也会影响她怎么理解这句话
 *    （心情好就往好处想，蔫着就往不动弹的地方想）
 */
object KnowledgeEngine {

    /** 一次最多注入几张卡。 */
    const val MAX_INJECTED = 2

    /** 注入正文的字符硬上限（不含标题）。 */
    const val MAX_INJECTED_CHARS = 420

    /** 低于这个分就算不上「撞上了」，直接不注入。 */
    const val MIN_SCORE = 2.6

    /** 触发词精确命中的权重。 */
    private const val EXACT_WEIGHT = 3.0

    /** 中文 2-gram 重叠权重。 */
    private const val GRAM_WEIGHT = 1.0

    /** 心情把某张卡抬起来 / 压下去的幅度。 */
    private const val MOOD_NUDGE = 1.6

    /** 她的心情词里，表示"有劲"的字。 */
    private val UP_WORDS = listOf(
        "翘", "来劲", "高兴", "开心", "得意", "兴奋", "精神", "暖", "松", "亮",
        "好", "甜", "乐", "踏实", "轻", "想", "活",
    )

    /** 表示"蔫着 / 不想动"的字。 */
    private val DOWN_WORDS = listOf(
        "蔫", "累", "烦", "低", "沉", "闷", "困", "钝", "淡", "静", "丧",
        "懒", "软", "慌", "乱", "空", "酸", "堵", "不想",
    )

    data class Hit(
        val card: KnowledgeCardsVerbatim.Card,
        val score: Double,
        val why: String,
    )

    /**
     * 她今天心里惦记着哪张卡。
     *
     * 同一天同一心情只会翻到同一张 —— 这样她这一天有个**一贯的心境**，
     * 不是每句话都在换人。跨过 04:00（新的会话日）就换一张。
     *
     * @param today 会话日 key（"2026-10-11"）
     * @param mood  她此刻的心情（她自己写的那行），可为空
     */
    fun cardOfTheDay(today: String, mood: String?, now: Long = System.currentTimeMillis()): KnowledgeCardsVerbatim.Card? {
        val all = KnowledgeCardsVerbatim.ALL
        if (all.isEmpty()) return null
        val seed = today.hashCode().toLong() * 31 + (mood?.hashCode() ?: 0) + now / 86_400_000L
        val idx = abs(seed % all.size).toInt()
        return all[idx]
    }

    /**
     * 按他这句话挑卡。返回按相关度排序、已经过阈值和条数限制的结果。
     *
     * @param text    他刚说的那句话
     * @param mood    她此刻的心情（她会把自己的心境读进这句话里）
     */
    fun retrieve(text: String, mood: String? = null): List<Hit> {
        val query = text.trim()
        if (query.isEmpty()) return emptyList()

        val grams = Keywords.of(query).toSet()
        val moodTerms = mood?.takeIf { it.isNotBlank() }?.let { Keywords.of(it).toSet() } ?: emptySet()
        if (grams.isEmpty() && moodTerms.isEmpty()) return emptyList()

        val moodTone = toneOf(mood)

        val scored = KnowledgeCardsVerbatim.ALL.map { card ->
            var score = 0.0
            val why = StringBuilder()

            // 1) 触发词精确命中（长词权重更高，避免"烦"这种单字乱撞）
            var exact = 0.0
            card.triggers.forEach { t ->
                if (t.isNotBlank() && query.contains(t)) {
                    exact += EXACT_WEIGHT * (1.0 + t.length * 0.15)
                }
            }
            if (exact > 0) {
                score += exact
                why.append("触发词")
            }

            // 2) 中文 2-gram 重叠
            var gramHits = 0
            grams.forEach { g -> if (card.rule.contains(g) || card.triggers.any { it.contains(g) }) gramHits++ }
            if (gramHits > 0) {
                // 开方压一下：长句不该只因为字多就赢
                score += kotlin.math.sqrt(gramHits.toDouble()) * GRAM_WEIGHT
                why.append(if (why.isEmpty()) "用词相近" else "+用词相近")
            }

            // 3) 心情微调：她把当下的心境读进这件事里。
            //    心情是自由文本，拆成碎片去撞词表几乎撞不上（第一版就是这么错的，
            //    被单测逮住）。正确做法是把它当成"她此刻的底色"：用一个整体分数
            //    抬她愿意接话的那些卡、压她没力气碰的那些卡。
            score += moodBias(card, moodTone)

            Hit(card, score, why.toString())
        }

        return scored
            .filter { it.score >= MIN_SCORE }
            .sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.card.id })
            .take(MAX_INJECTED)
            .let { cap(it) }
    }

    /**
     * 她此刻的底色：-1（蔫、没劲、不想碰）→ 0（说不准）→ +1（来劲、心里松）。
     *
     * 这是**估**，不是分析 —— 只为了让同一天里"精神"和"蔫着"是两个不同的她。
     */
    internal fun toneOf(mood: String?): Double {
        val m = mood?.trim().orEmpty()
        if (m.isEmpty()) return 0.0
        var tone = 0.0
        UP_WORDS.forEach { if (m.contains(it)) tone += 1.0 }
        DOWN_WORDS.forEach { if (m.contains(it)) tone -= 1.0 }
        return tone.coerceIn(-1.0, 1.0)
    }

    /**
     * 底色对每张卡的影响。
     *
     * 不是"心情好就多给卡"——那等于让她心情一好就话多。
     * 是**换一批卡**：她蔫着的时候，更容易往"他不想说话""他跟我赌气"
     * 这类安静、需要退一步的事上想；来劲的时候，更容易往"他做成了事"
     * "他让我出主意"这类需要接话、需要出力的事上想。
     */
    private fun moodBias(card: KnowledgeCardsVerbatim.Card, tone: Double): Double {
        if (tone == 0.0) return 0.0
        val weight = when (card.id) {
            // 蔫着的时候更容易想到的
            "他不想说话", "他失恋或放不下", "他迷茫", "他身体累", "他很久没回我" -> -1.0
            // 来劲的时候更容易想到的
            "他做成了事", "他让我出主意", "他跟我分享一件小事", "他被气到了", "他跟我说谢谢" -> 1.0
            else -> 0.0
        }
        // 底色是 -1..1：蔫着(tone<0) 把"安静卡"抬起来；来劲(tone>0) 把"出力卡"抬起来
        return -tone * weight * MOOD_NUDGE
    }

    /** 字符硬上限：超了就只留最相关的那张。 */
    private fun cap(hits: List<Hit>): List<Hit> {
        if (hits.isEmpty()) return hits
        if (hits.first().card.rule.length > MAX_INJECTED_CHARS) return hits.take(1)
        val out = ArrayList<Hit>(hits.size)
        var used = 0
        for (h in hits) {
            if (used + h.card.rule.length > MAX_INJECTED_CHARS && out.isNotEmpty()) break
            used += h.card.rule.length
            out += h
        }
        return out
    }

    /** 拼成给 prompt 看的一小段。没命中就返回 null（**默认不注入**）。 */
    fun renderForPrompt(hits: List<Hit>): String? {
        if (hits.isEmpty()) return null
        return buildString {
            appendLine("〔你这些年学到的、跟眼前这件事有关的准绳〕")
            appendLine("（不是规矩，是你自己的看法。用你自己的话说，别照念。）")
            hits.forEach { h ->
                appendLine("- ${h.card.id}：${h.card.rule}")
            }
        }.trim()
    }

    /** 性格层：她怎么对人。每轮都在。 */
    fun characterLayer(): String = CharacterLayerVerbatim.FULL
}
