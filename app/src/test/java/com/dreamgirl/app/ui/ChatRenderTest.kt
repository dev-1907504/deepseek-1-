package com.dreamgirl.app.ui

import androidx.compose.ui.text.AnnotatedString
import com.dreamgirl.app.ui.chat.renderWithActions
import com.dreamgirl.app.ui.theme.WxDarkPalette
import com.dreamgirl.app.ui.theme.WxLightPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * （动作/神态）的渲染规则（方案 §3）。
 * 这段逻辑决定她"说话"和"做动作"看起来是不是两回事，值得锁住。
 */
class ChatRenderTest {

    private fun render(s: String) = renderWithActions(s, WxLightPalette)

    /** 取出被特殊样式覆盖的那几段。 */
    private fun styledSpans(a: AnnotatedString): List<String> =
        a.spanStyles.map { a.text.substring(it.start, it.end) }

    @Test
    fun `full width action is styled`() {
        val spans = styledSpans(render("（尾巴啪嗒啪嗒拍地）哼，才不是呢。"))
        assertEquals(listOf("（尾巴啪嗒啪嗒拍地）"), spans)
    }

    @Test
    fun `half width action is styled too`() {
        val spans = styledSpans(render("(歪头) 你说什么？"))
        assertEquals(listOf("(歪头)"), spans)
    }

    @Test
    fun `multiple actions in one reply are all styled`() {
        val spans = styledSpans(render("（抬头）在的。（把本子推过去）说吧。"))
        assertEquals(listOf("（抬头）", "（把本子推过去）"), spans)
    }

    @Test
    fun `plain text has no styling`() {
        assertTrue(styledSpans(render("今天天气不错。")).isEmpty())
    }

    @Test
    fun `an unterminated bracket does not swallow the rest`() {
        val a = render("（尾巴翘着")
        assertEquals("（尾巴翘着", a.text)
        assertTrue(styledSpans(a).isEmpty())
    }

    @Test
    fun `text before and after an action is preserved verbatim`() {
        val src = "哼，（敲你脑门）说正事。"
        assertEquals(src, render(src).text)
    }

    @Test
    fun `styled spans use the secondary colour`() {
        val a = renderWithActions("（尾巴）在。", WxDarkPalette)
        assertEquals(WxDarkPalette.subText, a.spanStyles.single().item.color)
    }

    @Test
    fun `empty string renders to empty`() {
        assertEquals("", render("").text)
    }

    @Test
    fun `adjacent actions do not merge into one span`() {
        val a = render("（一）（二）")
        assertEquals(listOf("（一）", "（二）"), styledSpans(a))
    }
}
