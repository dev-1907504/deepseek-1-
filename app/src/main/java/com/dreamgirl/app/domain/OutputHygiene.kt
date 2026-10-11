package com.dreamgirl.app.domain

/**
 * 输出卫生：**认出"她崩了"的那种文本**。
 *
 * 用户实测遇到过的崩坏长这样：
 *
 * > 你要走的这类晚聊一般是真的在冷场。我刚看你敲出来就是这么短的一管感冒。
 * > 这几个小时你还绷着干啥真工作那不用说教以外都静了自己处理空间愿意说的说:[[不开口能当两个人]],
 * > 那你说走是什么意思我来把你问得有点折回上头得。
 *
 * 词都是真的，但组不成句子 —— 这是模型在**语言崩溃**状态下的输出。
 *
 * 为什么必须认出来：**这种文本一旦存进数据库，就会作为历史回灌进下一轮的 prompt**，
 * 模型看到自己上一句就在崩，会沿着那个模式继续崩 —— 垃圾是**自我强化**的。
 * 实测就是这样：18:18 她说了一句不对劲的，21:52 彻底崩掉。
 *
 * 这里只做**确定性特征判断**，不调模型。判据偏保守：
 * 宁可漏过一条，也不要把正常的话当成崩坏（那会白白丢掉上下文）。
 */
object OutputHygiene {

    data class Verdict(val garbled: Boolean, val why: String = "")

    private val OK = Verdict(false)

    /** 中文标点 + 常见停顿 —— 正常说话的句子一定会在这些地方断开。 */
    private val BREAKS = charArrayOf(
        '。', '，', '、', '！', '？', '；', '：', '…', '—', '\n',
        '.', ',', '!', '?', ';', ':', '~',
    )

    /** 无标点长串的上限 —— 超过这么多字一个停顿都没有，就不是人话了。 */
    private const val MAX_UNBROKEN_RUN = 34

    /** 符号（非中英文数字、非常见标点）占比上限。 */
    private const val MAX_ODD_SYMBOL_RATIO = 0.14

    /** 允许的"正常"符号。 */
    private val NORMAL_SYMBOLS = "。，、！？；：…—·「」『』（）《》〈〉【】〔〕\"'“”‘’,.!?;:~-— \n\t".toSet()

    fun check(text: String): Verdict {
        val t = text.trim()
        if (t.length < 12) return OK    // 太短，判不出来也不值得判

        // 1) 括号嵌套错乱 —— 实测那种 `:[[不开口能当两个人]]` 就是它
        if (NESTED_BRACKET.containsMatchIn(t)) {
            return Verdict(true, "括号嵌套错乱")
        }

        // 2) 一长串没有任何停顿
        var run = 0
        var worst = 0
        for (c in t) {
            if (c in BREAKS) {
                run = 0
            } else {
                run++
                if (run > worst) worst = run
            }
        }
        if (worst >= MAX_UNBROKEN_RUN) {
            return Verdict(true, "有 ${worst} 个字连着没有任何停顿")
        }

        // 3) 奇怪符号占比过高
        val odd = t.count { c ->
            !c.isLetterOrDigit() && !isCjk(c) && c !in NORMAL_SYMBOLS
        }
        if (odd.toDouble() / t.length > MAX_ODD_SYMBOL_RATIO) {
            return Verdict(true, "标点符号乱得不像话（${odd}/${t.length}）")
        }

        // 4) 同一小段原地重复
        if (REPEAT.containsMatchIn(t)) {
            return Verdict(true, "同一句话在原地重复")
        }

        return OK
    }

    fun isGarbled(text: String): Boolean = check(text).garbled

    private fun isCjk(c: Char): Boolean = c.code in 0x4E00..0x9FFF

    private val NESTED_BRACKET = Regex("""[\[【（(]\s*[\[【（(]|[\]】）)]\s*[\]】）)]""")

    /** 同一个 2–4 字的片段紧接着重复三遍以上。 */
    private val REPEAT = Regex("""(.{2,4})\1{2,}""")
}
