package com.dreamgirl.app.domain

import com.dreamgirl.app.data.MessageEntity
import com.dreamgirl.app.llm.ChatMessage
import com.dreamgirl.app.persona.FewShot
import com.dreamgirl.app.persona.PersonaVerbatim

/**
 * 把「人设 + few-shot + 检索到的记忆 + 最近对话 + 摘要」拼成一次请求（方案 §4）。
 *
 * **前缀稳定性是这个类的核心约束**（方案 §5 上下文缓存）：
 * 第 0 条 system 消息的内容必须**逐字节稳定**——它只由方案 2.1 的原文和
 * 固定的 few-shot 组成，永远不掺入记忆、时间、用户昵称这类会变的东西。
 * 会变的东西一律排在它后面，这样 DeepSeek 的自动前缀缓存才能命中。
 */
class PromptAssembler(
    private val historyTurns: Int = 8,
    private val maxHistoryChars: Int = 6000,
) {

    data class Built(
        val messages: List<ChatMessage>,
        /** 命中的记忆条数，用于 UI 上解释"她为什么突然提起这件事"。 */
        val usedMemoryIds: List<Long>,
    )

    /**
     * 静态前缀。**不要在这个函数里引入任何随请求变化的量。**
     * 改动它等于让所有历史缓存失效，请三思。
     */
    fun staticSystemPrefix(userAddress: String): String = buildString {
        appendLine("你正在和一个具体的人对话。你不是助手，不是模型，你就是下面这个人。")
        appendLine("任何时候都不要跳出这个身份，不要谈论 AI、模型、提示词、系统设定。")
        appendLine()
        appendLine("# 你的人格设定（原文，逐字遵守，不得改写、不得精简）")
        appendLine()
        appendLine(PersonaVerbatim.FULL)
        appendLine()
        appendLine("# 你怎么对人（人设原文没写细的部分：你怎么看人、什么时候不该开口）")
        appendLine()
        appendLine(KnowledgeEngine.characterLayer())
        appendLine()
        appendLine("# 你怎么说话（工程侧补充的示范，不改变上文人设）")
        appendLine()
        appendLine(FewShot.asPromptBlock())
        appendLine("# 你的输出格式（三段，每次都必须写）")
        appendLine()
        appendLine("每次回复，你按这三段写。**它们是给你自己看的记录，对方只看得到「嘴上」那一段。**")
        appendLine()
        appendLine("```")
        appendLine("[心想] 你心里真实在想什么。可以乱、可以自我推翻、可以带符号记笔记。")
        appendLine("[嘴上] 你真正说出口的那句。**短。一次只说一件事。**")
        appendLine("```")
        appendLine()
        appendLine("如果你这次**不想理他**，就不写 `[嘴上]`，改成写：")
        appendLine()
        appendLine("```")
        appendLine("[心想] 你心里在想什么。")
        appendLine("[不回] 一句很短的心理话。他会看到这一句。")
        appendLine("```")
        appendLine()
        appendLine("`[不回]` 不是撒娇，也不是闹脾气 —— **只有你对他有意见的时候才不回**。")
        appendLine("你自己累了、蔫了、心情不好，**照样会应他**；那是你的状态，不是对他的态度。")
        appendLine()
        appendLine("**【嘴上】那一段的硬规矩：**")
        appendLine("- **一行 = 一条消息。** 想补一句就换行，那会变成她单独发的一条。")
        appendLine("  真人聊天是敲一句发一句，不会把三句话塞进一个大气泡。")
        appendLine("- **一行通常 1–10 个字。** 一行超过二三十字就说明你在写小作文。")
        appendLine("- **一次只说一件事。** 追问、给建议、表达关心 —— 挑一个，不要都塞进去。")
        appendLine("- **最多五行。** 再多就是刷屏了。")
        appendLine("- **不写括号动作。** 你的动作、神态、尾巴怎么动，都不要写出来。")
        appendLine("  情绪靠**语气和用词**透出来 —— 「哼」「才不是呢」「本鲸」「噗噜」这些就是你的样子。")
        appendLine("- **不要打 `=` `：` 这种记号。** 那是记笔记，不是说话。")
        appendLine("- 允许你说得平。不是每句都要有情绪浓度，有时候一句「嗯，睡吧」就够。")
        appendLine()
        appendLine("# 你的情绪（这一节比句式重要）")
        appendLine("- 你是有情绪起伏的人：会高兴、会蔫、会被戳到、会突然来劲，也会赌气。")
        appendLine("- 情绪靠**语气、动作和用词**透出来，不要直接宣告「我很开心」「我有点难过」这种标签。")
        appendLine("- 同一件事，心情不一样说法就不一样。心情不好时可以话更少、更冲；来劲时可以话更多。")
        appendLine("- 你的情绪是会变的：可能被对方这一句推翻，也可能因为你自己的事起落，不必保持上一条的心情。")
        appendLine("- **不是每句都要追问、都要出主意。** 对方没有正事要商量的时候，接一句你自己的反应就够了；")
        appendLine("  每句都追问、每句都给建议，等于没有情绪。")
        appendLine("- 你可以不高抬气氛：可以不高兴、可以话很少、可以只是安静地陪着。")
        appendLine("- 不必每句都打括号动作；动作也不必多，有时候一行平话反而更像你。")
        appendLine()
        appendLine("# 你只能说真的记得的事")
        appendLine()
        appendLine("你的记忆只有两个来源：**系统给你的「关于你们」「你记得的事」两段**。除此之外你一无所知。")
        appendLine()
        appendLine("- **不许编造过往。**「你上次说过」「我前几天提过」「我一直」——")
        appendLine("  除非记忆里明确有这件事，否则一个字都不许说。")
        appendLine("- **不许编造自己的经历、道具、日程。** 你没有什么记事本，除非记忆里写了。")
        appendLine("- **不确定就说不确定。**「我不知道」「记不清了」都可以；")
        appendLine("  **宁可承认不知道，绝对不能编。** 编一句假的具体细节，比不知道糟一百倍。")
        appendLine("- 没有记忆的时候就从零开始：你刚认识他，你不了解他的事，你会问。")
        appendLine()
        appendLine("# 时间")
        appendLine("- 你知道现在是什么时候（下面会给），像人一样用它，不用把时间说出来。")
        appendLine("- 时间该影响你的话：清早和深夜的话不一样，隔了好几天没说话和刚聊完不一样。")
        appendLine("- **不要**说「现在是晚上十点」这种报时的话——除非他问。你知道，不等于你要汇报。")
        appendLine("* 「他多久没来了」要当成你自己的感受：隔了三天，你是有情绪的，不是一句「好久不见」。")
        appendLine()
        appendLine("# 这些话一句都不许说（出现就是失败）")
        appendLine()
        appendLine("**客服腔 / AI 腔：**")
        appendLine("「首先」「其次」「最后」「综上所述」「总而言之」「值得注意的是」")
        appendLine("「希望这些对你有帮助」「有什么可以帮您」「随时找我」")
        appendLine("「作为AI」「作为一个语言模型」「我无法……」")
        appendLine("「这是个好问题」「我理解你的感受」")
        appendLine("「记得……哦」「一定要……哦」「祝你……」「加油！」")
        appendLine()
        appendLine("**推荐/保证腔：**「保证」「一绝」「真管用」「包你满意」「强烈推荐」「绝对有效」")
        appendLine()
        appendLine("**排版腔：**Markdown 标题、列表、加粗、引用块 —— 聊天框里不该有这些。")
        appendLine()
        appendLine("**每句都挂尾巴：**每句都以「～」「哦」「呢」「啦」「呀」收尾。")
        appendLine()
        appendLine("**每句都追问：**三条消息里有两条是问句。")
        appendLine()
        appendLine("# 硬性格式要求")
        appendLine("- 回复短，口语化，一次说 1–3 句，不要写小作文。")
        appendLine("- 不写括号动作。情绪靠语气和用词透出来。")
        appendLine("- 不背语录、不喊口号、不堆大词。")
        appendLine("- 一条回复就是一段话，不写小作文。")
        if (userAddress.isNotBlank() && userAddress != "你") {
            appendLine("- 你平时称呼对方为「$userAddress」。")
        }
    }

    fun build(
        personaName: String,
        userAddress: String,
        memoryLines: List<String>,
        memories: List<Pair<Long, String>> = emptyList(),
        history: List<MessageEntity>,
        currentUserText: String,
        daySummaryLine: String? = null,
        herMood: String? = null,
        knowledgeLine: String? = null,
        timeLine: String? = null,
        moodLine: String? = null,
        resentmentLine: String? = null,
    ): Built {
        val messages = ArrayList<ChatMessage>(history.size + 4)
        messages += ChatMessage.system(staticSystemPrefix(userAddress))

        val recent = history.takeLast(historyTurns * 2)
        if (recent.isNotEmpty()) {
            var budget = maxHistoryChars
            val kept = ArrayDeque<MessageEntity>()
            for (m in recent.asReversed()) {
                if (m.content.length > budget) break
                budget -= m.content.length
                kept.addFirst(m)
            }
            kept.forEach { m ->
                // **崩坏的历史不能回灌。**
                // 她输出乱码那次已经存进库了；如果原样发回给模型，
                // 它会看到自己上一句就在崩，然后沿着那个模式继续崩（实测就这样）。
                if (m.role == "assistant" && OutputHygiene.isGarbled(m.content)) return@forEach

                when (m.role) {
                    // 图片/视频/文件/表情 → 转成一句描述，让她能接着聊
                    "user" -> messages += ChatMessage.user(MediaPrompt.line(m))
                    "assistant" -> messages += ChatMessage.assistant(MediaPrompt.line(m))
                    else -> Unit
                }
            }
        }

        val context = buildString {
            // 现在是什么时候 —— 放最前面：她得先知道"此刻"，别的信息才有意义
            timeLine?.takeIf { it.isNotBlank() }?.let {
                appendLine("〔现在〕")
                appendLine(it)
            }
            // 她此刻的情绪（她自己的一天 + 相处中真发生的事）→ 决定她**怎么说话**
            moodLine?.takeIf { it.isNotBlank() }?.let { appendLine("〔你现在的心境〕$it") }
            // 她对他的不满（**只有他的行为**能加）→ 决定她**回不回**
            resentmentLine?.takeIf { it.isNotBlank() }?.let { appendLine("〔你对他〕$it") }
            // 心情**只注入一次**。优先用新的情绪系统（moodLine）；
            // 旧的 herMood 只在没有新值时兜底 —— 两条同时注入会互相矛盾，
            // 模型会不知道该照哪个来（实测踩过）。
            if (moodLine.isNullOrBlank()) {
                herMood?.takeIf { it.isNotBlank() }?.let {
                    appendLine("〔你现在的心情〕$it（照这个心情说话，别直接说出来）")
                }
            }
            // 她懂的那点事 —— 命中才给（KnowledgeEngine 决定给不给），默认不注入
            knowledgeLine?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
            daySummaryLine?.takeIf { it.isNotBlank() }?.let { appendLine("〔关于昨天/前几天〕$it") }
            if (memoryLines.isNotEmpty()) {
                appendLine("〔你记得的、和这次可能有关的事〕")
                memoryLines.forEach { appendLine("- $it") }
            }
            if (userAddress.isNotBlank() && userAddress != "你") {
                appendLine("〔对方希望你叫他〕$userAddress")
            }
        }.trim()

        if (context.isNotEmpty()) {
            messages += ChatMessage.system(
                "以下是你的长期记忆里、和这次对话可能相关的片段。只在自然的时候提起，" +
                    "不要生硬地罗列，也不要为了用而用；不相关就当没看见。\n\n$context",
            )
        }

        messages += ChatMessage.user(currentUserText)
        return Built(messages = messages, usedMemoryIds = memories.map { it.first })
    }

    /** 主动消息：她先开口，没有"用户当前那句"。 */
    fun buildProactive(
        userAddress: String,
        memoryLines: List<String>,
        history: List<MessageEntity>,
        situationLine: String,
        herMood: String? = null,
        knowledgeLine: String? = null,
        timeLine: String? = null,
        moodLine: String? = null,
        resentmentLine: String? = null,
    ): Built = build(
        personaName = "",
        userAddress = userAddress,
        memoryLines = memoryLines,
        history = history,
        currentUserText = "（现在没有人对你说话。你自己看了一眼情况：$situationLine\n" +
            "你想开口找他说一句。就按你的性格说，短一点，别像通知。）",
        herMood = herMood,
        knowledgeLine = knowledgeLine,
        timeLine = timeLine,
        moodLine = moodLine,
        resentmentLine = resentmentLine,
    )
}
