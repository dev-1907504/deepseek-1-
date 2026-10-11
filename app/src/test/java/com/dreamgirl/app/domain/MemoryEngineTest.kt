package com.dreamgirl.app.domain

import com.dreamgirl.app.data.MemoryItemEntity
import com.dreamgirl.app.data.MessageEntity
import com.dreamgirl.app.fake.FakeKeys
import com.dreamgirl.app.fake.FakeLlm
import com.dreamgirl.app.fake.FakeMemoryDao
import com.dreamgirl.app.fake.FakeMessageDao
import com.dreamgirl.app.fake.FakeMonthDao
import com.dreamgirl.app.fake.FakeSettings
import com.dreamgirl.app.fake.FakeSummaryDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class MemoryEngineTest {

    private lateinit var messages: FakeMessageDao
    private lateinit var summaries: FakeSummaryDao
    private lateinit var memoryDao: FakeMemoryDao
    private lateinit var llm: FakeLlm
    private lateinit var keys: FakeKeys
    private lateinit var engine: MemoryEngine

    private val day: LocalDate = LocalDate.of(2026, 10, 10)

    @Before
    fun setUp() {
        messages = FakeMessageDao()
        summaries = FakeSummaryDao()
        memoryDao = FakeMemoryDao()
        llm = FakeLlm()
        keys = FakeKeys()
        engine = MemoryEngine(
            messageDao = messages,
            summaryDao = summaries,
            memoryDao = memoryDao,
            monthDao = FakeMonthDao(),
            client = llm,
            settings = FakeSettings(),
            apiKeyStore = keys,
        )
    }

    private suspend fun seedConversation() {
        messages.insert(MessageEntity(sessionDate = day.toString(), role = "user", content = "我想开始跑步", createdAt = 1))
        messages.insert(MessageEntity(sessionDate = day.toString(), role = "assistant", content = "（眼睛亮了）几点跑？", createdAt = 2))
    }

    @Test
    fun `summarizeDay stores the three layers from the model output`() = runTest {
        seedConversation()
        llm.completeResponses.add(
            """
            {
              "l1": "聊了跑步计划",
              "l2": ["他打算这周开始跑步", "他担心坚持不下来"],
              "l3": ["「难过归难过，事情还没结束」"],
              "mood": "有点心虚",
              "memory_items": [
                {"content": "他打算这周开始跑步", "tags": ["跑步","计划"], "weight": 0.8, "layer": 2}
              ]
            }
            """.trimIndent(),
        )

        val summary = engine.summarizeDay(day).getOrThrow()
        assertEquals("聊了跑步计划", summary.l1)
        assertEquals(listOf("他打算这周开始跑步", "他担心坚持不下来"), JsonUtil.parseStringArray(summary.l2Json))
        assertEquals(listOf("「难过归难过，事情还没结束」"), JsonUtil.parseStringArray(summary.l3Json))
        assertEquals("有点心虚", summary.mood)

        // memory_items 给了一条；L3 的细节按方案也会补成条目 —— 两条都该在
        val contents = memoryDao.snapshot().map { it.content }.toSet()
        assertEquals(2, contents.size)
        assertTrue(contents.contains("他打算这周开始跑步"))
        assertTrue(contents.contains("「难过归难过，事情还没结束」"))
    }

    @Test
    fun `l3 details become memory items even when the model forgets to list them`() = runTest {
        seedConversation()
        llm.completeResponses.add(
            """{"l1":"x","l2":[],"l3":["他讨厌香菜","他养了一只猫"],"memory_items":[]}""",
        )
        engine.summarizeDay(day).getOrThrow()
        val contents = memoryDao.snapshot().map { it.content }.toSet()
        assertTrue(contents.contains("他讨厌香菜"))
        assertTrue(contents.contains("他养了一只猫"))
    }

    @Test
    fun `sensitive details are never written to memory`() = runTest {
        seedConversation()
        llm.completeResponses.add(
            """
            {"l1":"x","l2":[],"l3":["他的卡号是 6222021234567890123"],
             "memory_items":[{"content":"身份证 110101199003072316","tags":[],"weight":0.9,"layer":3}]}
            """.trimIndent(),
        )
        val summary = engine.summarizeDay(day).getOrThrow()
        val contents = memoryDao.snapshot().map { it.content }
        assertTrue("银行卡号不该入库", contents.none { it.contains("6222021234567890123") })
        assertTrue("身份证号不该入库", contents.none { it.contains("110101199003072316") })

        // 方案 §6.3：跳过要在 UI 上标注出来 —— 但只标数量，不标内容
        assertEquals("应该记下跳过了 2 条", 2, summary.skippedCount)
    }

    @Test
    fun `a clean day reports zero skipped items`() = runTest {
        seedConversation()
        llm.completeResponses.add(
            """{"l1":"x","l2":[],"l3":["他喜欢跑步"],"memory_items":[{"content":"他在学吉他","tags":[],"weight":0.6,"layer":3}]}""",
        )
        val summary = engine.summarizeDay(day).getOrThrow()
        assertEquals(0, summary.skippedCount)
        assertEquals(2, memoryDao.snapshot().size)
    }

    @Test
    fun `summarizing a day with no conversation fails gracefully`() = runTest {
        val r = engine.summarizeDay(day)
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()?.message?.contains("没有对话") == true)
    }

    @Test
    fun `summarizing without an api key fails gracefully`() = runTest {
        seedConversation()
        keys.apiKey = null
        val r = engine.summarizeDay(day)
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()?.message?.contains("API Key") == true)
    }

    @Test
    fun `an unparseable model response does not blow up`() = runTest {
        seedConversation()
        llm.completeResponses.add("我觉得今天挺好的，没什么可总结的。")
        val r = engine.summarizeDay(day)
        assertTrue(r.isFailure)
        assertTrue(memoryDao.snapshot().isEmpty())
    }

    @Test
    fun `summarizeDay is idempotent and never runs the model twice for the same day`() = runTest {
        seedConversation()
        llm.completeResponses.add("""{"l1":"第一次","l2":[],"l3":[],"memory_items":[]}""")
        llm.completeResponses.add("""{"l1":"第二次","l2":[],"l3":[],"memory_items":[]}""")

        val first = engine.summarizeDay(day).getOrThrow()
        val second = engine.summarizeDay(day).getOrThrow()
        assertEquals("第一次", first.l1)
        assertEquals("已经有了就不该再花钱跑一遍", "第一次", second.l1)
    }

    @Test
    fun `force regenerates`() = runTest {
        seedConversation()
        llm.completeResponses.add("""{"l1":"第一次","l2":[],"l3":[],"memory_items":[]}""")
        llm.completeResponses.add("""{"l1":"第二次","l2":[],"l3":[],"memory_items":[]}""")
        engine.summarizeDay(day).getOrThrow()
        val again = engine.summarizeDay(day, force = true).getOrThrow()
        assertEquals("第二次", again.l1)
    }


    // ---------------------------------------------------------------- 跨天自动总结

    @Test
    fun `a new day summarizes the previous day, never today`() = runTest {
        val today = LocalDate.of(2026, 10, 11)
        val yesterday = today.minusDays(1)

        messages.insert(MessageEntity(sessionDate = yesterday.toString(), role = "user", content = "昨天聊了跑步", createdAt = 1))
        messages.insert(MessageEntity(sessionDate = yesterday.toString(), role = "assistant", content = "（点头）", createdAt = 2))
        messages.insert(MessageEntity(sessionDate = today.toString(), role = "user", content = "今天才刚开始", createdAt = 3))
        summaries.declareMessagesOn(yesterday.toString())
        summaries.declareMessagesOn(today.toString())

        llm.completeResponses.add("""{"l1":"昨天的主线","l2":["他开始跑步"],"l3":[],"memory_items":[]}""")

        val done = engine.catchUpMissingSummaries(today)

        assertEquals("只该补前一天那一条", 1, done)
        assertEquals("昨天的主线", summaries.byDate(yesterday.toString())?.l1)
        assertNull("今天还没过完，不该被总结", summaries.byDate(today.toString()))
    }

    @Test
    fun `catch up does nothing without an api key`() = runTest {
        keys.apiKey = null
        val today = LocalDate.of(2026, 10, 11)
        messages.insert(MessageEntity(sessionDate = "2026-10-10", role = "user", content = "x", createdAt = 1))
        summaries.declareMessagesOn("2026-10-10")
        assertEquals(0, engine.catchUpMissingSummaries(today))
        assertTrue(llm.completeRequests.isEmpty())
    }

    @Test
    fun `catch up is a no-op when everything is already summarized`() = runTest {
        val today = LocalDate.of(2026, 10, 11)
        summaries.declareMessagesOn("2026-10-10")
        summaries.upsert(
            com.dreamgirl.app.data.DaySummaryEntity(
                date = "2026-10-10", l1 = "已经总结过",
                l2Json = "[]", l3Json = "[]", generatedAt = 1,
            ),
        )
        assertEquals(0, engine.catchUpMissingSummaries(today))
        assertTrue(llm.completeRequests.isEmpty())
    }

    // ---------------------------------------------------------------- 检索

    private suspend fun seedMemory(content: String, tags: String, daysAgo: Long, weight: Float = 0.6f) {
        memoryDao.insert(
            MemoryItemEntity(
                sourceDate = LocalDate.of(2026, 10, 10).minusDays(daysAgo).toString(),
                content = content,
                tags = tags,
                weight = weight,
                layer = 3,
                createdAt = 1,
            ),
        )
    }

    @Test
    fun `retrieval matches on content`() = runTest {
        seedMemory("他喜欢喝美式，不加糖", "咖啡", 1)
        seedMemory("他养了一只叫年糕的猫", "宠物", 1)
        val hits = engine.retrieve("今天喝了杯咖啡", today = LocalDate.of(2026, 10, 10))
        assertEquals(1, hits.size)
        assertTrue(hits.single().content.contains("美式"))
    }

    @Test
    fun `retrieval matches on tags`() = runTest {
        seedMemory("某个说不清的东西", "年糕,宠物", 5)
        val hits = engine.retrieve("年糕怎么样了", today = LocalDate.of(2026, 10, 10))
        assertEquals(1, hits.size)
    }

    @Test
    fun `unrelated query retrieves nothing rather than everything`() = runTest {
        seedMemory("他喜欢喝美式", "咖啡", 1)
        val hits = engine.retrieve("明天会下雨吗", today = LocalDate.of(2026, 10, 10))
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `a recent memory outranks an equally matching old one`() = runTest {
        seedMemory("他喜欢跑步", "运动", 1, weight = 0.5f)
        seedMemory("他喜欢跑步这件事说过很多次", "运动", 200, weight = 0.5f)
        val hits = engine.retrieve("跑步", limit = 2, today = LocalDate.of(2026, 10, 10))
        assertEquals(2, hits.size)
        assertTrue("越近的应该排越前", hits.first().sourceDate > hits.last().sourceDate)
    }

    @Test
    fun `referenced memories are stamped so they can decay`() = runTest {
        seedMemory("他喜欢喝美式", "咖啡", 1)
        engine.retrieve("咖啡", today = LocalDate.of(2026, 10, 10))
        assertTrue(memoryDao.snapshot().single().lastReferencedAt > 0)
    }

    @Test
    fun `renderForPrompt uses relative time labels`() {
        val today = LocalDate.of(2026, 10, 10)
        val items = listOf(
            MemoryItemEntity(sourceDate = today.toString(), content = "A", createdAt = 1),
            MemoryItemEntity(sourceDate = today.minusDays(1).toString(), content = "B", createdAt = 1),
            MemoryItemEntity(sourceDate = today.minusDays(3).toString(), content = "C", createdAt = 1),
        )
        val lines = engine.renderForPrompt(items, today)
        assertTrue(lines[0].startsWith("（今天）"))
        assertTrue(lines[1].startsWith("（昨天）"))
        assertTrue(lines[2].startsWith("（3 天前）"))
    }

    // ---------------------------------------------------------------- 快速抽取

    @Test
    fun `extractQuick stores details and skips duplicates`() = runTest {
        val convo = listOf(
            MessageEntity(sessionDate = day.toString(), role = "user", content = "我在学吉他", createdAt = 1),
            MessageEntity(sessionDate = day.toString(), role = "assistant", content = "（眼睛亮了）学了多久？", createdAt = 2),
        )
        llm.completeResponses.add(
            """{"items":[{"content":"他在学吉他","tags":["吉他"],"weight":0.7,"layer":3}]}""",
        )
        assertEquals(1, engine.extractQuick(convo, day))

        // 再抽一次，同样内容不该重复入库
        llm.completeResponses.add(
            """{"items":[{"content":"他在学吉他","tags":["吉他"],"weight":0.7,"layer":3}]}""",
        )
        assertEquals(0, engine.extractQuick(convo, day))
        assertEquals(1, memoryDao.snapshot().size)
    }

    @Test
    fun `extractQuick filters sensitive items`() = runTest {
        val convo = listOf(
            MessageEntity(sessionDate = day.toString(), role = "user", content = "我的密码是 hunter2xyz", createdAt = 1),
            MessageEntity(sessionDate = day.toString(), role = "assistant", content = "（捂耳朵）我不听我不听", createdAt = 2),
        )
        llm.completeResponses.add(
            """{"items":[{"content":"他的密码是 hunter2xyz","tags":[],"weight":0.9,"layer":3}]}""",
        )
        assertEquals(0, engine.extractQuick(convo, day))
        assertTrue(memoryDao.snapshot().isEmpty())
    }

    @Test
    fun `extractQuick does nothing without an api key`() = runTest {
        keys.apiKey = null
        val convo = listOf(
            MessageEntity(sessionDate = day.toString(), role = "user", content = "a", createdAt = 1),
            MessageEntity(sessionDate = day.toString(), role = "assistant", content = "b", createdAt = 2),
        )
        assertEquals(0, engine.extractQuick(convo, day))
        assertTrue(llm.completeRequests.isEmpty())
    }

    @Test
    fun `memory for one day can be deleted without touching another`() = runTest {
        seedMemory("A", "", 0)
        seedMemory("B", "", 0)
        memoryDao.deleteByDate(LocalDate.of(2026, 10, 10).toString())
        assertTrue(memoryDao.snapshot().isEmpty())
        assertFalse(engine.renderForPrompt(memoryDao.snapshot()).isNotEmpty())
    }
}
