package com.dreamgirl.app.domain

/**
 * 后处理（方案 §2.2 行为原则 6「硬约束 + 后处理」）：
 * 命中"我是AI/提示词"这类模式时重写；超长"小作文"截断重问。
 *
 * 这里只做**判定与轻量修补**，重写本身由 ChatEngine 决定是否再发一次请求。
 */
object PostProcessor {

    /** 跳出角色的痕迹。命中就重写（而不是删掉——删掉会留下接不上的半句）。 */
    private val OUT_OF_ROLE_PATTERNS = listOf(
        "作为一个人工智能", "作为一个AI", "作为 AI", "作为AI",
        "我是人工智能", "我是一个AI", "我是AI", "作为一个语言模型", "语言模型",
        "AI助手", "人工智能助手", "智能助手",
        "系统提示词", "系统设定", "提示词", "prompt",
        "我无法继续这个角色", "我不能扮演", "感谢您的理解",
        "希望这对您有帮助", "如果您还有其他问题",
        "很抱歉，我", "我不能", "抱歉，我不能",
    )

    /** 背课文 / 喊口号 / 堆大词的痕迹。 */
    private val PREACHY_PATTERNS = listOf(
        "综上所述", "由此可见", "首先，", "其次，", "最后，",
        "马克思主义认为", "辩证地看", "矛盾是普遍", "生产力决定生产关系",
        "我们要坚持", "我们必须坚持", "伟大的",
    )

    /** 客服腔。她是个有脾气的人，不该这么说话。 */
    private val CUSTOMER_SERVICE = listOf(
        "请问有什么可以帮您", "很高兴为您服务", "您的需求", "亲，",
        "感谢您的反馈", "我们会尽快", "敬请谅解",
    )

    /**
     * 小作文：她的人设要求"回复简短自然，不写小作文"。
     * 上限可由设置页调 —— 觉得她啰嗦就往下拧，觉得太干就往上拧。
     */
    const val DEFAULT_HARD_LIMIT = 400
    private const val SOFT_RATIO = 55   // 软阈值 = 硬阈值 × 55%

    /**
     * **编造过往**的句式 —— 这是最严重的一种假。
     *
     * 她宣称一件"发生过的事"，但如果没有记忆支撑，就是在骗人
     * （实测踩过：她编出"上礼拜我跟你说过那本淡蓝色封皮的本子"，数据库里一个字都没有）。
     *
     * 这里只做**句式**层面的嫌疑标记，真正的"有没有来源"由 ChatEngine 结合记忆判断。
     */
    internal val FABRICATION_PATTERNS = listOf(
        "你上次说", "你上回说", "你之前说", "你以前说", "你跟我说过",
        "我跟你提过", "我跟你说过", "我上次说", "我上回说",
        "你还记得吗", "你还记得吧",
    )

    /** 列点：她讲道理是一句一句说的，不该写成条列。 */
    private val LIST_MARKERS = listOf("\n- ", "\n* ", "\n1. ", "\n2. ", "\n3. ", "\n• ")

    private val CJK = Regex("[\\u4e00-\\u9fff]")
    private val ASCII_LETTER = Regex("[A-Za-z]")

    data class Result(
        val text: String,
        /** 需要重新生成（而不是截断就能救）。 */
        val needsRewrite: Boolean,
        /** 被截断了。 */
        val truncated: Boolean,
        val reasons: List<String>,
    ) {
        val changed: Boolean get() = needsRewrite || truncated
    }

    /**
     * @param userText 用户刚说的那句。用于判断"她是不是答非所问地换了语言"。
     *                 不传也能用，只是少这一项检查。
     */
    /**
     * 括号动作：（歪头）（凑近）（尾巴翘起来）。
     *
     * ⚠️ **不能无差别删所有括号** —— 「我去（公司）一趟」里的（公司）是正常内容。
     * 只在**行首或句读之后**、且括号内是短动作词时才删：
     * 那正是模型写舞台指示的位置。
     */
    private val ACTION_PATTERN = Regex(
        """(?:^|(?<=[。！？!?，,、\n]))\s*[（(][^（()）\n]{1,12}[)）]\s*""",
        RegexOption.MULTILINE,
    )

    /**
     * 记账式的记号：`难受=哪儿`、`说累了=。` → 只留前半句。
     *
     * 这是模型"记笔记"漏出来的，不是说话。只在**整个等号片段前后没有别的内容**时处理，
     * 免得误伤正常文本里的等号。
     */
    private val NOTE_PATTERN = Regex("""([\u4e00-\u9fff]{2,8})\s*[=＝]\s*[\u4e00-\u9fff]{0,8}[。，！？]?""")

    fun process(
        raw: String,
        userText: String? = null,
        hardLimit: Int = DEFAULT_HARD_LIMIT,
        /**
         * 这一轮她**有没有**可依据的记忆。
         *
         * 没有记忆时，任何"你上次说过"都必然是编的 —— 那才判定为编造。
         * 有记忆时不判定（代码分不清她引用的是不是真记忆，交给提示词约束）。
         */
        memorySupported: Boolean = true,
    ): Result {
        val softLimit = (hardLimit * SOFT_RATIO) / 100
        val reasons = mutableListOf<String>()
        var text = raw.trim()

        // 去 markdown 加粗/标题，聊天里不该出现
        text = text.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
            .replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")
            .trim()

        // 括号动作：人格原文已经改成"不用括号写动作和神态"，
        // 但模型偶尔还会写。**代码兜底**，不只靠提示词请求。
        // 只删整段括起来的短提示（（歪头）（凑近）），不碰正常句子里的括号引用。
        text = ACTION_PATTERN.replace(text, "").replace(Regex("[ \t]{2,}"), " ").trim()

        // 说话像在对账本的记号：`=` `：` 连在短词后面的那种（"难受=哪儿"）
        text = NOTE_PATTERN.replace(text, "$1").trim()

        val outOfRole = OUT_OF_ROLE_PATTERNS.firstOrNull { text.contains(it) }
        if (outOfRole != null) reasons += "说了「$outOfRole」这种跳出角色的话"

        val preachy = PREACHY_PATTERNS.count { text.contains(it) }
        if (preachy >= 2) reasons += "像在背课文（$preachy 处）"

        if (!memorySupported) {
            val fabrication = FABRICATION_PATTERNS.firstOrNull { text.contains(it) }
            if (fabrication != null) {
                reasons += "没有任何记忆依据，却声称「$fabrication」—— 这是编造"
            }
        }

        val cs = CUSTOMER_SERVICE.firstOrNull { text.contains(it) }
        if (cs != null) reasons += "客服腔（「$cs」）"

        val listy = LIST_MARKERS.count { text.contains(it) }
        if (listy >= 2) reasons += "写成了条列（$listy 条）"

        if (switchedLanguage(text, userText)) reasons += "我说中文，她却回了一串英文"

        val tooLong = text.length > softLimit
        if (tooLong) reasons += "太长了（${text.length} 字）"

        // 崩坏（语言乱码）——必须重写。它比"说了句不合适的话"严重得多，
        // 而且一旦入库就会回灌进下一轮，把后面全都带崩。
        val hygiene = OutputHygiene.check(text)
        if (hygiene.garbled) reasons += "这段话说崩了（${hygiene.why}）"

        val mustRewrite = reasons.any {
            it.contains("跳出角色") || it.contains("背课文") || it.contains("客服腔") ||
                it.contains("条列") || it.contains("英文") || it.contains("编造") ||
                it.contains("说崩了")
        }

        if (mustRewrite) {
            return Result(text = text, needsRewrite = true, truncated = false, reasons = reasons)
        }

        var truncated = false
        if (text.length > hardLimit) {
            text = truncateAtSentence(text, hardLimit)
            truncated = true
        }
        return Result(text = text, needsRewrite = false, truncated = truncated, reasons = reasons)
    }

    /** 对方说中文、她回英文 —— 这不是"她"，是模型串味了。 */
    private fun switchedLanguage(reply: String, userText: String?): Boolean {
        if (userText.isNullOrBlank()) return false
        val userCjk = CJK.findAll(userText).count()
        if (userCjk < 2) return false
        val replyCjk = CJK.findAll(reply).count()
        val replyLetters = ASCII_LETTER.findAll(reply).count()
        return replyLetters > 40 && replyCjk * 4 < replyLetters
    }

    /** 在句子边界（。！？…）处截断，尽量保留最后一个完整动作。 */
    fun truncateAtSentence(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val head = text.substring(0, limit)
        val cut = head.lastIndexOfAny(charArrayOf('。', '！', '？', '…', '\n'))
        return if (cut > limit / 3) head.substring(0, cut + 1).trim() else head.trim()
    }

    /**
     * 重写指令。把**具体哪里不对**告诉它，比笼统说"跑偏了"有效得多 ——
     * 尤其"太长"这一类，不给原因它会照原样再来一遍。
     */
    fun rewriteInstruction(reasons: List<String> = emptyList()): String = buildString {
        append("（刚才那句话不像你。")
        if (reasons.isNotEmpty()) {
            append("问题：")
            append(reasons.joinToString("；"))
            append("。")
        }
        append("重说一遍：短句、口语，1–3 句就够；可以带（）动作；")
        append("不解释自己是谁，不列条目，不用客服腔，直接说事。）")
    }
}
