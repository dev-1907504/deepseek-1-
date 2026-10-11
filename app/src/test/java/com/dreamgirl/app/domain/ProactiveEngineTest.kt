package com.dreamgirl.app.domain

import com.dreamgirl.app.data.AppSettings
import com.dreamgirl.app.fake.FakeKeys
import com.dreamgirl.app.fake.FakeLlm
import com.dreamgirl.app.fake.FakeMemoryDao
import com.dreamgirl.app.fake.FakeMessageDao
import com.dreamgirl.app.fake.FakeMonthDao
import com.dreamgirl.app.fake.FakeSense
import com.dreamgirl.app.fake.FakeSessionDao
import com.dreamgirl.app.fake.FakeSettings
import com.dreamgirl.app.fake.FakeSummaryDao
import com.dreamgirl.app.sense.SenseSnapshot
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 验收标准第 5 条：「熬夜场景下，她能主动发来消息」。
 *
 * 这里验证的是**该不该开口**这一层的全部闸门：开关、Key、权限、冷却、刚聊完别插嘴。
 */
class ProactiveEngineTest {

    private lateinit var messages: FakeMessageDao
    private lateinit var llm: FakeLlm
    private lateinit var settings: FakeSettings
    private lateinit var keys: FakeKeys
    private lateinit var sense: FakeSense
    private lateinit var chat: ChatEngine
    private lateinit var engine: ProactiveEngine

    private val zone: ZoneId = ZoneId.systemDefault()

    private fun at(hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 10, 11, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        messages = FakeMessageDao()
        llm = FakeLlm()
        settings = FakeSettings(AppSettings(proactiveEnabled = true, usageStatsEnabled = true))
        keys = FakeKeys()
        sense = FakeSense()
        val memory = MemoryEngine(
            messageDao = messages,
            summaryDao = FakeSummaryDao(),
            memoryDao = FakeMemoryDao(),
            monthDao = FakeMonthDao(),
            client = llm,
            settings = settings,
            apiKeyStore = keys,
        )
        chat = ChatEngine(
            messageDao = messages,
            sessionDao = FakeSessionDao(),
            summaryDao = FakeSummaryDao(),
            client = llm,
            settings = settings,
            apiKeyStore = keys,
            memoryEngine = memory,
        )
        engine = ProactiveEngine(
            messageDao = messages,
            settings = settings,
            apiKeys = keys,
            sense = sense,
            chat = chat,
        )
    }

    @Test
    fun `stays quiet when the switch is off`() = runTest {
        settings.settings = settings.settings.copy(proactiveEnabled = false)
        sense.snap = SenseSnapshot(screenOnMinutesToday = 300, continuousUseMinutes = 200)
        val out = engine.checkOnce(at(2))
        assertTrue(out is ProactiveEngine.Outcome.SkippedReason)
        assertTrue(llm.streamRequests.isEmpty())
    }

    @Test
    fun `stays quiet without an api key`() = runTest {
        keys.apiKey = null
        sense.snap = SenseSnapshot(continuousUseMinutes = 200)
        val out = engine.checkOnce(at(2))
        assertTrue(out is ProactiveEngine.Outcome.SkippedReason)
        assertTrue(llm.streamRequests.isEmpty())
    }

    @Test
    fun `stays quiet when nothing is unusual`() = runTest {
        sense.snap = SenseSnapshot(screenOnMinutesToday = 80, continuousUseMinutes = 10)
        val out = engine.checkOnce(at(15))
        assertTrue(out is ProactiveEngine.Outcome.Skipped)
        assertTrue(llm.streamRequests.isEmpty())
    }

    @Test
    fun `speaks up at 2am because he should be asleep`() = runTest {
        llm.plan(FakeLlm.StreamPlan(deltas = listOf("（戳你）这个点了还不睡？")))
        sense.snap = SenseSnapshot(screenOnMinutesToday = 240, continuousUseMinutes = 5)

        val out = engine.checkOnce(at(2, 40))

        assertTrue("深夜应当开口", out is ProactiveEngine.Outcome.Spoke)
        val spoke = out as ProactiveEngine.Outcome.Spoke
        assertEquals(TriggerEngine.Reason.DEEP_NIGHT, spoke.reason)
        assertEquals("（戳你）这个点了还不睡？", spoke.text)
    }

    @Test
    fun `speaks up after a long unbroken session`() = runTest {
        llm.plan(FakeLlm.StreamPlan(deltas = listOf("（尾巴甩过来）起来走两步。")))
        sense.snap = SenseSnapshot(
            screenOnMinutesToday = 400,
            continuousUseMinutes = 120,
            foregroundPackage = "com.tencent.mm",
            foregroundLabel = "微信",
        )
        val out = engine.checkOnce(at(15))
        assertTrue(out is ProactiveEngine.Outcome.Spoke)
        assertEquals(TriggerEngine.Reason.LONG_SCREEN, (out as ProactiveEngine.Outcome.Spoke).reason)
    }

    @Test
    fun `does not interrupt right after they just talked`() = runTest {
        // 5 分钟前刚聊过
        val now = at(15)
        messages.insert(
            com.dreamgirl.app.data.MessageEntity(
                sessionDate = LocalDate.of(2026, 10, 11).toString(),
                role = "user",
                content = "刚才聊过了",
                createdAt = now - 5 * 60_000L,
            ),
        )
        sense.snap = SenseSnapshot(continuousUseMinutes = 200)

        val out = engine.checkOnce(now)
        assertTrue("刚聊完就该闭嘴", out is ProactiveEngine.Outcome.SkippedReason)
        assertTrue(llm.streamRequests.isEmpty())
    }

    @Test
    fun `deep night is allowed to interrupt even right after a chat`() = runTest {
        val now = at(2, 30)
        messages.insert(
            com.dreamgirl.app.data.MessageEntity(
                sessionDate = LocalDate.of(2026, 10, 10).toString(),
                role = "user",
                content = "刚说了句话",
                createdAt = now - 5 * 60_000L,
            ),
        )
        llm.plan(FakeLlm.StreamPlan(deltas = listOf("（叹气）还没睡啊。")))
        sense.snap = SenseSnapshot(screenOnMinutesToday = 300)

        val out = engine.checkOnce(now)
        assertTrue("深夜是例外，可以说", out is ProactiveEngine.Outcome.Spoke)
    }

    @Test
    fun `the fire time is recorded so the cooldown applies next time`() = runTest {
        llm.plan(FakeLlm.StreamPlan(deltas = listOf("（戳你）睡吧。")))
        sense.snap = SenseSnapshot(screenOnMinutesToday = 300)
        engine.checkOnce(at(2, 40))
        assertEquals(at(2, 40), settings.lastProactive)
    }

    @Test
    fun `cooldown blocks a second message a few minutes later`() = runTest {
        settings.settings = settings.settings.copy(lastProactiveAt = at(2, 30))
        llm.plan(FakeLlm.StreamPlan(deltas = listOf("（戳你）睡吧。")))
        sense.snap = SenseSnapshot(screenOnMinutesToday = 300)

        val out = engine.checkOnce(at(2, 40)) // 才过 10 分钟
        assertFalse(out is ProactiveEngine.Outcome.Spoke)
    }

    @Test
    fun `a model failure is reported instead of crashing`() = runTest {
        llm.plan(FakeLlm.StreamPlan(deltas = emptyList(), failure = com.dreamgirl.app.fake.fatalError()))
        sense.snap = SenseSnapshot(screenOnMinutesToday = 300)
        val out = engine.checkOnce(at(2, 40))
        assertTrue(out is ProactiveEngine.Outcome.Failed)
    }

    @Test
    fun `proactive messages are stored in the day session so they show in chat`() = runTest {
        llm.plan(FakeLlm.StreamPlan(deltas = listOf("（戳你）睡吧。")))
        sense.snap = SenseSnapshot(screenOnMinutesToday = 300)
        engine.checkOnce(at(2, 40))

        // 实际落库用的是 DayClock.today()，不是测试里那个虚构时刻
        val stored = messages.byDate(com.dreamgirl.app.data.DayClock.today().toString())
        assertTrue(stored.isNotEmpty())
        assertTrue(stored.last().proactive)
    }
}
