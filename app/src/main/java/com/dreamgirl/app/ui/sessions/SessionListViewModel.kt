package com.dreamgirl.app.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreamgirl.app.data.ApiKeyProvider
import com.dreamgirl.app.data.AppDatabase
import com.dreamgirl.app.data.DayClock
import com.dreamgirl.app.data.SessionPreview
import com.dreamgirl.app.data.AppSettings
import com.dreamgirl.app.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 资料头要显示的东西。 */
data class HerProfile(
    val displayName: String = "dream",
    val mood: String = "有点懒洋洋的",
    val signature: String = AppSettings.DEFAULT_SIGNATURE,
    val avatarFile: String? = null,
    val online: Boolean = false,
)

/** 还没总结过时候的兜底心情 —— 中性，不假装。 */
const val DEFAULT_MOOD = "今天还没说过话"

class SessionListViewModel(
    db: AppDatabase,
    private val settings: SettingsStore,
    private val apiKeys: ApiKeyProvider,
) : ViewModel() {

    /**
     * 会话列表：一行 = 和她的一天。
     * 「今天」这一行永远存在 —— 哪怕今天还没开口，也得有个地方点进去说话。
     */
    val sessions: StateFlow<List<SessionPreview>> = db.messageDao()
        .observeSessionPreviews()
        .map { rows ->
            val today = DayClock.today().toString()
            if (rows.any { it.sessionDate == today }) {
                rows
            } else {
                listOf(
                    SessionPreview(
                        sessionDate = today,
                        content = "",
                        role = "assistant",
                        createdAt = 0L,
                        messageCount = 0,
                    ),
                ) + rows
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 她最近的心情 —— **完全由她自己写**（日总结里那个 her_mood），用户设不了。
     * 还没总结过就用一句中性的兜底，不假装知道她的心情。
     */
    private val latestMood: StateFlow<String?> = db.daySummaryDao().observeAll()
        .map { list -> list.firstOrNull { !it.herMood.isNullOrBlank() }?.herMood }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val profile: StateFlow<HerProfile> = combine(
        settings.flow,
        latestMood,
        MutableStateFlow(apiKeys.hasKey),
    ) { cfg, mood, online ->
        HerProfile(
            displayName = cfg.displayName,
            // 实时心情（她每次回复自己写的）优先；没有就用日总结里那句；再没有就不装
            mood = cfg.liveMood ?: mood ?: DEFAULT_MOOD,
            signature = cfg.userSignature,
            avatarFile = cfg.avatarFile,
            // 「在线」= 她真的能说话（Key 配好了）。没配 Key 就是离线 —— 不装样子。
            online = online,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HerProfile())

    fun setName(v: String) = viewModelScope.launch { settings.setDisplayName(v) }
    fun setSignature(v: String) = viewModelScope.launch { settings.setUserSignature(v) }
    fun setAvatarFile(v: String?) = viewModelScope.launch { settings.setAvatarFile(v) }
}
