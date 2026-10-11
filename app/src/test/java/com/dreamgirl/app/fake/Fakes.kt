package com.dreamgirl.app.fake

import com.dreamgirl.app.data.ApiKeyProvider
import com.dreamgirl.app.data.AppSettings
import com.dreamgirl.app.data.AppSettingsProvider
import com.dreamgirl.app.data.DaySessionDao
import com.dreamgirl.app.data.DaySessionEntity
import com.dreamgirl.app.data.DaySummaryDao
import com.dreamgirl.app.data.DaySummaryEntity
import com.dreamgirl.app.data.MemoryDao
import com.dreamgirl.app.data.MemoryItemEntity
import com.dreamgirl.app.data.MessageDao
import com.dreamgirl.app.data.MessageEntity
import com.dreamgirl.app.data.MonthDigestDao
import com.dreamgirl.app.data.MonthDigestEntity
import com.dreamgirl.app.llm.ChatCompletionRequest
import com.dreamgirl.app.llm.CompletionResult
import com.dreamgirl.app.llm.LlmApi
import com.dreamgirl.app.llm.LlmErrorKind
import com.dreamgirl.app.llm.LlmEvent
import com.dreamgirl.app.llm.LlmException
import com.dreamgirl.app.llm.TokenUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * 纯 JVM 的测试替身。它们让「发一句话 → 流式 → 后处理 → 落库 → 抽记忆」
 * 这条主链路在没有手机、没有网络的情况下也能被完整跑穿。
 */

class FakeSettings(
    var settings: AppSettings = AppSettings(),
) : AppSettingsProvider {
    var lastProactive: Long = 0

    /** 她写下的心情，测试可以直接断言。 */
    var lastLiveMood: String? = null
        private set

    override suspend fun current(): AppSettings = settings
    override suspend fun setLastProactiveAt(v: Long) { lastProactive = v }

    override suspend fun setLiveMood(v: String?) {
        lastLiveMood = v
        // 真实实现会把它写进 DataStore，下一轮 current() 就能读到 —— 替身也要这样，
        // 否则"她自己写的心情有没有真的影响下一句"这件事根本测不出来。
        settings = settings.copy(liveMood = v)
    }
}

class FakeKeys(override var apiKey: String? = "sk-test-1234567890") : ApiKeyProvider {
    override val hasKey: Boolean get() = !apiKey.isNullOrEmpty()
}

/** 可以按剧本演：先吐几段、然后成功或失败。 */
class FakeLlm : LlmApi {

    data class StreamPlan(
        val deltas: List<String> = listOf("默认回复。"),
        val failure: LlmException? = null,
        val usage: TokenUsage? = TokenUsage(10, 5, 15),
    )

    val plans = ArrayDeque<StreamPlan>()
    val streamRequests = mutableListOf<ChatCompletionRequest>()
    val completeRequests = mutableListOf<ChatCompletionRequest>()
    val completeResponses = ArrayDeque<String>()

    var defaultComplete = """{"items":[]}"""

    fun plan(vararg p: StreamPlan) { plans.addAll(p) }

    override fun streamChat(request: ChatCompletionRequest, baseUrl: String): Flow<LlmEvent> = flow {
        streamRequests += request
        val p = if (plans.isNotEmpty()) plans.removeFirst() else StreamPlan()
        p.deltas.forEach { emit(LlmEvent.Delta(it)) }
        val f = p.failure
        if (f != null) emit(LlmEvent.Failed(f)) else emit(LlmEvent.Completed(p.usage))
    }

    override suspend fun complete(
        request: ChatCompletionRequest,
        baseUrl: String,
    ): Result<CompletionResult> {
        completeRequests += request
        val text = if (completeResponses.isNotEmpty()) completeResponses.removeFirst() else defaultComplete
        return Result.success(CompletionResult(text, TokenUsage(20, 10, 30)))
    }

    override suspend fun ping(baseUrl: String, model: String): Result<Unit> = Result.success(Unit)
}

/** 感知层的替身：想让它"看见"什么就设什么。 */
class FakeSense(
    var permitted: Boolean = true,
    var snap: com.dreamgirl.app.sense.SenseSnapshot = com.dreamgirl.app.sense.SenseSnapshot(),
) : com.dreamgirl.app.sense.SenseProvider {
    override fun hasPermission(): Boolean = permitted
    override fun snapshot(now: Long): com.dreamgirl.app.sense.SenseSnapshot = snap
    override fun lastScreenOffAt(now: Long): Long? = null
}

class FakeMessageDao : MessageDao {
    private val items = MutableStateFlow<List<MessageEntity>>(emptyList())
    private var nextId = 1L

    override suspend fun insert(message: MessageEntity): Long {
        val id = if (message.id != 0L) message.id else nextId++
        items.value = items.value + message.copy(id = id)
        return id
    }

    override suspend fun update(message: MessageEntity) {
        items.value = items.value.map { if (it.id == message.id) message else it }
    }

    override fun observeByDate(date: String): Flow<List<MessageEntity>> =
        items.map { list -> list.filter { it.sessionDate == date }.sortedWith(compareBy({ it.createdAt }, { it.id })) }

    override suspend fun byDate(date: String): List<MessageEntity> =
        items.value.filter { it.sessionDate == date }.sortedWith(compareBy({ it.createdAt }, { it.id }))

    override suspend fun latestByDate(date: String, limit: Int): List<MessageEntity> =
        byDate(date).takeLast(limit).reversed()

    override suspend fun lastMessage(): MessageEntity? =
        items.value.maxWithOrNull(compareBy({ it.createdAt }, { it.id }))

    override suspend fun between(from: Long, to: Long): List<MessageEntity> =
        items.value.filter { it.createdAt in from..to }.sortedBy { it.createdAt }

    override suspend fun deleteById(id: Long) { items.value = items.value.filterNot { it.id == id } }

    override suspend fun deleteByIds(ids: List<Long>) {
        items.value = items.value.filterNot { it.id in ids }
    }

    override suspend fun byId(id: Long): MessageEntity? = items.value.firstOrNull { it.id == id }

    override suspend fun deleteByDate(date: String) { items.value = items.value.filterNot { it.sessionDate == date } }

    override suspend fun countByDate(date: String): Int = items.value.count { it.sessionDate == date }

    override fun observeSessionPreviews(): Flow<List<com.dreamgirl.app.data.SessionPreview>> =
        items.map { list ->
            list.groupBy { it.sessionDate }.mapNotNull { (date, msgs) ->
                val last = msgs.maxWithOrNull(compareBy({ it.createdAt }, { it.id })) ?: return@mapNotNull null
                com.dreamgirl.app.data.SessionPreview(
                    sessionDate = date,
                    content = last.content,
                    role = last.role,
                    createdAt = last.createdAt,
                    messageCount = msgs.size,
                    proactive = last.proactive,
                )
            }.sortedByDescending { it.createdAt }
        }

    override fun observeTotalCount(): Flow<Int> = items.map { it.size }

    fun all(): List<MessageEntity> = items.value
}

class FakeSessionDao : DaySessionDao {
    private val map = linkedMapOf<String, DaySessionEntity>()
    override suspend fun upsert(session: DaySessionEntity) { map[session.date] = session }
    override suspend fun byDate(date: String): DaySessionEntity? = map[date]
    override fun observeAll(): Flow<List<DaySessionEntity>> =
        MutableStateFlow(map.values.sortedByDescending { it.date })
    override suspend fun all(): List<DaySessionEntity> = map.values.sortedByDescending { it.date }
    override suspend fun latest(): DaySessionEntity? = all().firstOrNull()
}

class FakeSummaryDao : DaySummaryDao {
    private val map = linkedMapOf<String, DaySummaryEntity>()
    private val messagesForMissing = linkedSetOf<String>()

    fun declareMessagesOn(date: String) { messagesForMissing += date }

    override suspend fun upsert(summary: DaySummaryEntity) { map[summary.date] = summary }
    override suspend fun byDate(date: String): DaySummaryEntity? = map[date]
    override fun observeByDate(date: String): Flow<DaySummaryEntity?> = MutableStateFlow(map[date])
    override fun observeAll(): Flow<List<DaySummaryEntity>> =
        MutableStateFlow(map.values.sortedByDescending { it.date })

    override suspend fun all(): List<DaySummaryEntity> = map.values.sortedByDescending { it.date }
    override suspend fun olderThan(before: String): List<DaySummaryEntity> =
        map.values.filter { it.date < before }.sortedBy { it.date }

    override suspend fun datesMissingSummary(today: String): List<String> =
        messagesForMissing.filter { it < today && it !in map }.sorted()

    override suspend fun deleteByDate(date: String) { map.remove(date) }
}

class FakeMemoryDao : MemoryDao {
    private val items = mutableListOf<MemoryItemEntity>()
    private var nextId = 1L
    override suspend fun insert(item: MemoryItemEntity): Long {
        val id = nextId++
        items += item.copy(id = id); return id
    }

    override suspend fun insertAll(items: List<MemoryItemEntity>) {
        items.forEach { insert(it) }
    }

    override suspend fun update(item: MemoryItemEntity) {
        val i = items.indexOfFirst { it.id == item.id }
        if (i >= 0) items[i] = item
    }

    override fun observeAll(): Flow<List<MemoryItemEntity>> = MutableStateFlow(items.toList())
    override suspend fun all(): List<MemoryItemEntity> = items.toList()
    override suspend fun byDate(date: String): List<MemoryItemEntity> = items.filter { it.sourceDate == date }
    override suspend fun byId(id: Long): MemoryItemEntity? = items.firstOrNull { it.id == id }
    override suspend fun allForRetrieval(): List<MemoryItemEntity> =
        items.sortedWith(compareBy({ it.lastReferencedAt }, { it.weight }))

    override suspend fun deleteById(id: Long) { items.removeAll { it.id == id } }
    override suspend fun deleteByDate(date: String) { items.removeAll { it.sourceDate == date } }
    override suspend fun markReferenced(ids: List<Long>, at: Long) {
        ids.forEach { id ->
            val i = items.indexOfFirst { it.id == id }
            if (i >= 0) items[i] = items[i].copy(lastReferencedAt = at)
        }
    }

    override fun observeCount(): Flow<Int> = MutableStateFlow(items.size)
    override suspend fun countExact(content: String, date: String): Int =
        items.count { it.content == content && it.sourceDate == date }

    fun snapshot(): List<MemoryItemEntity> = items.toList()
}

class FakeMonthDao : MonthDigestDao {
    private val map = linkedMapOf<String, MonthDigestEntity>()
    override suspend fun upsert(digest: MonthDigestEntity) { map[digest.month] = digest }
    override suspend fun all(): List<MonthDigestEntity> = map.values.sortedByDescending { it.month }
    override fun observeAll(): Flow<List<MonthDigestEntity>> =
        MutableStateFlow(map.values.sortedByDescending { it.month })
}

fun retryableError() = LlmException(LlmErrorKind.NETWORK, "网络断了")
fun fatalError() = LlmException(LlmErrorKind.INVALID_KEY, "API Key 无效或已失效")
