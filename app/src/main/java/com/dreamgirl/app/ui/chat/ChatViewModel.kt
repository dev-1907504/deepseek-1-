package com.dreamgirl.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreamgirl.app.data.AppDatabase
import com.dreamgirl.app.data.DayClock
import com.dreamgirl.app.data.MessageEntity
import com.dreamgirl.app.data.SettingsStore
import com.dreamgirl.app.domain.ChatEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val engine: ChatEngine,
    db: AppDatabase,
    private val settings: SettingsStore,
) : ViewModel() {

    private val messageDao = db.messageDao()
    private val sessionDao = db.daySessionDao()

    private val _selectedDate = MutableStateFlow(DayClock.today())
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    val messages: StateFlow<List<MessageEntity>> = _selectedDate
        .flatMapLatest { day -> messageDao.observeByDate(day.toString()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 有对话的日子（倒序），聊天页顶部用来翻页。 */
    val sessionDates: StateFlow<List<String>> = sessionDao.observeAll()
        .map { list -> list.map { it.date } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var streamingRaw by mutableStateOf("")

    /** 气泡里显示的流式文字：把她正在写的 [心情:xx] 藏起来。 */
    val streamingText: String get() = com.dreamgirl.app.domain.MoodParser.stripForDisplay(streamingRaw)

    private var setStreaming by mutableStateOf(0) // 触发重组用


    var isSending by mutableStateOf(false)
        private set

    /** 正在引用哪一句（null = 没引用）。 */
    var quoting by mutableStateOf<MessageEntity?>(null)
        private set

    /** 多选模式：选中的消息 id。非空集合不一定代表"在多选模式"——见 selecting。 */
    var selectedIds by mutableStateOf<Set<Long>>(emptySet())
        private set

    /** 是否处于多选模式。长按"多选"进入，退出时清空选择。 */
    var selecting by mutableStateOf(false)
        private set

    /** 删除后可撤销的那一条（撤销条上的"撤销"按钮用）。 */
    var undoMessage by mutableStateOf<MessageEntity?>(null)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    /** 她这次回忆起了几件旧事——用于在 UI 上轻描淡写地提示一下。 */
    var memoryHint by mutableStateOf<String?>(null)
        private set

    var userAddress by mutableStateOf("你")
        private set

    /** 界面标题用的名字（可在人格页改）。 */
    var displayName by mutableStateOf("dream")
        private set

    /** 她的头像文件名（null = 用内置立绘）。 */
    var avatarFile by mutableStateOf<String?>(null)
        private set

    init {
        viewModelScope.launch {
            engine.ensureSession()
            settings.flow.collect {
                userAddress = it.userAddress
                displayName = it.displayName
                avatarFile = it.avatarFile
            }
        }
        viewModelScope.launch { _selectedDate.value = DayClock.today() }
    }

    fun selectDate(date: LocalDate) {
        _selectedDate.value = date
    }

    fun backToToday() = selectDate(DayClock.today())

    fun send(text: String) {
        val body = text.trim()
        if (body.isEmpty() || isSending) return
        val quote = quoting
        viewModelScope.launch {
            isSending = true
            streamingRaw = ""
            errorMessage = null
            memoryHint = null
            quoting = null
            // 跨天自动开新会话（方案 §6.1）
            engine.ensureSession()
            _selectedDate.value = DayClock.today()
            engine.send(body, quote).collect { ev -> handle(ev) }
            isSending = false
        }
    }

    // ---------------------------------------------------------- 长按菜单

    fun startQuote(m: MessageEntity) {
        quoting = m
        selecting = false
        selectedIds = emptySet()
    }

    fun cancelQuote() {
        quoting = null
    }

    fun startSelecting(m: MessageEntity) {
        selecting = true
        selectedIds = setOf(m.id)
        quoting = null
    }

    fun toggleSelected(id: Long) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    fun cancelSelecting() {
        selecting = false
        selectedIds = emptySet()
    }

    fun selectAll(ids: List<Long>) {
        selecting = true
        selectedIds = ids.toSet()
    }

    /** 删一条。留一份快照，可以撤销（误删聊天记录是很难受的事）。 */
    fun delete(m: MessageEntity) {
        viewModelScope.launch {
            engine.deleteMessage(m.id)
            undoMessage = m
        }
    }

    /** 批量删。撤销条只挂最后一条（够用，且不把内存撑大）。 */
    fun deleteSelected() {
        val ids = selectedIds.toList()
        if (ids.isEmpty()) return
        val snapshot = messages.value.filter { it.id in selectedIds }
        viewModelScope.launch {
            engine.deleteMessages(ids)
            undoMessage = snapshot.lastOrNull()
            cancelSelecting()
        }
    }

    /** 撤销刚才那次删除：把它重新写回去。 */
    fun undoDelete() {
        val m = undoMessage ?: return
        viewModelScope.launch {
            engine.restoreMessage(m)
            undoMessage = null
        }
    }

    fun dismissUndo() {
        undoMessage = null
    }

    /** 发送图片 / 视频 / 文件 / 表情包。 */
    fun sendMedia(kind: String, mediaPath: String, displayName: String) {
        if (isSending) return
        viewModelScope.launch {
            isSending = true
            streamingRaw = ""
            errorMessage = null
            memoryHint = null
            engine.ensureSession()
            _selectedDate.value = DayClock.today()
            engine.sendMedia(kind, mediaPath, displayName).collect { ev -> handle(ev) }
            isSending = false
        }
    }

    fun regenerate() {
        if (isSending) return
        viewModelScope.launch {
            isSending = true
            streamingRaw = ""
            errorMessage = null
            engine.regenerate().collect { ev -> handle(ev) }
            isSending = false
        }
    }

    fun dismissError() {
        errorMessage = null
    }

    private fun handle(ev: ChatEngine.ChatEvent) {
        when (ev) {
            is ChatEngine.ChatEvent.UserSaved -> streamingRaw = ""
            is ChatEngine.ChatEvent.Restarting -> streamingRaw = ""
            is ChatEngine.ChatEvent.Delta -> {
                streamingRaw += ev.text
                setStreaming++
            }
            is ChatEngine.ChatEvent.Finished -> {
                streamingRaw = ""
                if (ev.usedMemoryIds.isNotEmpty()) {
                    memoryHint = "（她想起了 ${ev.usedMemoryIds.size} 件你以前说过的事）"
                }
            }

            is ChatEngine.ChatEvent.Failed -> {
                streamingRaw = ""
                errorMessage = ev.error.message + hintFor(ev.error.kind)
            }

            // 她这回没开口 —— 她的内心独白会以灰字出现在聊天流里
            is ChatEngine.ChatEvent.Silent -> {
                streamingRaw = ""
            }
        }
    }

    private fun hintFor(kind: com.dreamgirl.app.llm.LlmErrorKind): String = when (kind) {
        com.dreamgirl.app.llm.LlmErrorKind.MISSING_KEY -> "，去「设置」里填一个 Key"
        com.dreamgirl.app.llm.LlmErrorKind.INVALID_KEY -> "，去「设置」里检查 Key"
        else -> ""
    }
}
