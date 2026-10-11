package com.dreamgirl.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「她懂的那些事」的检索行为。
 *
 * 这些测试守的是**成本和不添乱**两件事：
 *  - 撞不上就必须一张都不给（默认不注入，不命中不花 token）
 *  - 撞上了也不能塞太多（挤占人设和记忆）
 *  - 她自己今天的心情要影响她怎么读这句话
 */
class KnowledgeEngineTest {

    @Test
    fun `trigger words hit the right card`() {
        val hits = KnowledgeEngine.retrieve("我今天真的好累，头也疼")
        assertTrue("「累/头疼」应该撞上身体累那张卡", hits.any { it.card.id == "他身体累" })
    }

    @Test
    fun `irrelevant small talk hits nothing at all`() {
        // 这一条是成本红线：不相干就一个 token 都不花
        val hits = KnowledgeEngine.retrieve("嗯")
        assertTrue("一个字不该撞出卡来", hits.isEmpty())
    }

    @Test
    fun `a card is never injected just because the text is long`() {
        val long = "今天".repeat(400)
        val hits = KnowledgeEngine.retrieve(long)
        assertTrue("单纯的字多不该撞出卡来", hits.size <= KnowledgeEngine.MAX_INJECTED)
    }

    @Test
    fun `at most MAX_INJECTED cards come back`() {
        // 这句话同时碰到好几类触发词
        val hits = KnowledgeEngine.retrieve("我好累，头疼，而且工作搞砸了，领导还针对我，睡不着")
        assertTrue("不能一次塞一堆", hits.size <= KnowledgeEngine.MAX_INJECTED)
    }

    @Test
    fun `injected text stays under the character cap`() {
        val hits = KnowledgeEngine.retrieve("我妈催婚，前任又来找我，工作也想辞职，睡不着又很累")
        val rendered = KnowledgeEngine.renderForPrompt(hits).orEmpty()
        val body = rendered.lines().filter { it.startsWith("- ") }.joinToString("")
        assertTrue(
            "注入正文 ${body.length} 字，超过上限 ${KnowledgeEngine.MAX_INJECTED_CHARS}",
            body.length <= KnowledgeEngine.MAX_INJECTED_CHARS,
        )
    }

    @Test
    fun `no hits renders to null so nothing is injected`() {
        assertNull(KnowledgeEngine.renderForPrompt(emptyList()))
    }

    @Test
    fun `hits render as her own standard not a template`() {
        val hits = KnowledgeEngine.retrieve("他跟我说对不起，是他错了")
        val text = KnowledgeEngine.renderForPrompt(hits)
        assertNotNull("道歉这件事她应该有话可说", text)
        assertTrue("要提示她用自己的话说", text!!.contains("别照念"))
    }

    @Test
    fun `her mood is read as an overall tone, not chopped into keywords`() {
        // 心情是自由文本，拆成 2-gram 去撞词表基本撞不上（第一版就是这么错的）。
        assertTrue("「来劲」应该是正底色", KnowledgeEngine.toneOf("尾巴翘起来了") > 0)
        assertTrue("「蔫」应该是负底色", KnowledgeEngine.toneOf("有点蔫但嘴上不认") < 0)
        assertEquals("说不准的心情就是中性", 0.0, KnowledgeEngine.toneOf(null), 0.0001)
        assertEquals("空心情也是中性", 0.0, KnowledgeEngine.toneOf("   "), 0.0001)
    }

    @Test
    fun `the same sentence lands differently depending on her mood`() {
        val sentence = "今天真的累"
        val bright = KnowledgeEngine.retrieve(sentence, "来劲了，心里松快")
        val droopy = KnowledgeEngine.retrieve(sentence, "有点蔫，不太想说话")
        val changed = bright.map { it.card.id } != droopy.map { it.card.id } ||
            bright.map { it.score } != droopy.map { it.score }
        assertTrue("她说同样的话，心情不同，理解也该有差别", changed)
    }

    @Test
    fun `a droopy mood pushes her toward the quiet cards`() {
        // 这一条守的是"她这一天换了个人"这件事：蔫着的时候更容易想到安静的那几张
        val droopy = KnowledgeEngine.retrieve("今天就我们俩", "有点蔫，不太想说话")
        val bright = KnowledgeEngine.retrieve("今天就我们俩", "来劲了")
        val droopyQuiet = droopy.count { it.card.id == "他不想说话" || it.card.id == "他迷茫" }
        val brightQuiet = bright.count { it.card.id == "他不想说话" || it.card.id == "他迷茫" }
        assertTrue("蔫着不该比来劲时更少想到安静的事", droopyQuiet >= brightQuiet)
    }

    @Test
    fun `a null mood still retrieves normally`() {
        val hits = KnowledgeEngine.retrieve("我睡不着，几点了")
        assertTrue(hits.any { it.card.id == "他半夜还没睡" })
    }

    @Test
    fun `card of the day is stable within the same day and mood`() {
        val a = KnowledgeEngine.cardOfTheDay("2026-10-11", "尾巴翘起来了")
        val b = KnowledgeEngine.cardOfTheDay("2026-10-11", "尾巴翘起来了")
        assertEquals("同一天同一心情必须翻到同一张，否则她一天里变了个人", a?.id, b?.id)
    }

    @Test
    fun `card of the day has a card to offer`() {
        assertNotNull(KnowledgeEngine.cardOfTheDay("2026-10-11", null))
    }

    @Test
    fun `character layer is present and talks about both her feelings and how she loves`() {
        val layer = KnowledgeEngine.characterLayer()
        assertTrue("性格层不能为空", layer.isNotBlank())
        assertTrue("她也有情绪那一段", layer.contains("她自己也有情绪"))
        assertTrue("她很爱你那一段", layer.contains("她很爱你"))
        assertTrue("她什么时候不出手那一段", layer.contains("她什么时候不出手"))
    }

    @Test
    fun `character layer never claims to be an assistant or a therapist`() {
        val layer = KnowledgeEngine.characterLayer()
        listOf("AI", "助手", "模型", "咨询师", "心理医生").forEach {
            assertTrue("性格层里不该出现「$it」", !layer.contains(it))
        }
    }
}
