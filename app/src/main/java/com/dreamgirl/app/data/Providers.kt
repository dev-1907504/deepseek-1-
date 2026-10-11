package com.dreamgirl.app.data

/**
 * 这两层薄接口存在的唯一目的：让核心编排在没有 Android 运行时的情况下可测。
 * 真实实现分别是 [SettingsStore]（DataStore）和 [ApiKeyStore]（Keystore）。
 */
interface AppSettingsProvider {
    suspend fun current(): AppSettings

    /** 只有主动消息用得上；测试替身可以什么都不做。 */
    suspend fun setLastProactiveAt(v: Long) {}

    /** 存下她这次自己写的心情。测试替身可以什么都不做。 */
    suspend fun setLiveMood(v: String?) {}

    /** 她此刻的内心状态（情绪 + 不满 + 连续没理他的次数）。 */
    suspend fun innerState(): InnerStateSnapshot = InnerStateSnapshot()

    suspend fun saveInnerState(s: InnerStateSnapshot) {}

    /** 记下一次"她没开口"。 */
    suspend fun bumpSilentStreak() {}

    suspend fun resetSilentStreak() {}
}

interface ApiKeyProvider {
    val apiKey: String?
    val hasKey: Boolean
}

/**
 * 她的内心的持久化形态。
 *
 * **两个独立的量**：
 *  - mood = 情绪，来自她自己的一天和相处中真发生的事 → 决定她怎么说话
 *  - resentment = 不满，**只有他的行为**能加 → 决定她回不回
 *
 * 混在一起就会变成"我今天心情差所以不理你"，那是耍人不是人。
 */
data class InnerStateSnapshot(
    val moodLabel: String = "",
    val moodValence: Double = 0.0,
    val moodAt: Long = 0L,
    val resentmentLevel: Double = 0.0,
    val resentmentReason: String = "",
    val resentmentAt: Long = 0L,
    /** 已经连续几次没理他（兜住冷暴力的上限）。 */
    val silentStreak: Int = 0,
)
