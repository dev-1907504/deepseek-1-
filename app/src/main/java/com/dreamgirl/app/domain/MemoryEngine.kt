package com.dreamgirl.app.domain

import com.dreamgirl.app.data.ApiKeyProvider
import com.dreamgirl.app.data.DaySummaryDao
import com.dreamgirl.app.data.MemoryDao
import com.dreamgirl.app.data.MessageDao
import com.dreamgirl.app.data.MonthDigestDao
import com.dreamgirl.app.data.DaySummaryEntity
import com.dreamgirl.app.data.MemoryItemEntity
import com.dreamgirl.app.data.MessageEntity
import com.dreamgirl.app.data.MonthDigestEntity
import com.dreamgirl.app.data.AppSettingsProvider
import com.dreamgirl.app.domain.JsonUtil.array
import com.dreamgirl.app.domain.JsonUtil.double
import com.dreamgirl.app.domain.JsonUtil.string
import com.dreamgirl.app.domain.JsonUtil.stringList
import com.dreamgirl.app.llm.ChatCompletionRequest
import com.dreamgirl.app.llm.ChatMessage
import com.dreamgirl.app.llm.LlmApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.exp
import kotlin.math.ln

/**
 * 记忆系统（方案 §6）：按天分会话 → 当日总结（L1/L2/L3）→ 条目化检索。
 *
 * 三条铁律：
 *  1. **一天只跑一次总结**，成本可控（§6.4）。
 *  2. **失败不影响主流程**，下次再试（§6.4-5）。
 *  3. 原始对话不外发，只有本地聚合/摘出来的东西进 prompt（§7-4）。
 */
class MemoryEngine(
    private val messageDao: MessageDao,
    private val summaryDao: DaySummaryDao,
    private val memoryDao: MemoryDao,
    private val monthDao: MonthDigestDao,
    private val client: LlmApi,
    private val settings: AppSettingsProvider,
    private val apiKeyStore: ApiKeyProvider,
) {

    // ---------------------------------------------------------------- 生成

    /**
     * 生成（或补生成）某天的三层总结，并把它拆成记忆条目。
     * 幂等：已经生成过且非用户手改的，直接返回旧的。
     */
    suspend fun summarizeDay(date: LocalDate, force: Boolean = false): Result<DaySummaryEntity> =
        withContext(Dispatchers.IO) {
            val key = date.toString()
            val existing = summaryDao.byDate(key)
            if (existing != null && !force && !existing.userEdited) return@withContext Result.success(existing)

            val messages = messageDao.byDate(key).filter { it.role == "user" || it.role == "assistant" }
            if (messages.isEmpty()) {
                return@withContext Result.failure(IllegalStateException("这天没有对话"))
            }
            if (!apiKeyStore.hasKey) {
                return@withContext Result.failure(IllegalStateException("还没配置 API Key"))
            }

            val cfg = settings.current()
            val baseUrl = cfg.baseUrl

            // map：分段小摘要。**只有真的需要分段时才跑** ——
            // 一天只聊了十几句还多花一次模型调用，是纯粹的浪费（方案 §6.4 成本控制）。
            val chunks = messages.chunked(40)
            val partials = ArrayList<String>(chunks.size)
            var promptTokens = 0
            var completionTokens = 0
            if (chunks.size == 1) {
                partials += renderTranscript(chunks.first())
            }
            for ((idx, chunk) in chunks.withIndex()) {
                if (chunks.size == 1) break // 上面的直通路径已经处理过了
                val transcript = renderTranscript(chunk)
                val req = ChatCompletionRequest(
                    model = cfg.chatModel,
                    messages = listOf(
                        ChatMessage.system(MAP_PROMPT),
                        ChatMessage.user("这是当天第 ${idx + 1}/${chunks.size} 段对话：\n\n$transcript"),
                    ),
                    stream = false,
                    temperature = cfg.utilityTemperature,
                    maxTokens = 500,
                )
                val r = client.complete(req, baseUrl)
                r.onSuccess {
                    partials += it.text.trim()
                    promptTokens += it.usage?.promptTokens ?: 0
                    completionTokens += it.usage?.completionTokens ?: 0
                }.onFailure { return@withContext Result.failure(it) }
            }

            // reduce：合成 L1/L2/L3 + 记忆条目
            val reduceReq = ChatCompletionRequest(
                model = cfg.chatModel,
                messages = listOf(
                    ChatMessage.system(REDUCE_PROMPT),
                    ChatMessage.user(
                        "日期：$key\n\n" + partials.mapIndexed { i, p -> "【片段${i + 1}】\n$p" }.joinToString("\n\n"),
                    ),
                ),
                stream = false,
                temperature = cfg.utilityTemperature,
                maxTokens = 900,
            )
            val reduced = client.complete(reduceReq, baseUrl).getOrElse { return@withContext Result.failure(it) }
            promptTokens += reduced.usage?.promptTokens ?: 0
            completionTokens += reduced.usage?.completionTokens ?: 0

            val obj = JsonUtil.extractJsonObject(reduced.text)
                ?: return@withContext Result.failure(IllegalStateException("总结格式无法解析"))

            val l1 = obj.string("l1").orEmpty().ifBlank { "这天没说什么正经事。" }
            val l2 = obj.stringList("l2")
            val l3 = obj.stringList("l3")
            val mood = obj.string("mood")
            val herMood = obj.string("her_mood")

            val entity = DaySummaryEntity(
                date = key,
                l1 = l1,
                l2Json = JsonUtil.stringArray(l2),
                l3Json = JsonUtil.stringArray(l3),
                mood = mood,
                herMood = herMood,
                generatedAt = System.currentTimeMillis(),
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                userEdited = existing?.userEdited ?: false,
            )
            val skipped = replaceMemories(key, obj)
            val saved = entity.copy(skippedCount = skipped)
            summaryDao.upsert(saved)
            Result.success(saved)
        }

    /**
     * 把 reduce 结果里的 memory_items 落库（先清掉这天的旧条目，保证幂等）。
     * @return 因为敏感而被跳过的条数（只记数量，不记内容）
     */
    private suspend fun replaceMemories(date: String, obj: JsonObject): Int {
        memoryDao.deleteByDate(date)
        val now = System.currentTimeMillis()
        val items = ArrayList<MemoryItemEntity>()
        var skipped = 0

        obj.array("memory_items").forEach { item ->
            val content = item.string("content")?.trim().orEmpty()
            if (content.isEmpty()) return@forEach
            if (SensitiveFilter.isSensitive(content)) {
                skipped++
                return@forEach
            }
            val tags = item.stringList("tags").joinToString(",")
            val weight = item.double("weight", 0.5).toFloat().coerceIn(0f, 1f)
            val layer = item.double("layer", 2.0).toInt().coerceIn(2, 3)
            items += MemoryItemEntity(
                sourceDate = date,
                content = content,
                tags = tags,
                weight = weight,
                layer = layer,
                createdAt = now,
            )
        }
        // L3 细节也补成条目（模型有时只在 l3 里给，不给 memory_items）
        obj.stringList("l3").forEach { detail ->
            if (detail.isBlank()) return@forEach
            if (SensitiveFilter.isSensitive(detail)) {
                skipped++
                return@forEach
            }
            if (items.any { it.content == detail }) return@forEach
            items += MemoryItemEntity(
                sourceDate = date,
                content = detail,
                tags = "",
                weight = 0.7f,
                layer = 3,
                createdAt = now,
            )
        }
        if (items.isNotEmpty()) memoryDao.insertAll(items)
        return skipped
    }

    /**
     * 把「有对话、但还没总结」的过去几天补上（方案 §6.4-4：次日首次打开）。
     *
     * 这是**她自己做的事**，不需要用户点任何按钮。返回成功了几条。
     * 只补最近的几天：更早的交给月记压缩，不必逐天重跑。
     */
    suspend fun catchUpMissingSummaries(today: LocalDate = LocalDate.now(), max: Int = 3): Int {
        if (!apiKeyStore.hasKey) return 0
        val missing = runCatching { summaryDao.datesMissingSummary(today.toString()) }.getOrElse { return 0 }
        var done = 0
        for (iso in missing.takeLast(max)) {
            val date = runCatching { LocalDate.parse(iso) }.getOrNull() ?: continue
            if (summarizeDay(date).isSuccess) done++
        }
        return done
    }

    // ---------------------------------------------------------------- 检索

    /**
     * 检索相关记忆（方案 §6.5）：关键词匹配 + 时间衰减，不命中就不硬塞。
     */
    suspend fun retrieve(query: String, limit: Int = 5, today: LocalDate = LocalDate.now()): List<MemoryItemEntity> =
        withContext(Dispatchers.IO) {
            val terms = Keywords.of(query)
            if (terms.isEmpty()) return@withContext emptyList()
            val pool = memoryDao.allForRetrieval()
            val scored = pool.mapNotNull { item ->
                val hay = item.content + " " + item.tags
                var hits = 0
                var tagHits = 0
                terms.forEach { t ->
                    if (hay.contains(t, ignoreCase = true)) {
                        hits++
                        if (item.tags.contains(t, ignoreCase = true)) tagHits++
                    }
                }
                if (hits == 0) return@mapNotNull null
                val days = runCatching {
                    ChronoUnit.DAYS.between(LocalDate.parse(item.sourceDate), today).toDouble()
                }.getOrDefault(0.0)
                val decay = exp(-days / 45.0)
                val score = (hits + tagHits * 1.5) * (0.4 + item.weight) * decay
                item to score
            }.sortedByDescending { it.second }

            val top = scored.take(limit).map { it.first }
            if (top.isNotEmpty()) {
                memoryDao.markReferenced(top.map { it.id }, System.currentTimeMillis())
            }
            top
        }

    /** 给 prompt 用的短句形式。 */
    fun renderForPrompt(items: List<MemoryItemEntity>, today: LocalDate = LocalDate.now()): List<String> =
        items.map { item ->
            val days = runCatching {
                ChronoUnit.DAYS.between(LocalDate.parse(item.sourceDate), today).toInt()
            }.getOrDefault(0)
            val whenStr = when {
                days <= 0 -> "今天"
                days == 1 -> "昨天"
                days <= 7 -> "$days 天前"
                days <= 30 -> "上个月"
                else -> "更早"
            }
            "（$whenStr）${item.content}"
        }

    // ---------------------------------------------------------------- 月记压缩

    /** 超过 [olderThanDays] 天的日总结合并成月记，控制体积（方案 §6.5）。 */
    suspend fun compressOldSummaries(olderThanDays: Long = 90, today: LocalDate = LocalDate.now()): Int =
        withContext(Dispatchers.IO) {
            val cutoff = today.minusDays(olderThanDays).toString()
            val old = summaryDao.olderThan(cutoff)
            if (old.isEmpty()) return@withContext 0
            val cfg = settings.current()
            val byMonth = old.groupBy { it.date.substring(0, 7) }
            var made = 0
            byMonth.forEach { (month, list) ->
                val body = list.joinToString("\n") { s ->
                    val l2 = JsonUtil.parseStringArray(s.l2Json).joinToString("；")
                    "${s.date}：${s.l1}${if (l2.isNotEmpty()) "｜$l2" else ""}"
                }
                val req = ChatCompletionRequest(
                    model = cfg.chatModel,
                    messages = listOf(
                        ChatMessage.system(MONTH_PROMPT),
                        ChatMessage.user(body),
                    ),
                    stream = false,
                    temperature = cfg.utilityTemperature,
                    maxTokens = 600,
                )
                client.complete(req, cfg.baseUrl).onSuccess { r ->
                    val obj = JsonUtil.extractJsonObject(r.text) ?: return@onSuccess
                    monthDao.upsert(
                        MonthDigestEntity(
                            month = month,
                            l1 = obj.string("l1").orEmpty(),
                            l2Json = JsonUtil.stringArray(obj.stringList("l2")),
                            generatedAt = System.currentTimeMillis(),
                            coveredDays = list.size,
                        ),
                    )
                    made++
                }
            }
            made
        }

    /**
     * 廉价的小抽取：只从最近几条消息里抓「他提过的细节」。
     *
     * 和日总结互补——日总结一天跑一次；这个让细节当天就能被记住（行为原则 1）。
     * 节流由调用方负责；这里失败一律静默返回 0，绝不影响主流程（§5 降级）。
     */
    suspend fun extractQuick(messages: List<MessageEntity>, date: LocalDate): Int {
        if (messages.size < 2 || !apiKeyStore.hasKey) return 0
        val cfg = settings.current()
        val req = ChatCompletionRequest(
            model = cfg.chatModel,
            messages = listOf(
                ChatMessage.system(QUICK_PROMPT),
                ChatMessage.user(renderTranscript(messages)),
            ),
            stream = false,
            temperature = cfg.utilityTemperature,
            maxTokens = 400,
        )
        val result = client.complete(req, cfg.baseUrl).getOrNull() ?: return 0
        val obj = JsonUtil.extractJsonObject(result.text) ?: return 0
        val key = date.toString()
        val existing = memoryDao.byDate(key).map { it.content }.toMutableSet()
        val now = System.currentTimeMillis()
        val fresh = ArrayList<MemoryItemEntity>()
        obj.array("items").forEach { item ->
            val content = item.string("content")?.trim().orEmpty()
            if (content.isEmpty()) return@forEach
            if (SensitiveFilter.isSensitive(content)) return@forEach
            if (content in existing) return@forEach
            existing += content
            fresh += MemoryItemEntity(
                sourceDate = key,
                content = content,
                tags = item.stringList("tags").joinToString(","),
                weight = item.double("weight", 0.6).toFloat().coerceIn(0f, 1f),
                layer = item.double("layer", 3.0).toInt().coerceIn(2, 3),
                createdAt = now,
            )
        }
        if (fresh.isNotEmpty()) memoryDao.insertAll(fresh)
        return fresh.size
    }

    // ---------------------------------------------------------------- 工具

    private fun renderTranscript(messages: List<MessageEntity>): String = messages.joinToString("\n") { m ->
        val who = if (m.role == "user") "他" else "我"
        "$who：${m.content}"
    }

    companion object {
        private val MAP_PROMPT = """
            你在为一段聊天记录做"小摘要"。只保留有信息量的东西，写 3–6 行短句。
            特别要留下：他的偏好和习惯、明确说过的计划或承诺、情绪事件、重大决定、我答应过的事。
            寒暄和表情包对轰压成一行，或者直接略过。
            遇到密码、银行卡、身份证号等敏感信息，跳过，不要写进摘要。
            直接输出摘要文字，不要 JSON，不要解释。
        """.trimIndent()

        private val REDUCE_PROMPT = """
            你在把一天的聊天小摘要合并成一条"当日总结"，并抽出可长期记住的条目。
            详略判定：
            - 详记：他的偏好和习惯、明确说过的计划或承诺、情绪事件（难过/焦虑/兴奋）、重大决定、我给的建议和后来的结果，以及**我答应过他的事**。
            - 略记：寒暄、表情包、重复的日常问候，压成一行甚至不记。
            - 不记：密码、银行卡、身份证号等敏感信息，直接跳过。
            - 没内容就空着：这天只聊了两句废话，l2 可以是空数组。不要为了填满而编。
            只输出 JSON，不要解释，不要 Markdown 代码块外的东西。格式：
            {
              "l1": "10-30字，这天的主线",
              "l2": ["3-6条要点，每条一句话"],
              "l3": ["0-5条值得被原样记住的话/偏好/承诺/情绪事件，可为空数组"],
              "mood": "一个词，他这天大概什么状态",
              "her_mood": "3-8个字，用她自己的口吻写她此刻的心情（比如「尾巴翘起来了」「有点蔫」）",
              "memory_items": [
                {"content": "一条可长期记住的事实", "tags": ["人名","事件","情绪"], "weight": 0.0-1.0, "layer": 2或3}
              ]
            }
        """.trimIndent()

        private val QUICK_PROMPT = """
            你从一个对话片段里，找出「他明确提过的、以后可能还用得上的细节」。
            只要这几类：偏好和习惯、明确说过的计划或承诺、情绪事件、重要的人名/地点/物件、需要长期记住的决定。
            寒暄、玩笑、表情包一律不要。密码、银行卡、身份证号一律不要。
            没有就返回空数组，不要硬凑。最多 4 条。
            只输出 JSON：{"items":[{"content":"一句话事实","tags":["标签"],"weight":0.0-1.0,"layer":3}]}
        """.trimIndent()

        private val MONTH_PROMPT = """
            你在把若干天的日总结压缩成一条"月记"。只保留长期有用的：他的习惯、长期计划、
            反复出现的情绪模式、已经发生的重要变化。琐碎的日常丢掉。
            只输出 JSON：{"l1":"一句话主线","l2":["要点，最多6条"]}
        """.trimIndent()
    }
}

/** 关键词切分：中文按 2-gram，英文/数字按词。检索用，不需要很聪明。 */
object Keywords {
    private val CJK = Regex("[\\u4e00-\\u9fff]")
    private val RUN = Regex("[A-Za-z0-9_]{2,}")
    private val STOP = setOf(
        "这个", "那个", "什么", "怎么", "我们", "你们", "他们", "自己", "就是", "可以",
        "但是", "因为", "所以", "如果", "现在", "今天", "昨天", "一个", "没有", "不是",
        "还是", "已经", "觉得", "知道", "可能", "应该", "时候", "问题", "事情",
    )

    fun of(text: String): List<String> {
        val out = LinkedHashSet<String>()
        RUN.findAll(text).forEach { out += it.value.lowercase() }
        val cjkRuns = Regex("[\\u4e00-\\u9fff]+").findAll(text).map { it.value }
        cjkRuns.forEach { run ->
            if (run.length <= 2) {
                if (run.length == 2 && run !in STOP) out += run
            } else {
                for (i in 0..run.length - 2) {
                    val g = run.substring(i, i + 2)
                    if (g !in STOP) out += g
                }
                if (run.length in 3..6) out += run
            }
        }
        return out.toList()
    }
}

/** 敏感信息过滤：识别到就跳过，并在 UI 上标注"已跳过"（方案 §6.3）。 */
object SensitiveFilter {

    /**
     * 关键词兜底：只要出现这些词，整条就不记。
     * 宁可漏记一句无关紧要的话，也不把密码写进数据库 —— 隐私默认保守（方案 §7-6）。
     */
    private val keywords = listOf(
        "密码", "口令", "password", "passwd", "身份证", "银行卡", "信用卡",
        "卡号", "验证码", "cvv", "私钥", "助记词", "seed phrase",
    )

    private val patterns = listOf(
        Regex("\\b\\d{16,19}\\b"),                       // 银行卡
        Regex("\\b\\d{17}[0-9Xx]\\b"),                   // 身份证
        Regex("(?i)\\b(password|passwd|密码|口令)\\s*[:：=]\\s*\\S+"),
        Regex("\\bsk-[A-Za-z0-9]{16,}\\b"),              // API Key
        Regex("\\b1[3-9]\\d{9}\\b"),                     // 手机号
    )

    fun isSensitive(text: String): Boolean =
        keywords.any { text.contains(it, ignoreCase = true) } || patterns.any { it.containsMatchIn(text) }
}
