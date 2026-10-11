package com.dreamgirl.app.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「她崩了」的文本检测。
 *
 * 判据必须**保守**：漏过一条崩坏只是它再坏一轮；把正常的话误判成崩坏，
 * 会白白丢掉上下文、还可能反复触发重写（费钱）。
 */
class OutputHygieneTest {

    /** 用户实测截图里那段 —— 这段是真实的崩坏样本。 */
    private val realGarbled =
        "看错了一句。抱歉，窜台了。\n" +
            "你要走的这类晚聊一般是真的在冷场。我刚看你敲出来就是这么短的一管感冒。" +
            "这几个小时你还绷着干啥真工作那不用说教以外都静了自己处理空间愿意说的说:[[不开口能当两个人]],\n" +
            "那你说走是什么意思我来把你问得有点折回上头得。怕什么选最近攒盘突你朋友飞过有个视频点了不在？"

    @Test
    fun `the real garbled sample is caught`() {
        val v = OutputHygiene.check(realGarbled)
        assertTrue("这段实际崩坏过，必须能认出来", v.garbled)
    }

    @Test
    fun `nested brackets are caught`() {
        assertTrue(OutputHygiene.isGarbled("他说的话是这样的:[[不开口能当两个人]],你懂吗"))
        assertTrue(OutputHygiene.isGarbled("他的意思【【不是这样】】的"))
    }

    @Test
    fun `a long run with no pause is caught`() {
        val noPause = "他今天跟我说了很多很多话我一句都没听进去因为我一直在想别的事情".repeat(2)
        assertTrue("一长串没有停顿就不是人话", OutputHygiene.isGarbled(noPause))
    }

    @Test
    fun `normal replies are NOT flagged`() {
        listOf(
            "嗯，睡吧。",
            "在的，刚回来。今天有点累，不太想说话。",
            "我今天想了想，还是觉得这件事得跟你说清楚。不是因为我在意，是因为你今天的语气不对。",
            "行吧，那就这样。明天再说。",
            "你去哪了？我等了你好久，还以为你出事了。",
            "哼，才不是为你呢。水在桌上，自己拿。",
        ).forEach {
            assertFalse("正常的话被误判成崩坏了：$it", OutputHygiene.isGarbled(it))
        }
    }

    @Test
    fun `normal brackets and quotes are NOT flagged`() {
        listOf(
            "我去（公司）一趟，回来跟你说。",
            "他说「明天见」，我就走了。",
            "（笑）你这人也太有意思了。",
            "她说：【明天来】——记着了。",
            "这就是【主要矛盾】，懂吗？",
        ).forEach {
            assertFalse("正常括号被误判：$it", OutputHygiene.isGarbled(it))
        }
    }

    @Test
    fun `short text is never flagged even if odd`() {
        assertFalse(OutputHygiene.isGarbled(":[[x]]"))
        assertFalse(OutputHygiene.isGarbled("嗯"))
    }

    @Test
    fun `repeated fragments are caught`() {
        assertTrue(OutputHygiene.isGarbled("你说的对呀对呀对呀对呀，我明白了。"))
    }
}
