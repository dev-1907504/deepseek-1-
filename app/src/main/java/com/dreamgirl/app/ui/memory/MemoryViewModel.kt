package com.dreamgirl.app.ui.memory

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreamgirl.app.data.AppDatabase
import com.dreamgirl.app.data.DayClock
import com.dreamgirl.app.data.DaySummaryEntity
import com.dreamgirl.app.data.MemoryItemEntity
import com.dreamgirl.app.domain.MemoryEngine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class MemoryViewModel(
    db: AppDatabase,
    private val engine: MemoryEngine,
) : ViewModel() {

    private val summaryDao = db.daySummaryDao()
    private val memoryDao = db.memoryDao()

    val summaries: StateFlow<List<DaySummaryEntity>> = summaryDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val memories: StateFlow<List<MemoryItemEntity>> = memoryDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var busy by mutableStateOf(false)
        private set

    var status by mutableStateOf<String?>(null)
        private set

    fun summarizeDate(date: LocalDate = DayClock.today(), force: Boolean = true) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            status = "正在整理 ${DayClock.label(date)} 的对话…"
            val r = engine.summarizeDay(date, force)
            status = r.fold(
                onSuccess = { "整理完了：${it.l1}" },
                onFailure = { "没整理成：${it.message}" },
            )
            busy = false
        }
    }

    fun compressOld() {
        if (busy) return
        viewModelScope.launch {
            busy = true
            status = "正在把 90 天以前的日总结压成月记…"
            val n = runCatching { engine.compressOldSummaries() }.getOrDefault(0)
            status = if (n > 0) "压缩完成，生成/更新了 $n 条月记" else "还没有需要压缩的旧总结"
            busy = false
        }
    }

    /** 记忆条目可改（方案 §3 v1「记忆条目可看 / 可改 / 可删」）。 */
    fun updateMemory(item: MemoryItemEntity, newContent: String) {
        val text = newContent.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            memoryDao.update(item.copy(content = text, weight = item.weight))
            status = "改好了"
        }
    }

    fun deleteMemory(item: MemoryItemEntity) {
        viewModelScope.launch {
            memoryDao.deleteById(item.id)
            status = "删掉了这条记忆"
        }
    }

    fun deleteDay(date: String) {
        viewModelScope.launch {
            summaryDao.deleteByDate(date)
            memoryDao.deleteByDate(date)
            status = "删掉了 $date 的总结和它的记忆条目"
        }
    }

    fun clearStatus() {
        status = null
    }
}
