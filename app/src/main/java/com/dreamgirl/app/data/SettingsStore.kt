package com.dreamgirl.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "dreamgirl_settings")

/** 所有可调项。默认值按方案 §5 的建议来（温度 1.0、max_tokens 小）。 */
data class AppSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val chatModel: String = "deepseek-chat",
    val reasonerModel: String = "deepseek-reasoner",
    val temperature: Double = 1.0,
    val maxTokens: Int = 300,
    /** 记忆抽取这类后台小请求的温度，要稳。 */
    val utilityTemperature: Double = 0.2,
    val proactiveEnabled: Boolean = true,
    val usageStatsEnabled: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val replyLengthHint: Int = 300,
    val onboardingDone: Boolean = false,
    /** 她怎么称呼你。默认「你」。 */
    val userAddress: String = "你",
    /**
     * 她的显示名字（界面标题用）。
     * **与人格原文分离**：改这里不会动人设 2.1 节那句话，原文照旧逐字不变。
     */
    val displayName: String = "dream",
    /**
     * 单条回复的硬上限（字数）。超过就在句子边界截断。
     * 人设要求"不写小作文"，这里是那个"小作文"的刻度。
     */
    val replyCharLimit: Int = 400,
    /**
     * 我自己的签名，显示在资料头上。**纯展示，不进 prompt、不影响她**。
     */
    val userSignature: String = DEFAULT_SIGNATURE,
    /**
     * 她当前的心情。默认一句她的口吻；有日总结时会自动跟着那天的 mood 走。
     */
    val herMood: String = "有点懒洋洋的",
    /** 自定义头像的文件名（存在 App 私有目录）；null = 用内置立绘。 */
    val avatarFile: String? = null,
    /**
     * 她**此刻**的心情 —— 每次回复她自己在末尾写一行，我们抠出来存这儿。
     * 比日总结里的 her_mood 新鲜得多：聊一句就能变。
     */
    val liveMood: String? = null,
    /** 自动生成日总结：最后一条消息后多少分钟。 */
    val summaryIdleMinutes: Int = 30,
    /** 上次主动开口的时间戳，0 = 从没开过口。 */
    val lastProactiveAt: Long = 0L,
    /**
     * 遇到真正需要分析的难题时，切到 deepseek-reasoner 想（方案 §5）。
     * 默认关：日常闲聊用 reasoner 会更慢、也更不像她。
     */
    val useReasonerForHardQuestions: Boolean = false,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_SIGNATURE = "或许不知是梦的缘故，流离之人追逐幻影"
    }
}

class SettingsStore(private val context: Context) : AppSettingsProvider {

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val CHAT_MODEL = stringPreferencesKey("chat_model")
        val REASONER_MODEL = stringPreferencesKey("reasoner_model")
        val TEMPERATURE = doublePreferencesKey("temperature")
        val MAX_TOKENS = intPreferencesKey("max_tokens")
        val UTILITY_TEMPERATURE = doublePreferencesKey("utility_temperature")
        val PROACTIVE = booleanPreferencesKey("proactive_enabled")
        val USAGE_STATS = booleanPreferencesKey("usage_stats_enabled")
        val NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
        val ONBOARDING = booleanPreferencesKey("onboarding_done")
        val USER_ADDRESS = stringPreferencesKey("user_address")
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val REPLY_CHAR_LIMIT = intPreferencesKey("reply_char_limit")
        val USER_SIGNATURE = stringPreferencesKey("user_signature")
        val HER_MOOD = stringPreferencesKey("her_mood")
        val AVATAR_FILE = stringPreferencesKey("avatar_file")
        val LIVE_MOOD = stringPreferencesKey("live_mood")
        val MOOD_VALENCE = doublePreferencesKey("mood_valence")
        val MOOD_AT = longPreferencesKey("mood_at")
        val RESENTMENT = doublePreferencesKey("resentment")
        val RESENTMENT_REASON = stringPreferencesKey("resentment_reason")
        val RESENTMENT_AT = longPreferencesKey("resentment_at")
        val SILENT_STREAK = intPreferencesKey("silent_streak")
        val SUMMARY_IDLE = intPreferencesKey("summary_idle_minutes")
        val LAST_PROACTIVE = longPreferencesKey("last_proactive_at")
        val USE_REASONER = booleanPreferencesKey("use_reasoner_for_hard_questions")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { p ->
        val d = AppSettings()
        AppSettings(
            baseUrl = p[Keys.BASE_URL] ?: d.baseUrl,
            chatModel = p[Keys.CHAT_MODEL] ?: d.chatModel,
            reasonerModel = p[Keys.REASONER_MODEL] ?: d.reasonerModel,
            temperature = p[Keys.TEMPERATURE] ?: d.temperature,
            maxTokens = p[Keys.MAX_TOKENS] ?: d.maxTokens,
            utilityTemperature = p[Keys.UTILITY_TEMPERATURE] ?: d.utilityTemperature,
            proactiveEnabled = p[Keys.PROACTIVE] ?: d.proactiveEnabled,
            usageStatsEnabled = p[Keys.USAGE_STATS] ?: d.usageStatsEnabled,
            notificationsEnabled = p[Keys.NOTIFICATIONS] ?: d.notificationsEnabled,
            onboardingDone = p[Keys.ONBOARDING] ?: d.onboardingDone,
            userAddress = p[Keys.USER_ADDRESS] ?: d.userAddress,
            displayName = p[Keys.DISPLAY_NAME] ?: d.displayName,
            replyCharLimit = p[Keys.REPLY_CHAR_LIMIT] ?: d.replyCharLimit,
            userSignature = p[Keys.USER_SIGNATURE] ?: d.userSignature,
            herMood = p[Keys.HER_MOOD] ?: d.herMood,
            avatarFile = p[Keys.AVATAR_FILE] ?: d.avatarFile,
            liveMood = p[Keys.LIVE_MOOD] ?: d.liveMood,
            summaryIdleMinutes = p[Keys.SUMMARY_IDLE] ?: d.summaryIdleMinutes,
            lastProactiveAt = p[Keys.LAST_PROACTIVE] ?: d.lastProactiveAt,
            useReasonerForHardQuestions = p[Keys.USE_REASONER] ?: d.useReasonerForHardQuestions,
        )
    }

    override suspend fun current(): AppSettings = flow.first()

    suspend fun setBaseUrl(v: String) = edit { it[Keys.BASE_URL] = v.trim().trimEnd('/') }
    suspend fun setChatModel(v: String) = edit { it[Keys.CHAT_MODEL] = v.trim() }
    suspend fun setReasonerModel(v: String) = edit { it[Keys.REASONER_MODEL] = v.trim() }
    suspend fun setTemperature(v: Double) = edit { it[Keys.TEMPERATURE] = v.coerceIn(0.0, 2.0) }
    suspend fun setMaxTokens(v: Int) = edit { it[Keys.MAX_TOKENS] = v.coerceIn(64, 2048) }
    suspend fun setProactive(v: Boolean) = edit { it[Keys.PROACTIVE] = v }
    suspend fun setUsageStats(v: Boolean) = edit { it[Keys.USAGE_STATS] = v }
    suspend fun setNotifications(v: Boolean) = edit { it[Keys.NOTIFICATIONS] = v }
    suspend fun setOnboardingDone(v: Boolean) = edit { it[Keys.ONBOARDING] = v }
    suspend fun setUserAddress(v: String) = edit { it[Keys.USER_ADDRESS] = v.take(24) }
    suspend fun setDisplayName(v: String) = edit { it[Keys.DISPLAY_NAME] = v.trim().take(16) }
    suspend fun setReplyCharLimit(v: Int) = edit { it[Keys.REPLY_CHAR_LIMIT] = v.coerceIn(150, 800) }
    suspend fun setUserSignature(v: String) = edit { it[Keys.USER_SIGNATURE] = v.take(64) }
    suspend fun setHerMood(v: String) = edit { it[Keys.HER_MOOD] = v.take(16) }
    override suspend fun setLiveMood(v: String?) = edit {
        if (v.isNullOrBlank()) it.remove(Keys.LIVE_MOOD) else it[Keys.LIVE_MOOD] = v.take(20)
    }

    // ------------------------------------------------------------ 她的内心状态

    override suspend fun innerState(): InnerStateSnapshot {
        val p = context.dataStore.data.first()
        return InnerStateSnapshot(
            moodLabel = p[Keys.LIVE_MOOD] ?: "",
            moodValence = p[Keys.MOOD_VALENCE] ?: 0.0,
            moodAt = p[Keys.MOOD_AT] ?: 0L,
            resentmentLevel = p[Keys.RESENTMENT] ?: 0.0,
            resentmentReason = p[Keys.RESENTMENT_REASON] ?: "",
            resentmentAt = p[Keys.RESENTMENT_AT] ?: 0L,
            silentStreak = p[Keys.SILENT_STREAK] ?: 0,
        )
    }

    override suspend fun saveInnerState(s: InnerStateSnapshot) = edit {
        it[Keys.LIVE_MOOD] = s.moodLabel
        it[Keys.MOOD_VALENCE] = s.moodValence
        it[Keys.MOOD_AT] = s.moodAt
        it[Keys.RESENTMENT] = s.resentmentLevel
        it[Keys.RESENTMENT_REASON] = s.resentmentReason
        it[Keys.RESENTMENT_AT] = s.resentmentAt
    }

    override suspend fun bumpSilentStreak() = edit { p ->
        p[Keys.SILENT_STREAK] = (p[Keys.SILENT_STREAK] ?: 0) + 1
    }

    override suspend fun resetSilentStreak() = edit { it[Keys.SILENT_STREAK] = 0 }

    suspend fun setAvatarFile(v: String?) = edit {
        if (v.isNullOrBlank()) it.remove(Keys.AVATAR_FILE) else it[Keys.AVATAR_FILE] = v
    }
    override suspend fun setLastProactiveAt(v: Long) = edit { it[Keys.LAST_PROACTIVE] = v }
    suspend fun setUseReasoner(v: Boolean) = edit { it[Keys.USE_REASONER] = v }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
