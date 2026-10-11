package com.dreamgirl.app.domain

import com.dreamgirl.app.data.DayClock
import com.dreamgirl.app.data.MemoryItemEntity
import com.dreamgirl.app.data.MessageEntity
import com.dreamgirl.app.fake.FakeKeys
import com.dreamgirl.app.fake.FakeLlm
import com.dreamgirl.app.fake.FakeMemoryDao
import com.dreamgirl.app.fake.FakeMessageDao
import com.dreamgirl.app.fake.FakeMonthDao
import com.dreamgirl.app.fake.FakeSessionDao
import com.dreamgirl.app.fake.FakeSettings
import com.dreamgirl.app.fake.FakeSummaryDao
import com.dreamgirl.app.fake.fatalError
import com.dreamgirl.app.fake.retryableError
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 「发一句话 → 流式 → 后处理 → 落库 → 抽记忆」这条主链路的端到端测试。
 * 全部跑在纯 JVM 上，不碰 Android、不碰网络。
 */
class ChatEngineTest {

    private lateinit var messages: FakeMessageDao
    private lateinit var sessions: FakeSessionDao
    private lateinit var summaries: FakeSummaryDao
    private lateinit var memoryDao: FakeMemoryDao
    private lateinit var llm: FakeLlm
    private lateinit var settings: FakeSettings
    private lateinit var keys: FakeKeys
    private lateinit var memory: MemoryEngine
    private lateinit var chat: ChatEngine

    private val today get() = DayClock.today()

    @Before
    fun setUp() {
        messages = FakeMessageDao()
        sessions = FakeSessionDao()
        summaries = FakeSummaryDao()
        memoryDao = FakeMemoryDao()
        llm = FakeLlm()
        settings = FakeSettings()
        keys = FakeKeys()
        memory = MemoryEngine(
            messageDao = messages,
            summaryDao = summaries,
            memoryDao = memoryDao,
            monthDao = FakeMonthDao(),
            client = llm,
            settings = settings,
            apiKeyStore = keys,
        )
        chat = ChatEngine(
            messageDao = messages,
            sessionDao = sessions,
            summaryDao = summaries,
            client = llm,
            settings = settings,
            apiKeyStore = keys,
            memoryEngine = memory,
        )
    }

    @Test
    fun `send persists the user message and her reply in order`() = runTest {
        llm.plan(FakeLlm.StreamPlan(deltas = listOf("[嘴上]在的。")))

        val events = chat.send("在吗").toList()

        assertTrue(events.first() is ChatEngine.ChatEvent.UserSaved)
        assertTrue(events.last() is ChatEngine.ChatEvent.Finished)

        val stored = messages.byDate(today.toString())
        assertEquals(2, stored.size)
        assertEquals("user", stored[0].role)
        assertEquals("在吗", stored[0].content)
        assertEquals("assistant", stored[1].role)
        assertEquals("在的。", stored[1].content)
    }

    @Test
    fun `deltas are streamed so the bubble can grow`() = runTest {
        llm.plan(FakeLlm.StreamPlan(deltas = listOf("哼", "，", "才不是呢")))
        val deltas = chat.send("想你了").toList()
            .filterIsInstance<ChatEngine.ChatEvent.Delta>()
            .map { it.text }
        assertEquals(listOf("哼", "，", "才不是呢"), deltas)
    }

    @Test
    fun `token usage is recorded on the stored reply`() = runTest {
        llm.plan(FakeLlm.StreamPlan(deltas = listOf("好。"), usage = com.dreamgirl.app.llm.TokenUsage(123, 45, 168)))
        chat.send("早").toList()
        val reply = messages.byDate(today.toString()).last()
        assertEquals(123, reply.promptTokens)
        assertEquals(45, reply.completionTokens)
    }

    @Test
    fun `a retryable failure is retried and then succeeds`() = runTest {
        llm.plan(
            FakeLlm.StreamPlan(deltas = emptyList(), failure = retryableError()),
            FakeLlm.StreamPlan(deltas = listOf("[嘴上]我在。")),
        )
        val events = chat.send("在吗").toList()

        assertTrue("应该重试过", llm.streamRequests.size >= 2)
        assertTrue(events.any { it is ChatEngine.ChatEvent.Restarting })
        assertTrue(events.last() is ChatEngine.ChatEvent.Finished)
        assertEquals("我在。", messages.byDate(today.toString()).last().content)
    }

    @Test
    fun `a bad key is not retried`() = runTest {
        llm.plan(FakeLlm.StreamPlan(deltas = emptyList(), failure = fatalError()))
        val events = chat.send("在吗").toList()

        assertEquals("Key 错重试也没用，只该请求一次", 1, llm.streamRequests.size)
        val failed = events.filterIsInstance<ChatEngine.ChatEvent.Failed>().single()
        assertEquals("API Key 无效或已失效", failed.error.message)
    }

    @Test
    fun `an out of character reply is rewritten`() = runTest {
        llm.plan(
            FakeLlm.StreamPlan(deltas = listOf("作为一个AI助手，我建议你注意休息。")),
            FakeLlm.StreamPlan(deltas = listOf("[嘴上]（敲你脑门）先睡觉，别的明天再说。")),
        )
        chat.send("好累").toList()

        assertEquals("应该重说过一遍", 2, llm.streamRequests.size)
        val stored = messages.byDate(today.toString()).last().content
        assertTrue("存下来的应该是重说后的版本", stored.contains("先睡觉"))
        assertFalse("括号动作应当被剥掉", stored.contains("敲你脑门"))
        assertFalse(stored.contains("作为一个AI"))
    }

    @Test
    fun `an over long essay is truncated before it reaches the database`() = runTest {
        val essay = "这件事是这样的呀。".repeat(60)
        llm.plan(FakeLlm.StreamPlan(deltas = listOf(essay)))
        val events = chat.send("说吧").toList()

        val finished = events.last() as ChatEngine.ChatEvent.Finished
        assertTrue("应当被判定为太长了", finished.post.truncated)
        assertTrue(finished.message.content.length <= 400)
        assertTrue(finished.message.content.endsWith("。"))
    }


    @Test
    fun `the configured reply length limit is applied end to end`() = runTest {
        settings.settings = settings.settings.copy(replyCharLimit = 200)
        llm.plan(FakeLlm.StreamPlan(deltas = listOf("她说了一句话。".repeat(40))))
        val events = chat.send("说吧").toList()
        val finished = events.last() as ChatEngine.ChatEvent.Finished
        assertTrue("应当被截断", finished.post.truncated)
        assertTrue("存下来的不能超过设置的上限", finished.message.content.length <= 200)
    }

    @Test
    fun `the day session message count grows with each exchange`() = runTest {
        llm.plan(FakeLlm.StreamPlan(deltas = listOf("一。")), FakeLlm.StreamPlan(deltas = listOf("二。")))
        chat.send("第一句").toList()
        chat.send("第二句").toList()
        assertEquals(4, sessions.byDate(today.toString())?.messageCount)
    }

    @Test
    fun `ensureSession is idempotent within the same day`() = runTest {
        val a = chat.ensureSession()
        val b = chat.ensureSession()
        assertEquals(a.date, b.date)
        assertEquals(a.startedAt, b.startedAt)
        assertEquals(1, sessions.all().size)
    }

    @Test
    fun `history is sent back to the model`() = runTest {
        llm.plan(FakeLlm.StreamPlan(deltas = listOf("第一轮。")), FakeLlm.StreamPlan(deltas = listOf("第二轮。")))
        chat.send("第一句").toList()
        chat.send("第二句").toList()

        val second = llm.streamRequests.last()
        val flat = second.messages.joinToString("\n") { "${it.role}:${it.content}" }
        assertTrue("上一轮她说过的话应该在上下文里", flat.contains("第一轮。"))
        assertTrue("上一轮我说的话也应该在", flat.contains("第一句"))
    }

    @Test
    fun `retrieved memories reach the prompt`() = runTest {
        memoryDao.insert(
            MemoryItemEntity(
                sourceDate = today.minusDays(1).toString(),
                content = "他喜欢喝美式，不加糖",
                tags = "咖啡,偏好",
                weight = 0.9f,
                layer = 3,
                createdAt = 1,
            ),
        )
        llm.plan(FakeLlm.StreamPlan(deltas = listOf("（记起来了）还是不加糖？")))
        chat.send("今天喝了杯咖啡").toList()

        val sent = llm.streamRequests.last().messages.joinToString("\n") { it.content }
        assertTrue("记忆应该被检索出来并拼进 prompt", sent.contains("美式"))
    }

    @Test
    fun `an unrelated message does not drag memories into the prompt`() = runTest {
        memoryDao.insert(
            MemoryItemEntity(
                sourceDate = today.minusDays(1).toString(),
                content = "他喜欢喝美式，不加糖",
                tags = "咖啡,偏好",
                weight = 0.9f,
                layer = 3,
                createdAt = 1,
            ),
        )
        llm.plan(FakeLlm.StreamPlan(deltas = listOf("嗯。")))
        chat.send("今天天气不错").toList()

        val sent = llm.streamRequests.last().messages.joinToString("\n") { it.content }
        assertFalse("没命中就不该硬塞", sent.contains("美式"))
    }

    @Test
    fun `proactive replies are flagged so the UI can label them`() = runTest {
        llm.plan(FakeLlm.StreamPlan(deltas = listOf("（戳你）这个点了还不睡？")))
        chat.proactive("现在是 01:20，他还没睡。").toList()

        val reply = messages.byDate(today.toString()).last()
        assertTrue(reply.proactive)
        assertEquals("assistant", reply.role)
    }

    @Test
    fun `an empty send is a no-op`() = runTest {
        val events = chat.send("   ").toList()
        assertTrue(events.isEmpty())
        assertTrue(messages.all().isEmpty())
    }


    // ---------------------------------------------------------------- 模型选择

    @Test
    fun `by default everything goes to the chat model`() = runTest {
        llm.plan(FakeLlm.StreamPlan(deltas = listOf("嗯。")))
        chat.send("我想辞职去搞自媒体，你说该怎么办").toList()
        assertEquals("deepseek-chat", llm.streamRequests.last().model)
    }

    @Test
    fun `a hard question switches to the reasoner when enabled`() = runTest {
        settings.settings = settings.settings.copy(useReasonerForHardQuestions = true)
        llm.plan(FakeLlm.StreamPlan(deltas = listOf("（放下本子）行不行，得看三件事。")))
        chat.send("我想辞职去搞自媒体，你说该怎么办").toList()
        assertEquals("deepseek-reasoner", llm.streamRequests.last().model)
    }

    @Test
    fun `small talk stays on the chat model even when the reasoner is enabled`() = runTest {
        settings.settings = settings.settings.copy(useReasonerForHardQuestions = true)
        llm.plan(FakeLlm.StreamPlan(deltas = listOf("[嘴上]在的。")))
        chat.send("今天好累啊，什么都不想干").toList()
        assertEquals(
            "日常闲聊切 reasoner 会又慢又爱讲道理，必须留在 chat 模型",
            "deepseek-chat",
            llm.streamRequests.last().model,
        )
    }

    @Test
    fun `regenerate replaces her last reply instead of appending`() = runTest {
        llm.plan(FakeLlm.StreamPlan(deltas = listOf("第一版。")), FakeLlm.StreamPlan(deltas = listOf("第二版。")))
        chat.send("你好").toList()
        chat.regenerate().toList()

        val assistantReplies = messages.byDate(today.toString()).filter { it.role == "assistant" }
        assertEquals("应该只剩一条回复", 1, assistantReplies.size)
        assertEquals("第二版。", assistantReplies.single().content)
    }

    // ---------------------------------------------------------- 她怎么对人 / 她懂的事

    /** 她真正发出去的那串 messages 拼起来。 */
    private fun sentPrompt(): String =
        llm.streamRequests.last().messages.joinToString("\n") { it.content }

    @Test
    fun `the character layer really goes out on the wire with every reply`() {
        runTest {
            chat.send("在吗").toList()
            val prompt = sentPrompt()
            assertTrue("性格层必须每轮都在", prompt.contains("## 她很爱你"))
            assertTrue("她也有情绪那一段也要在", prompt.contains("## 她自己也有情绪"))
            assertTrue("人设原文一个字没动", prompt.contains("名字是 dream 少女"))
        }
    }

    @Test
    fun `a knowledge card is injected when his words actually hit one`() {
        runTest {
            chat.send("我今天真的好累，头也疼").toList()
            val prompt = sentPrompt()
            assertTrue("撞上了就该给她那张准绳", prompt.contains("你这些年学到的"))
            assertTrue("应该是身体累那张", prompt.contains("他身体累"))
        }
    }

    @Test
    fun `nothing extra is injected when his words hit no card`() {
        runTest {
            chat.send("嗯").toList()
            val prompt = sentPrompt()
            assertFalse("不相干就必须一个 token 都不花", prompt.contains("你这些年学到的"))
            // 但性格层照常在
            assertTrue(prompt.contains("## 她很爱你"))
        }
    }

    @Test
    fun `the knowledge card never leaks into the cached prefix`() {
        runTest {
            chat.send("我妈又催婚了").toList()
            val request = llm.streamRequests.last()
            val prefix = request.messages.first().content
            assertFalse("知识卡不能进固定前缀，否则前缀缓存全废", prefix.contains("你这些年学到的"))
            assertTrue("性格层是固定的，应该在前缀里", prefix.contains("## 她很爱你"))
        }
    }

    @Test
    fun `when she speaks first she has one thing on her mind all day`() {
        runTest {
            // 主动开口不是回应某句话，所以给她一张"今天心里搁着的事"的卡。
            chat.proactive("现在没人跟你说话，你自己看了一眼时间：凌晨一点").toList()
            val prompt = sentPrompt()
            assertTrue("她主动开口时该有今天的心思", prompt.contains("你今天心里一直搁着"))
            assertTrue("她主动开口时性格层也必须在", prompt.contains("## 她很爱你"))
        }
    }

    @Test
    fun `her own mood is fed back into the next reply`() {
        runTest {
            // 她回复里自己写的那行心情，会被抠出来存下，并出现在下一轮的 prompt 里
            llm.plan(FakeLlm.StreamPlan(deltas = listOf("[心想] 心里软了一下。\n[嘴上] ……嗯。")))
            chat.send("我今天有点难受").toList()
            assertTrue("心情标签应当被记下来", settings.lastLiveMood?.contains("心里软了一下") == true)

            llm.plan(FakeLlm.StreamPlan(deltas = listOf("嗯。")))
            chat.send("好点了吗").toList()
            assertTrue(
                "她刚写下的心情应该出现在下一轮 prompt 里",
                sentPrompt().contains("心里软了一下"),
            )
        }
    }

    // ---------------------------------------------------------- 她自己的时间感

    @Test
    fun `she is told what time it is on every reply`() {
        runTest {
            chat.send("在吗").toList()
            val prompt = sentPrompt()
            assertTrue("她必须知道现在是什么时候", prompt.contains("〔现在〕"))
            assertTrue("要有周几", prompt.contains("周"))
            assertTrue("要知道她自己的一天从四点算起", prompt.contains("凌晨四点"))
        }
    }

    @Test
    fun `the time block is dynamic so the cached prefix survives`() {
        runTest {
            chat.send("第一句").toList()
            val prefix = llm.streamRequests.last().messages.first().content
            // 时间是每轮都变的，绝不能进固定前缀（否则前缀缓存全废）
            assertFalse("时间不能进固定前缀", prefix.contains("〔现在〕"))
            assertFalse("前缀里不该有具体钟点", Regex("""现在是.*点\d\d""").containsMatchIn(prefix))
        }
    }

    @Test
    fun `she knows how long it has been since he last spoke`() {
        runTest {
            // 造一条"他三天前说过话"的历史
            val threeDaysAgo = DayClock.today().minusDays(3)
            messages.insert(
                MessageEntity(
                    sessionDate = threeDaysAgo.toString(), role = "user",
                    content = "我先忙了", createdAt = DayClock.startOfSessionDay(threeDaysAgo) + 3600_000,
                ),
            )
            chat.send("我回来了").toList()
            val prompt = sentPrompt()
            assertTrue("她要能感觉到隔了几天，而不是只看到一句「我回来了」", prompt.contains("没跟你说话") || prompt.contains("没来了"))
        }
    }

    @Test
    fun `the gap is measured from his previous words and never from the one he just sent`() {
        runTest {
            // 他刚发的这一句要从历史里排除掉（history.filter { it.id != userId }），
            // 所以"间隔"说的是**他上一次**开口距现在多久 —— 这正是人感觉时间的方式。
            // 今天他第一次说话：没有"上一次"，就不该编一个间隔出来。
            chat.send("在吗").toList()
            val prompt = sentPrompt()
            assertTrue("今天第一次说话也要有时间感", prompt.contains("〔现在〕"))
            assertFalse(
                "今天他还没说过话，不该编出一个间隔：$prompt",
                prompt.contains("上一句是刚刚说的"),
            )
        }
    }

    @Test
    fun `a second message in the same session does get a gap from his first`() {
        runTest {
            chat.send("第一句").toList()
            chat.send("第二句").toList()
            val prompt = sentPrompt()
            // 他上一句就是刚刚发的（测试里时间几乎不动）
            assertTrue("第二次开口应当能感觉到他刚说过话", prompt.contains("刚刚") || prompt.contains("几分钟"))
        }
    }

    // ---------------------------------------------------------- 分段连发

    @Test
    fun `she sends several short bubbles instead of one long paragraph`() {
        runTest {
            llm.plan(FakeLlm.StreamPlan(deltas = listOf("[嘴上] 在吗\n问你个事\n算了没事")))
            chat.send("在？").toList()
            val hers = messages.byDate(today.toString()).filter { it.role == "assistant" }
            assertEquals("三行应该变成三个气泡", 3, hers.size)
            assertEquals(listOf("在吗", "问你个事", "算了没事"), hers.map { it.content })
        }
    }

    @Test
    fun `a single line still produces exactly one bubble`() {
        runTest {
            llm.plan(FakeLlm.StreamPlan(deltas = listOf("[嘴上] 嗯，睡吧。")))
            chat.send("晚安").toList()
            val hers = messages.byDate(today.toString()).filter { it.role == "assistant" }
            assertEquals(1, hers.size)
            assertEquals("嗯，睡吧。", hers.first().content)
        }
    }

    @Test
    fun `no memory means she cannot claim a shared past`() {
        runTest {
            // 第一轮重写，第二轮给个干净回复
            llm.plan(
                FakeLlm.StreamPlan(deltas = listOf("[嘴上] 你上次说想去看海。")),
                FakeLlm.StreamPlan(deltas = listOf("[嘴上] 哦。")),
            )
            chat.send("在吗").toList()
            assertEquals("无记忆却声称过往，应当触发重写", 2, llm.streamRequests.size)
            val hers = messages.byDate(today.toString()).filter { it.role == "assistant" }
            assertFalse(hers.any { it.content.contains("你上次说") })
        }
    }

    // ---------------------------------------------------------- P0：崩坏不再回灌

    @Test
    fun `her own garbled reply is not fed back into the next prompt`() {
        runTest {
            // 造一条"她上一轮崩了"的历史（真实崩坏样本）
            val garbled =
                "看错了一句。抱歉，窜台了。你要走的这类晚聊一般是真的在冷场。" +
                    "这几个小时你还绷着干啥真工作那不用说教以外都静了自己处理空间愿意说的说:[[不开口能当两个人]],"
            messages.insert(
                MessageEntity(
                    id = 900, sessionDate = today.toString(), role = "assistant",
                    content = garbled, createdAt = 1,
                ),
            )
            messages.insert(
                MessageEntity(
                    id = 901, sessionDate = today.toString(), role = "assistant",
                    content = "嗯，我知道了。", createdAt = 2,
                ),
            )

            chat.send("在吗").toList()

            val prompt = sentPrompt()
            assertFalse(
                "崩坏的历史必须被跳过，否则她会沿着崩坏模式继续崩",
                prompt.contains("窜台") || prompt.contains("攒盘突"),
            )
            assertTrue("正常的历史该留着", prompt.contains("嗯，我知道了。"))
        }
    }

    @Test
    fun `a garbled reply triggers a rewrite instead of being saved`() {
        runTest {
            llm.plan(
                FakeLlm.StreamPlan(deltas = listOf(
                    "[嘴上] 你要走的这类晚聊一般是真的在冷场。" +
                        "这几个小时你还绷着干啥真工作那不用说教以外都静了自己处理空间愿意说的说:[[不开口能当两个人]],",
                )),
                FakeLlm.StreamPlan(deltas = listOf("[嘴上] 嗯。路上小心。")),
            )
            chat.send("我要走了").toList()

            assertEquals("说崩了应当触发重写", 2, llm.streamRequests.size)
            val hers = messages.byDate(today.toString()).filter { it.role == "assistant" }
            assertFalse("崩坏的版本不该入库", hers.any { it.content.contains("攒盘") })
            assertTrue(hers.any { it.content.contains("路上小心") })
        }
    }

    @Test
    fun `the mood is injected only once`() {
        runTest {
            val mood = settings.current().liveMood
            chat.send("在吗").toList()
            val prompt = sentPrompt()
            // 新的情绪系统和旧的 herMood 不能同时出现（会给模型矛盾指令）
            val newMood = prompt.contains("〔你现在的心境〕")
            val oldMood = prompt.contains("〔你现在的心情〕")
            assertFalse("心情被注入了两次", newMood && oldMood)
        }
    }

    @Test
    fun `input meta blocks do not collide with her output markers`() {
        runTest {
            chat.send("在吗").toList()
            val prompt = sentPrompt()
            // 输入的 meta 块用〔〕，输出的标记用 [] —— 两套不能混
            assertTrue("输入的 meta 块应该用〔〕", prompt.contains("〔现在〕"))
            assertFalse("输入里不该再出现【现在】这种方头括号块", prompt.contains("【现在】"))
        }
    }
}
