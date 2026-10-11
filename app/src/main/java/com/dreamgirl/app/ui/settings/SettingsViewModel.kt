package com.dreamgirl.app.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreamgirl.app.data.ApiKeyStore
import com.dreamgirl.app.data.AppSettings
import com.dreamgirl.app.data.SettingsStore
import com.dreamgirl.app.llm.DeepSeekClient
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val store: SettingsStore,
    private val keys: ApiKeyStore,
    private val client: DeepSeekClient,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = store.flow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    var maskedKey by mutableStateOf(keys.masked())
        private set

    var testState by mutableStateOf<TestState>(TestState.Idle)
        private set

    sealed interface TestState {
        data object Idle : TestState
        data object Running : TestState
        data class Ok(val ms: Long) : TestState
        data class Fail(val message: String) : TestState
    }

    fun saveKey(raw: String) {
        keys.apiKey = raw
        maskedKey = keys.masked()
        testState = TestState.Idle
    }

    fun clearKey() {
        keys.clear()
        maskedKey = null
        testState = TestState.Idle
    }

    fun testConnection() {
        viewModelScope.launch {
            testState = TestState.Running
            val cfg = store.current()
            val start = System.currentTimeMillis()
            val r = client.ping(cfg.baseUrl, cfg.chatModel)
            val took = System.currentTimeMillis() - start
            testState = r.fold(
                onSuccess = { TestState.Ok(took) },
                onFailure = { TestState.Fail(it.message ?: "连接失败") },
            )
        }
    }

    fun setBaseUrl(v: String) = viewModelScope.launch { store.setBaseUrl(v) }
    fun setChatModel(v: String) = viewModelScope.launch { store.setChatModel(v) }
    fun setTemperature(v: Double) = viewModelScope.launch { store.setTemperature(v) }
    fun setMaxTokens(v: Int) = viewModelScope.launch { store.setMaxTokens(v) }
    fun setUserAddress(v: String) = viewModelScope.launch { store.setUserAddress(v) }
    fun setProactive(v: Boolean) = viewModelScope.launch { store.setProactive(v) }
    fun setUsageStats(v: Boolean) = viewModelScope.launch { store.setUsageStats(v) }
    fun setNotifications(v: Boolean) = viewModelScope.launch { store.setNotifications(v) }
    fun setUseReasoner(v: Boolean) = viewModelScope.launch { store.setUseReasoner(v) }
    fun setReplyCharLimit(v: Int) = viewModelScope.launch { store.setReplyCharLimit(v) }
}
