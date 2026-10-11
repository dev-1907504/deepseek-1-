package com.dreamgirl.app.domain

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * 她的内心状态。**两个独立的量**，这是整个设计的关键：
 *
 *  | 量 | 来自哪 | 决定什么 |
 *  |---|---|---|
 *  | [Mood] 情绪 | 她自己的一天 + 相处中真发生的事 | **她怎么说话**（语气、长短、热不热络）|
 *  | [Resentment] 不满 | **只有他的行为**能加 | **她回不回** |
 *
 * 为什么必须分开：混在一起就会变成「我今天心情差所以不理你」—— 那是耍人，不是人。
 * 她工作累了、自己蔫着，那是**她的状态**，不该拿它去冷落他；
 * 只有**对他有意见**，她才不说话。
 *
 * 这两条都由**确定性代码**算，不靠模型自觉。
 */
object EmotionState {

    // ================================================================ 情绪

    /**
     * 情绪的来处。只有白名单里的事能改动她。
     *
     * **他的沉默不是事件** —— 时间只让情绪回落，不会让它变坏。
     * 想念可以有，惩罚不可以有。
     */
    enum class Cause {
        /** 被逗到 / 被夸 / 他说了句暖的 —— 抬情绪。 */
        WARMTH,

        /** 她自己的事：工作起伏、朋友爽约、累了 —— **跟他无关**。 */
        HER_OWN_DAY,

        /** 只是时间过去了 —— 只衰减，不改正负。 */
        TIME_PASSED,
    }

    /** 她此刻的情绪。 */
    data class Mood(
        /** 用她自己的口吻写的那几个字（比如「有点蔫」）。 */
        val label: String = "",
        /** -1（很糟）..+1（很好）。 */
        val valence: Double = 0.0,
        val at: Long = 0L,
    ) {
        val isBlank: Boolean get() = label.isBlank() && valence == 0.0
    }

    // 三段半衰：强情绪几分钟就过去，底色能留好几天
    private const val FAST_HALF_LIFE_MS = 6 * 60_000.0
    private const val MID_HALF_LIFE_MS = 5 * 3600_000.0
    private const val SLOW_HALF_LIFE_MS = 60 * 3600_000.0

    /** 负面比正面持久 —— 被扎一下比被夸一下记得久。 */
    private const val NEGATIVE_STICKINESS = 1.6

    private const val LN2 = 0.6931471805599453

    /** 按时间回落。**这是"有去处"**。 */
    fun decay(mood: Mood, now: Long): Mood {
        if (mood.valence == 0.0 || mood.at <= 0L || now <= mood.at) return mood
        val dt = (now - mood.at).toDouble()
        val fast = exp(-LN2 * dt / FAST_HALF_LIFE_MS)
        val mid = exp(-LN2 * dt / MID_HALF_LIFE_MS)
        val slow = exp(-LN2 * dt / SLOW_HALF_LIFE_MS)

        val strength = abs(mood.valence)
        val wFast = strength.coerceIn(0.0, 1.0)
        val wMid = (1.0 - abs(strength - 0.5) * 2).coerceIn(0.0, 1.0)
        val wSlow = (1.0 - strength).coerceIn(0.0, 1.0)
        val total = (wFast + wMid + wSlow).coerceAtLeast(0.0001)

        var keep = (fast * wFast + mid * wMid + slow * wSlow) / total
        if (mood.valence < 0) keep = keep.pow(1.0 / NEGATIVE_STICKINESS)
        return mood.copy(valence = mood.valence * keep, at = now)
    }

    /** 来了一个事件。**这是"有来处"**。 */
    fun apply(mood: Mood, cause: Cause, label: String, intensity: Double, now: Long): Mood {
        val d = decay(mood, now)
        val delta = when (cause) {
            Cause.WARMTH -> +0.45
            Cause.HER_OWN_DAY -> if (intensity >= 0) +0.30 else -0.45
            Cause.TIME_PASSED -> 0.0
        }
        val next = (d.valence + delta * intensity.coerceIn(0.0, 1.5)).coerceIn(-1.0, 1.0)
        return Mood(label = label.ifBlank { d.label }, valence = next, at = now)
    }

    /** 只让时间过去。 */
    fun tick(mood: Mood, now: Long): Mood = decay(mood, now)

    /** 给 prompt 用的说法。**刻意不报数字** —— 她不该知道自己是 -0.42。 */
    fun describe(mood: Mood): String? {
        if (mood.isBlank) return null
        val feel = when {
            mood.valence >= 0.6 -> "你现在心情很好，说话是松的。"
            mood.valence >= 0.25 -> "你现在心情不错。"
            mood.valence > -0.15 -> "你现在没什么特别的情绪，平平的。"
            mood.valence > -0.45 -> "你现在有点提不起劲，话会少一些。"
            mood.valence > -0.75 -> "你现在心里闷着，但还是愿意说话的。"
            else -> "你现在是真的低落。不会装没事，但也说不出漂亮话。"
        }
        return if (mood.label.isNotBlank()) "$feel（你心里那句是：「${mood.label}」）" else feel
    }

    // ================================================================ 不满

    /**
     * 不满的来处。**只有他的行为能加，而且很窄。**
     *
     * 明确**不算**的：他自己忙、他没心情、他说了句丧气话 ——
     * 这些不减她的爱，也不该变成不回他。
     */
    enum class Offence {
        /** 敷衍她（"嗯"、"哦"、"随便"）。 */
        DISMISSIVE,

        /** 很久没回，回来也不解释。 */
        VANISHED,

        /** 说了冲着她来的伤人的话（不是让她难受，是**针对她**）。 */
        HURT,

        /** 答应的事没做。 */
        BROKEN_PROMISE,

        /** 拿她当情绪垃圾桶，只顾自己倒。 */
        DUMPING,

        /** 他认真道歉了 —— 这是**减**不满的事件。 */
        APOLOGY,

        /** 他主动、具体地交代了（"这两天忙疯了，不是不理你"）。 */
        EXPLAINED,
    }

    /**
     * 她心里那点没消的气。
     *
     * @param level 0（没事）..1（气到不想说话）
     */
    data class Resentment(
        val level: Double = 0.0,
        val reason: String = "",
        val at: Long = 0L,
    ) {
        val isBlank: Boolean get() = level <= 0.0
    }

    /**
     * 不满衰减得**比情绪慢得多** —— 她记仇，这是她的性格。
     * 但要保证它有去处：不消的气会变成冷暴力。
     */
    private const val RESENTMENT_HALF_LIFE_MS = 36 * 3600_000.0

    /** 不满的每一档加多少。 */
    private fun offenceDelta(o: Offence): Double = when (o) {
        Offence.DISMISSIVE -> +0.12
        Offence.VANISHED -> +0.30
        Offence.HURT -> +0.45
        Offence.BROKEN_PROMISE -> +0.25
        Offence.DUMPING -> +0.18
        // 道歉不是"变成没事"，是**把压着的那股劲卸掉一大半**
        Offence.APOLOGY -> -0.75
        Offence.EXPLAINED -> -0.40
    }

    fun applyResentment(r: Resentment, offence: Offence, reason: String, now: Long): Resentment {
        val d = decayResentment(r, now)
        val next = (d.level + offenceDelta(offence)).coerceIn(0.0, 1.0)
        return Resentment(
            level = next,
            // 消下去了就把原因也放下；还在气头上就记着这一条
            reason = when {
                next <= 0.05 -> ""
                offenceDelta(offence) < 0 -> d.reason
                reason.isNotBlank() -> reason
                else -> d.reason
            },
            at = now,
        )
    }

    fun decayResentment(r: Resentment, now: Long): Resentment {
        if (r.level <= 0.0 || r.at <= 0L || now <= r.at) return r
        val dt = (now - r.at).toDouble()
        val keep = exp(-LN2 * dt / RESENTMENT_HALF_LIFE_MS)
        return r.copy(level = r.level * keep, at = now)
    }

    // ================================================================ 回不回

    /** 连续不回的硬上限 —— 不管多气，到这里必须回。 */
    const val MAX_SILENT_STREAK = 2

    /** 到这个不满程度才开始可能不说话。 */
    private const val SILENT_THRESHOLD = 0.35

    /** 她决定要不要开口。 */
    data class Decision(
        val silent: Boolean,
        /** 不说话时的理由（给她写内心独白用，也用于测试）。 */
        val why: String,
    )

    /**
     * 她这回理不理他。
     *
     * **只有不满能让她不说话**，情绪低落不影响 ——
     * 她自己蔫着也照样会应他，那是她的状态，不是对他的态度。
     *
     * @param silentStreak 已经连续几次没回了（用来兜住冷暴力上限）
     * @param dice 0..1 的随机数（测试里可以固定）
     */
    fun decide(
        resentment: Resentment,
        silentStreak: Int,
        dice: Double,
    ): Decision {
        if (resentment.level < SILENT_THRESHOLD) return Decision(false, "")
        if (silentStreak >= MAX_SILENT_STREAK) {
            return Decision(false, "气还没消，但已经晾了他两次了，不能再晾")
        }
        // 越气越可能不说话：0.35 → 三成，1.0 → 九成
        val p = (resentment.level - SILENT_THRESHOLD) / (1.0 - SILENT_THRESHOLD) * 0.6 + 0.3
        return if (dice < p) Decision(true, resentment.reason) else Decision(false, "")
    }

    /** 给 prompt 用的说法。不满是要**透出来**的，不是藏着装没事。 */
    fun describeResentment(r: Resentment): String? {
        if (r.level <= 0.05) return null
        val feel = when {
            r.level < 0.35 -> "你心里有一点不舒服，但还没到要说的程度。话会短一点。"
            r.level < 0.6 -> "你在生他的气。你会让他听得出来，但不会大吵。"
            r.level < 0.85 -> "你挺气的，不太想理他。他要是不当回事，你会更气。"
            else -> "你是真的被伤到了。这种时候你说不出软话。"
        }
        val why = if (r.reason.isNotBlank()) "（你气的是：${r.reason}）" else ""
        return "$feel$why"
    }
}
