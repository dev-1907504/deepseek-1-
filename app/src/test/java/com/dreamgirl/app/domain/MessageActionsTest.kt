package com.dreamgirl.app.domain

import com.dreamgirl.app.data.DayClock
import com.dreamgirl.app.data.MessageEntity
import com.dreamgirl.app.fake.FakeKeys
import com.dreamgirl.app.fake.FakeLlm
import com.dreamgirl.app.fake.FakeMemoryDao
import com.dreamgirl.app.fake.FakeMessageDao
import com.dreamgirl.app.fake.FakeMonthDao
import com.dreamgirl.app.fake.FakeSessionDao
import com.dreamgirl.app.fake.FakeSettings
import com.dreamgirl.app.fake.FakeSummaryDao
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 长按菜单背后的行为：引用 / 删除 / 撤销。
 *
 * 最要紧的两条：
 *  1. **引用必须真的进 prompt** —— 否则它只是个装饰，她根本不知道你在回哪句。
 *  2. **误删必须能撤销** —— 聊天记录删了找不回来是最难受的事。
 */
class MessageActionsTest {

    private lateinit var messages: FakeMessageDao
    private lateinit var sessions: FakeSessionDao
    private lateinit var summaries: FakeSummaryDao
    private lateinit var llm: FakeLlm
    private lateinit var settings: FakeSettings
    private lateinit var chat: ChatEngine

    private val today get() = DayClock.today()

    @Before
    fun setUp() {
        messages = FakeMessageDao()
        sessions = FakeSessionDao()
        summaries = FakeSummaryDao()
        llm = FakeLlm()
        settings = FakeSettings()
        val memory = MemoryEngine(
            messageDao = messages,
            summaryDao = summaries,
            memoryDao = FakeMemoryDao(),
            monthDao = FakeMonthDao(),
            client = llm,
            settings = settings,
            apiKeyStore = FakeKeys(),
        )
        chat = ChatEngine(
            messageDao = messages,
            sessionDao = sessions,
            summaryDao = summaries,
            client = llm,
            settings = settings,
            apiKeyStore = FakeKeys(),
            memoryEngine = memory,
        )
    }

    private fun sentPrompt(): String =
        llm.streamRequests.last().messages.joinToString("\n") { it.content }

    @Test
    fun `quoting a message actually reaches the prompt`() = runTest {
        val first = messages.insert(
            MessageEntity(sessionDate = today.toString(), role = "assistant", content = "你今天面试怎么样？", createdAt = 1),
        )
        val quoted = messages.byId(first)!!

        chat.send("还行，就是最后那个问题没答好", quote = quoted).toList()

        val prompt = sentPrompt()
        assertTrue("她必须知道你回的是哪一句", prompt.contains("你今天面试怎么样？"))
        assertTrue("而且要能分清那是她自己说的", prompt.contains("你自己"))
    }

    @Test
    fun `quoting my own message says it was mine`() = runTest {
        val myId = messages.insert(
            MessageEntity(sessionDate = today.toString(), role = "user", content = "我明天要去体检", createdAt = 1),
        )
        chat.send("记得空腹", quote = messages.byId(myId)!!).toList()
        assertTrue(sentPrompt().contains("他自己"))
    }

    @Test
    fun `the quote is stored on the message so the bubble can show it`() = runTest {
        val id = messages.insert(
            MessageEntity(sessionDate = today.toString(), role = "assistant", content = "别熬夜了", createdAt = 1),
        )
        chat.send("知道啦", quote = messages.byId(id)!!).toList()

        val mine = messages.byDate(today.toString()).last { it.role == "user" }
        assertEquals(id, mine.quoteId)
        assertEquals("别熬夜了", mine.quoteText)
    }

    @Test
    fun `a reply with no quote carries no quote state`() = runTest {
        chat.send("在吗").toList()
        val mine = messages.byDate(today.toString()).last { it.role == "user" }
        assertNull(mine.quoteId)
        assertNull(mine.quoteText)
        assertFalse("不该凭空冒出引用", sentPrompt().contains("他这句话是接着"))
    }

    @Test
    fun `a quoted media message gets a readable placeholder`() = runTest {
        val id = messages.insert(
            MessageEntity(
                sessionDate = today.toString(), role = "assistant", content = "",
                createdAt = 1, kind = MessageEntity.KIND_IMAGE, mediaPath = "a.jpg",
            ),
        )
        chat.send("这张真好看", quote = messages.byId(id)!!).toList()
        val mine = messages.byDate(today.toString()).last { it.role == "user" }
        assertEquals("[图片]", mine.quoteText)
    }

    @Test
    fun `deleting removes exactly one message`() = runTest {
        val a = messages.insert(MessageEntity(sessionDate = today.toString(), role = "user", content = "一", createdAt = 1))
        val b = messages.insert(MessageEntity(sessionDate = today.toString(), role = "assistant", content = "二", createdAt = 2))
        chat.deleteMessage(a)
        val left = messages.byDate(today.toString())
        assertEquals(1, left.size)
        assertEquals(b, left.first().id)
    }

    @Test
    fun `deleting a batch removes all of them`() = runTest {
        val ids = (1..4).map {
            messages.insert(MessageEntity(sessionDate = today.toString(), role = "user", content = "第$it", createdAt = it.toLong()))
        }
        chat.deleteMessages(listOf(ids[0], ids[2]))
        val left = messages.byDate(today.toString()).map { it.id }
        assertEquals(listOf(ids[1], ids[3]), left)
    }

    @Test
    fun `undo puts the message back with its original id`() = runTest {
        val id = messages.insert(
            MessageEntity(sessionDate = today.toString(), role = "assistant", content = "记住这句", createdAt = 5),
        )
        val snapshot = messages.byId(id)!!
        chat.deleteMessage(id)
        assertTrue(messages.byDate(today.toString()).isEmpty())

        chat.restoreMessage(snapshot)

        val back = messages.byDate(today.toString())
        assertEquals(1, back.size)
        assertEquals("用原来的 id 写回去，引用它的人才不会指向空", id, back.first().id)
        assertEquals("记住这句", back.first().content)
    }

    @Test
    fun `deleting keeps the session message count honest`() = runTest {
        messages.insert(MessageEntity(sessionDate = today.toString(), role = "user", content = "一", createdAt = 1))
        val second = messages.insert(
            MessageEntity(sessionDate = today.toString(), role = "assistant", content = "二", createdAt = 2),
        )
        chat.ensureSession()
        chat.send("三").toList()
        val before = sessions.byDate(today.toString())!!.messageCount

        chat.deleteMessage(second)

        val after = sessions.byDate(today.toString())!!.messageCount
        assertTrue("删完条数要跟着减，否则列表上的数字对不上", after < before)
    }

    @Test
    fun `deleting a message that is already gone is harmless`() = runTest {
        chat.deleteMessage(9999L)
        assertTrue(messages.byDate(today.toString()).isEmpty())
    }
}
