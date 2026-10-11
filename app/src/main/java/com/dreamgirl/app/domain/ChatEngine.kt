package com.dreamgirl.app.domain

import com.dreamgirl.app.data.ApiKeyProvider
import com.dreamgirl.app.data.DaySessionDao
import com.dreamgirl.app.data.DaySummaryDao
import com.dreamgirl.app.data.MessageDao
import com.dreamgirl.app.data.DaySessionEntity
import com.dreamgirl.app.data.DayClock
import com.dreamgirl.app.data.MessageEntity
import com.dreamgirl.app.data.AppSettingsProvider
import com.dreamgirl.app.data.InnerStateSnapshot
import com.dreamgirl.app.llm.ChatCompletionRequest
import com.dreamgirl.app.llm.LlmApi
import com.dreamgirl.app.llm.LlmErrorKind
import com.dreamgirl.app.llm.LlmEvent
import com.dreamgirl.app.llm.LlmException
import com.dreamgirl.app.llm.TokenUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

/**
 * 一次对话的完整编排（方案 §4「一次对话的流程」）。
 *
 *   你发一句
 *    → PromptAssembler 拼装
 *    → 流式请求 DeepSeek
 *    → 逐 token 渲染
 *    → 后处理（跳出角色/超长）
 *    → 写回 Room
 *    → 抽取记忆
 */
class ChatEngine(
    private val messageDao: MessageDao,
    private val sessionDao: DaySessionDao,
    private val summaryDao: DaySummaryDao,
    private val client: LlmApi,
    private val settings: AppSettingsProvider,
    private val apiKeyStore: ApiKeyProvider,
    private val memoryEngine: MemoryEngine,
    private val assembler: PromptAssembler = PromptAssembler(),
) {
    sealed interface ChatEvent {
        data class UserSaved(val message: MessageEntity) : ChatEvent
        data class Delta(val text: String) : ChatEvent

        /** 上一次尝试失败了、正在自动重来：UI 应把已经流出来的半截文字清掉。 */
        data object Restarting : ChatEvent
        data class Finished(
            val message: MessageEntity,
            val post: PostProcessor.Result,
            val usedMemoryIds: List<Long>,
        ) : ChatEvent

        data class Failed(val error: LlmException, val partial: String) : ChatEvent

        /**
         * 她这回**没开口**，但你看得到她心里那句（居中灰字）。
         * 不回是"对他不满"的结果，不是心情差 —— 心情差她照样会应他。
         */
        data class Silent(
            val message: MessageEntity,
            val inner: String,
        ) : ChatEvent
    }

    private val sendLock = Mutex()

    /** 她连发两条之间的停顿（毫秒）—— 太快像机器，太慢像卡了。 */
    private val SEGMENT_GAP_MS = 600L
    private var lastExtractAt = 0L

    /** 保证当天会话存在（方案 §6.1 跨天自动开新会话）。 */
    suspend fun ensureSession(date: LocalDate = DayClock.today()): DaySessionEntity {
        val key = date.toString()
        val existing = sessionDao.byDate(key)
        if (existing != null) return existing
        val now = System.currentTimeMillis()
        val fresh = DaySessionEntity(date = key, startedAt = now, lastMessageAt = now, messageCount = 0)
        sessionDao.upsert(fresh)
        return fresh
    }

    fun send(userText: String, quote: MessageEntity? = null): Flow<ChatEvent> = sendInternal(
        promptText = userText.trim(),
        displayContent = userText.trim(),
        kind = com.dreamgirl.app.data.MessageEntity.KIND_TEXT,
        mediaPath = null,
        quote = quote,
    )

    /**
     * 发一张图片 / 一段视频 / 一个文件 / 一个表情。
     *
     * 她看不见文件内容，所以进 prompt 的是一句描述（"他发来一张图片"），
     * 她就能自然地接话。
     */
    fun sendMedia(
        kind: String,
        mediaPath: String,
        displayName: String,
    ): Flow<ChatEvent> = sendInternal(
        promptText = MediaPrompt.describe(
            kind,
            MediaPrompt.Who.USER,
            displayName,
        ),
        displayContent = displayName,
        kind = kind,
        mediaPath = mediaPath,
    )

    private fun sendInternal(
        promptText: String,
        displayContent: String,
        kind: String,
        mediaPath: String?,
        quote: MessageEntity? = null,
    ): Flow<ChatEvent> = flow {
        val text = promptText.trim()
        if (text.isEmpty()) return@flow
        sendLock.withLock {
            val cfg = settings.current()
            val today = DayClock.today()
            val dateKey = today.toString()
            ensureSession(today)

            // 她现在的内心状态：心境（决定怎么说）+ 对他的不满（决定回不回）
            val stateBefore = settings.innerState()

            val now = System.currentTimeMillis()
            val userMsg = MessageEntity(
                sessionDate = dateKey,
                role = "user",
                content = displayContent,
                createdAt = now,
                kind = kind,
                mediaPath = mediaPath,
                quoteId = quote?.id,
                quoteText = quote?.let { quoteSnippet(it) },
            )
            val userId = messageDao.insert(userMsg)
            bumpSession(dateKey, now, 1)
            emit(ChatEvent.UserSaved(userMsg.copy(id = userId)))

            // 检索记忆 + 昨天的总结
            val memories = runCatching { memoryEngine.retrieve(text) }.getOrDefault(emptyList())
            val yesterdayLine = summaryDao.byDate(today.minusDays(1).toString())?.l1
            val history = messageDao.byDate(dateKey).filter { it.id != userId }

            // 她自己的时间感：现在几点、隔了多久没说话
            val timeLine = timeSenseLine(history)

            // 她此刻的心境 / 对他的不满 —— **两个独立的量**，分开注入
            val mood = EmotionState.decay(
                EmotionState.Mood(
                    stateBefore.moodLabel, stateBefore.moodValence, stateBefore.moodAt,
                ),
                now,
            )
            val moodLine = EmotionState.describe(mood).orEmpty()
            val resentmentLine = EmotionState.describeResentment(
                EmotionState.decayResentment(
                    EmotionState.Resentment(
                        stateBefore.resentmentLevel,
                        stateBefore.resentmentReason,
                        stateBefore.resentmentAt,
                    ),
                    now,
                ),
            ).orEmpty()

            // 她这回理不理他 —— 只有不满能让她不说话
            val decision = EmotionState.decide(
                EmotionState.decayResentment(
                    EmotionState.Resentment(
                        stateBefore.resentmentLevel,
                        stateBefore.resentmentReason,
                        stateBefore.resentmentAt,
                    ),
                    now,
                ),
                stateBefore.silentStreak,
                dice = java.util.Random().nextDouble(),
            )

            // 他引用了哪一句 —— 这件事必须让她知道，否则"引用"就只是个 UI 装饰
            val quoteNote = quote?.let {
                val who = if (it.role == "user") "他自己" else "你自己"
                "（他这句话是接着$who 之前那句说的：「${quoteSnippet(it)}」）\n"
            }.orEmpty()

            val built = assembler.build(
                personaName = "",
                userAddress = cfg.userAddress,
                memoryLines = memoryEngine.renderForPrompt(memories, today),
                memories = memories.map { it.id to it.content },
                history = history,
                currentUserText = quoteNote + text,
                daySummaryLine = yesterdayLine,
                herMood = cfg.liveMood,
                // 她懂的那点事：撞上了才给（撞不上就一个 token 都不花）
                knowledgeLine = knowledgeLineFor(text, cfg.liveMood),
                timeLine = timeLine,
                moodLine = moodLine,
                resentmentLine = if (decision.silent) {
                    resentmentLine + "\n（你这次不打算开口，只写 [心想] 和 [不回]。）"
                } else resentmentLine,
            )

            emitReply(
                request = ChatCompletionRequest(
                    model = pickModel(cfg, text),
                    messages = built.messages,
                    stream = true,
                    temperature = cfg.temperature,
                    maxTokens = cfg.maxTokens,
                ),
                dateKey = dateKey,
                cfg = cfg,
                usedMemoryIds = built.usedMemoryIds,
                text0 = text,
            )
        }
    }.flowOn(Dispatchers.IO)

    /** 重发上一条：把最后一条 assistant 删掉再走一遍。 */
    fun regenerate(): Flow<ChatEvent> = flow {
        sendLock.withLock {
            val today = DayClock.today()
            val dateKey = today.toString()
            val all = messageDao.byDate(dateKey)
            val lastAssistant = all.lastOrNull { it.role == "assistant" }
            val lastUser = all.lastOrNull { it.role == "user" }
            if (lastUser == null) return@flow
            if (lastAssistant != null && lastAssistant.createdAt > lastUser.createdAt) {
                messageDao.deleteById(lastAssistant.id)
            }
            val cfg = settings.current()
            val memories = runCatching { memoryEngine.retrieve(lastUser.content) }.getOrDefault(emptyList())
            val history = messageDao.byDate(dateKey).filter { it.id != lastUser.id }
            val timeLine = timeSenseLine(history)
            val built = assembler.build(
                personaName = "",
                userAddress = cfg.userAddress,
                memoryLines = memoryEngine.renderForPrompt(memories, today),
                memories = memories.map { it.id to it.content },
                history = history,
                currentUserText = lastUser.content,
                daySummaryLine = summaryDao.byDate(today.minusDays(1).toString())?.l1,
                herMood = cfg.liveMood,
                knowledgeLine = knowledgeLineFor(lastUser.content, cfg.liveMood),
                timeLine = timeLine,
            )
            emitReply(
                request = ChatCompletionRequest(
                    model = cfg.chatModel,
                    messages = built.messages,
                    stream = true,
                    temperature = cfg.temperature,
                    maxTokens = cfg.maxTokens,
                ),
                dateKey = dateKey,
                cfg = cfg,
                usedMemoryIds = built.usedMemoryIds,
            )
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 把这一轮的结果回写进她的内心状态。
     *
     * **有来处**：情绪只被白名单事件改动；**不满只有他的行为能加**。
     * 这里也负责在他"敷衍"之后给她攒一点气 —— 但那是另一条链路
     * （见 [offenceOf]），不是每句都加。
     */
    private suspend fun updateInner(
        before: InnerStateSnapshot,
        parsed: MoodParser.Parsed,
        dateKey: String,
    ): InnerStateSnapshot {
        val now = System.currentTimeMillis()

        // 情绪：她自己写的那句内心独白就是来处；没有就按时间回落
        val mood0 = EmotionState.Mood(before.moodLabel, before.moodValence, before.moodAt)
        val cause = if (parsed.inner.isNullOrBlank()) EmotionState.Cause.TIME_PASSED
        else EmotionState.Cause.HER_OWN_DAY
        val mood = EmotionState.apply(
            mood0,
            cause,
            label = parsed.inner?.replace("\n", " ")?.take(20).orEmpty(),
            intensity = 0.5,
            now = now,
        )

        val resent0 = EmotionState.Resentment(
            before.resentmentLevel, before.resentmentReason, before.resentmentAt,
        )
        val resent = EmotionState.decayResentment(resent0, now)

        val after = before.copy(
            moodLabel = mood.label,
            moodValence = mood.valence,
            moodAt = mood.at,
            resentmentLevel = resent.level,
            resentmentReason = resent.reason,
            resentmentAt = resent.at,
        )
        runCatching { settings.saveInnerState(after) }
        // 兼容：旧的 liveMood 仍写一份，资料头还在用
        mood.label.takeIf { it.isNotBlank() }?.let { runCatching { settings.setLiveMood(it) } }
        return after
    }

    /**
     * 他这句话算不算"冒犯"。
     *
     * **很窄**：只有敷衍、说重话、拿她当垃圾桶才算。
     * 他自己忙、他没心情、他说了句丧气话 —— **都不算**，那些不减她的爱。
     */
    internal fun offenceOf(text: String): EmotionState.Offence? {
        val t = text.trim()
        if (t.isEmpty()) return null
        // 敷衍：很短且没内容
        if (t.length <= 3 && t in DISMISSIVE) return EmotionState.Offence.DISMISSIVE
        // 冲着她来的
        if (HURT_WORDS.any { t.contains(it) }) return EmotionState.Offence.HURT
        // 只顾自己倒
        if (t.length > 200 && t.count { it == '我' } >= 6) return EmotionState.Offence.DUMPING
        return null
    }

    private val DISMISSIVE = setOf("嗯", "哦", "哦哦", "随便", "都行", "呵呵", "知道了", "行", "好")
    private val HURT_WORDS = listOf("烦死了", "别烦我", "你很烦", "关你什么事", "用不着你", "少管", "闭嘴")

    /**
     * 她自己的时间感。
     *
     * [history] 里没有他的消息 = 今天他还没开口（隔夜的情况由 TimeSense 按会话日算）。
     * 这里取**他**最后一句的时间，而不是消息列表里最后一条
     * —— 否则她刚说完话，就会以为"刚刚才聊过"。
     */
    private fun timeSenseLine(history: List<MessageEntity>): String =
        runCatching {
            val now = System.currentTimeMillis()
            val lastFromHim = history.lastOrNull { it.role == "user" }?.createdAt
            TimeSense.of(now, lastFromHim).line
        }.getOrNull().orEmpty()

    /**
     * 她懂的那点事：拿他这句话去翻卡。
     * 撞不上就返回 null —— **默认不注入**（宁可不用，也别硬塞一张不相关的）。
     */
    private fun knowledgeLineFor(text: String, mood: String?): String? =
        runCatching { KnowledgeEngine.renderForPrompt(KnowledgeEngine.retrieve(text, mood)) }.getOrNull()

    /**
     * 她今天心里惦记着的那张卡。
     * 一天只翻一次、同一个心情翻到同一张 —— 这样她这一天有个一贯的心境，
     * 而不是每句话都在换一个人。04:00 之后（新的会话日）自然换。
     */
    private fun cardOfTheDayLine(dateKey: String, mood: String?): String? =
        runCatching {
            val card = KnowledgeEngine.cardOfTheDay(dateKey, mood) ?: return@runCatching null
            "〔你今天心里一直搁着的一件事〕${card.id}：${card.rule}\n" +
                "（今天就照你自己的这个心境过，别直接说出来。）"
        }.getOrNull()

    /**
     * 方案 §5：日常用 deepseek-chat；只有用户真的在纠结一件事、
     * 且他自己开了这个开关时，才切到 deepseek-reasoner。
     */
    private fun pickModel(cfg: com.dreamgirl.app.data.AppSettings, userText: String): String =
        if (cfg.useReasonerForHardQuestions && QuestionClassifier.isHardQuestion(userText)) {
            cfg.reasonerModel
        } else {
            cfg.chatModel
        }

    /** 她主动开口（方案 §4 第二条流程）。 */
    fun proactive(situationLine: String): Flow<ChatEvent> = flow {
        sendLock.withLock {
            val cfg = settings.current()
            val today = DayClock.today()
            val dateKey = today.toString()
            ensureSession(today)
            val history = messageDao.byDate(dateKey)
            val built = assembler.buildProactive(
                userAddress = cfg.userAddress,
                memoryLines = emptyList(),
                history = history,
                situationLine = situationLine,
                herMood = cfg.liveMood,
                knowledgeLine = cardOfTheDayLine(dateKey, cfg.liveMood),
                timeLine = timeSenseLine(history),
            )
            emitReply(
                request = ChatCompletionRequest(
                    model = cfg.chatModel,
                    messages = built.messages,
                    stream = true,
                    temperature = cfg.temperature,
                    maxTokens = cfg.maxTokens,
                ),
                dateKey = dateKey,
                cfg = cfg,
                usedMemoryIds = emptyList(),
                proactive = true,
            )
        }
    }.flowOn(Dispatchers.IO)

    // ------------------------------------------------------------------

    private suspend fun kotlinx.coroutines.flow.FlowCollector<ChatEvent>.emitReply(
        request: ChatCompletionRequest,
        dateKey: String,
        cfg: com.dreamgirl.app.data.AppSettings,
        usedMemoryIds: List<Long>,
        proactive: Boolean = false,
        text0: String? = null,
        stateBefore: InnerStateSnapshot = InnerStateSnapshot(),
    ) {
        val sb = StringBuilder()
        var usage: TokenUsage? = null
        var error: LlmException? = null

        // 断线重试 + 连续失败自动退避（方案 §5「降级」）。
        // 只在「一个字都还没吐出来」时重试——已经吐了一半再重来会让气泡里出现重复的话。
        var attempt = 0
        var backoffMs = 0L
        while (true) {
            sb.setLength(0)
            error = null
            if (attempt > 0) {
                delay(backoffMs)
                emit(ChatEvent.Restarting)
            }
            client.streamChat(request, cfg.baseUrl).collect { ev ->
                when (ev) {
                    is LlmEvent.Delta -> {
                        sb.append(ev.text)
                        emit(ChatEvent.Delta(ev.text))
                    }

                    is LlmEvent.Completed -> usage = ev.usage
                    is LlmEvent.Failed -> error = ev.error
                    is LlmEvent.Reasoning -> Unit
                }
            }
            val e = error
            if (e == null) break
            if (!e.retryable || attempt >= MAX_RETRIES) break
            attempt++
            backoffMs = RETRY_BASE_DELAY_MS * attempt
        }

        val fatal = error
        if (fatal != null && sb.isEmpty()) {
            emit(ChatEvent.Failed(fatal, ""))
            persistError(dateKey, fatal, proactive)
            return
        }

        // 拆她的三段输出：心想 / 嘴上 / 不回
        val parsed = MoodParser.parse(sb.toString())
        updateInner(stateBefore, parsed, dateKey)

        // 她这次没开口 —— 存一条"内心独白"，不进气泡
        if (parsed.silent) {
            val inner = parsed.inner.orEmpty().ifBlank { "……" }
            val msg = MessageEntity(
                sessionDate = dateKey,
                role = "assistant",
                content = inner,
                createdAt = System.currentTimeMillis(),
                proactive = proactive,
                kind = MessageEntity.KIND_INNER,
            )
            val id = messageDao.insert(msg)
            bumpSession(dateKey, msg.createdAt, 0)
            settings.bumpSilentStreak()
            emit(ChatEvent.Silent(msg.copy(id = id), inner))
            return
        }
        settings.resetSilentStreak()

        var text = parsed.said
        // 她这一轮有没有可依据的记忆 —— 没有的话，"你上次说过"必然是编的
        var post = PostProcessor.process(
            text, text0, cfg.replyCharLimit,
            memorySupported = usedMemoryIds.isNotEmpty(),
        )

        // 跳出角色 → 重写一次（方案 §2.2 行为原则 6）
        if (post.needsRewrite && apiKeyStore.hasKey) {
            val retryMessages = request.messages + listOf(
                com.dreamgirl.app.llm.ChatMessage.assistant(text),
                com.dreamgirl.app.llm.ChatMessage.user(PostProcessor.rewriteInstruction(post.reasons)),
            )
            val retry = StringBuilder()
            var retryError: LlmException? = null
            emit(ChatEvent.Restarting)
            client.streamChat(
                request.copy(messages = retryMessages, temperature = (cfg.temperature - 0.2).coerceAtLeast(0.3)),
                cfg.baseUrl,
            ).collect { ev ->
                when (ev) {
                    is LlmEvent.Delta -> {
                        retry.append(ev.text)
                        emit(ChatEvent.Delta(ev.text))
                    }

                    is LlmEvent.Completed -> usage = ev.usage ?: usage
                    is LlmEvent.Failed -> retryError = ev.error
                    is LlmEvent.Reasoning -> Unit
                }
            }
            if (retryError == null && retry.isNotBlank()) {
                val reparsed = MoodParser.parse(retry.toString())
                if (!reparsed.silent && reparsed.said.isNotBlank()) {
                    updateInner(stateBefore, reparsed, dateKey)
                    text = reparsed.said
                    post = PostProcessor.process(text, text0, cfg.replyCharLimit)
                }
            }
        }

        if (text.isBlank()) {
            emit(ChatEvent.Failed(LlmException(LlmErrorKind.PARSE_ERROR, "她这次什么都没说"), ""))
            return
        }

        // **真人敲一句发一句。** 把她的回复按行拆成几条，逐条落库 ——
        // 每条一个气泡，中间隔一点点时间，看起来就像她在连发。
        // 不这么做的话，三句话挤成一个大气泡，是最明显的"AI 味"。
        val segments = if (parsed.segments.size > 1) {
            parsed.segments.map { seg -> PostProcessor.process(seg, text0, cfg.replyCharLimit).text }
                .filter { it.isNotBlank() }
        } else {
            listOf(post.text)
        }

        // **最后一道防线**：标记绝不允许出现在她嘴里。
        // 解析器已经归一化+擦过一遍；这里再验一次 —— 万一还有残留，
        // 宁可这条不发，也不能让用户看到「**嘴上**：」这种东西。
        val finalSegments = segments
            .map { MoodParser.scrub(it).trim() }
            .filter { it.isNotBlank() && !MoodParser.hasLeak(it) }
            .ifEmpty { listOf(post.text).map { MoodParser.scrub(it).trim() }.filter { it.isNotBlank() } }

        if (finalSegments.isEmpty()) {
            emit(
                ChatEvent.Failed(
                    LlmException(LlmErrorKind.PARSE_ERROR, "她这次的话没说出来（格式乱了）"),
                    "",
                ),
            )
            return
        }

        var last: MessageEntity? = null
        finalSegments.forEachIndexed { idx, seg ->
            if (idx > 0) {
                // 连发的间隔：第一句发出去、想一下、再补一句
                delay(SEGMENT_GAP_MS)
            }
            val m = MessageEntity(
                sessionDate = dateKey,
                role = "assistant",
                content = seg,
                createdAt = System.currentTimeMillis(),
                proactive = proactive,
                interrupted = error != null,
                // token 只记第一条，免得统计重复
                promptTokens = if (idx == 0) usage?.promptTokens ?: 0 else 0,
                completionTokens = if (idx == 0) usage?.completionTokens ?: 0 else 0,
            )
            val id = messageDao.insert(m)
            bumpSession(dateKey, m.createdAt, 0)
            last = m.copy(id = id)
        }

        last?.let { emit(ChatEvent.Finished(it, post, usedMemoryIds)) }

        // 后台抽记忆（节流，失败不影响主流程）
        maybeExtractMemories(dateKey)
    }

    // ---------------------------------------------------------------- 长按菜单

    /** 删一条消息。 */
    suspend fun deleteMessage(id: Long) {
        val m = messageDao.byId(id) ?: return
        messageDao.deleteById(id)
        adjustSessionCount(m)
    }

    /** 批量删。 */
    suspend fun deleteMessages(ids: List<Long>) {
        if (ids.isEmpty()) return
        val affected = ids.mapNotNull { runCatching { messageDao.byId(it) }.getOrNull() }
        messageDao.deleteByIds(ids)
        affected.forEach { adjustSessionCount(it) }
    }

    /**
     * 把删掉的消息写回去（撤销）。
     *
     * 用原来的 id 重新插入 —— 引用它的那些消息的 quoteId 才不会指向空。
     */
    suspend fun restoreMessage(m: MessageEntity) {
        messageDao.insert(m.copy(id = m.id))
        bumpSession(m.sessionDate, m.createdAt, 1)
    }

    /** 删完之后把会话的条数减掉，免得列表上显示的数字对不上。 */
    private suspend fun adjustSessionCount(m: MessageEntity) {
        val s = sessionDao.byDate(m.sessionDate) ?: return
        sessionDao.upsert(s.copy(messageCount = (s.messageCount - 1).coerceAtLeast(0)))
    }

    /** 引用的原文快照：只用于显示和进 prompt，截断到 120 字。 */
    private fun quoteSnippet(m: MessageEntity): String {
        val body = when {
            m.content.isNotBlank() -> m.content
            m.kind == com.dreamgirl.app.data.MessageEntity.KIND_IMAGE -> "[图片]"
            m.kind == com.dreamgirl.app.data.MessageEntity.KIND_VIDEO -> "[视频]"
            m.kind == com.dreamgirl.app.data.MessageEntity.KIND_FILE -> "[文件]"
            m.kind == com.dreamgirl.app.data.MessageEntity.KIND_STICKER -> "[表情]"
            else -> ""
        }
        return body.replace("\n", " ").take(120)
    }

    private suspend fun persistError(dateKey: String, error: LlmException, proactive: Boolean) {
        messageDao.insert(
            MessageEntity(
                sessionDate = dateKey,
                role = "assistant",
                content = "",
                createdAt = System.currentTimeMillis(),
                proactive = proactive,
                errorKind = error.kind.name,
            ),
        )
    }

    private suspend fun bumpSession(dateKey: String, at: Long, addedMessages: Int) {
        val cur = sessionDao.byDate(dateKey)
        val count = if (addedMessages > 0) {
            (cur?.messageCount ?: 0) + addedMessages
        } else {
            messageDao.countByDate(dateKey)
        }
        sessionDao.upsert(
            DaySessionEntity(
                date = dateKey,
                startedAt = cur?.startedAt ?: at,
                lastMessageAt = at,
                messageCount = count,
            ),
        )
    }

    private suspend fun maybeExtractMemories(dateKey: String) {
        val now = System.currentTimeMillis()
        if (now - lastExtractAt < EXTRACT_THROTTLE_MS) return
        lastExtractAt = now
        runCatching {
            val recent = messageDao.latestByDate(dateKey, 12).sortedBy { it.createdAt }
            if (recent.size >= 2) memoryEngine.extractQuick(recent, LocalDate.parse(dateKey))
        }
    }

    companion object {
        private const val EXTRACT_THROTTLE_MS = 15 * 60_000L

        /** 网络抖动最多自动重来 2 次（共 3 次尝试）。 */
        private const val MAX_RETRIES = 2
        private const val RETRY_BASE_DELAY_MS = 1_200L
    }
}
