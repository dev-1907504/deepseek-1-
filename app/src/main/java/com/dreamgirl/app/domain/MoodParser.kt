package com.dreamgirl.app.domain

import java.util.regex.Pattern

/**
 * 解析她的三段输出：
 *
 * ```
 * [心想] 他又问我在干嘛。上次问完就没影了……烦。不想接。
 * [嘴上] 在收拾本子呢。
 * [不回] 不想说。
 * ```
 *
 * | 标记 | 去哪 |
 * |---|---|
 * | `[心想]` | **不进 UI**，只回写情绪状态机 |
 * | `[嘴上]` | 正常气泡 —— 她真正说出口的那句 |
 * | `[不回]` | 居中灰色小字 —— **她的内心独白** |
 *
 * ---
 * ## 为什么要"宽容"（v0.21.1 重写）
 *
 * 实测过：模型**不会老老实实写 `[嘴上]`**。它会写成
 * `【嘴上】`、`**嘴上**：`、`[嘴上：]`、行首裸写 `嘴上：`……
 * 而旧解析器只认一种写法 —— 结果**标记原样泄漏到气泡里**，
 * 用户看到的就是"一堆不明所以的话"。
 *
 * 所以现在的顺序是：**先归一化，再解析，最后擦一遍**。
 * 擦不干净就宁可整条不发，也绝不让标记出现在她嘴里。
 */
object MoodParser {

    data class Parsed(
        /** 她真正说出口的（气泡里显示的）。多段时是拼起来的全文。 */
        val said: String,
        /** 她的内心独白 —— [silent] 为 true 时显示在灰字里。 */
        val inner: String?,
        /** 她这次**没有开口**。 */
        val silent: Boolean,
        /**
         * **她要发的几条消息。**
         *
         * 真人聊天不会把三句话塞进一条：他敲一句发一句。
         * 但**只有她明确用了 `[嘴上]` 标记时**才按行拆 ——
         * 否则一段正常的话会被切成一堆碎片（也实测踩过）。
         */
        val segments: List<String> = emptyList(),
    ) {
        /** 兼容旧调用。 */
        val text: String get() = said
    }

    /** 三个标记的候选词。 */
    private const val INNER_WORDS = "心想|心声|内心|心里"
    private const val SAID_WORDS = "嘴上|回复|开口|说出来"
    private const val SILENT_WORDS = "不回|沉默|没说话|不说话"

    private const val ALL_WORDS = "$INNER_WORDS|$SAID_WORDS|$SILENT_WORDS"

    /** 归一化之后，标记统一长成 `[心想]` 这样。 */
    private val CANON_INNER = Pattern.compile("""\[\s*($INNER_WORDS)\s*]\s*[:：]?\s*([\s\S]*?)(?=\n\s*\[|\z)""")
    private val CANON_SAID = Pattern.compile("""\[\s*($SAID_WORDS)\s*]\s*[:：]?\s*([\s\S]*?)(?=\n\s*\[|\z)""")
    private val CANON_SILENT = Pattern.compile("""\[\s*($SILENT_WORDS)\s*]\s*[:：]?\s*([\s\S]*?)(?=\n\s*\[|\z)""")

    /**
     * **漏网检测**：解析完还剩下这些形状，说明标记没被吃掉。
     *
     * 中括号/方括号/星号/圆括号包着的标记词，或者行首裸写的 `心想：`。
     */
    private val LEAK = Pattern.compile(
        """[\[\【\*（(]\s*(?:$ALL_WORDS)\s*[\]\】\*）):：]""" +
            """|(?m)^[ \t]*(?:$ALL_WORDS)\s*[:：]""",
    )

    /** 旧写法：末尾一行 `[心情:xxx]`。 */
    private val LEGACY_MOOD = Pattern.compile("""[\[【]\s*心情\s*[:：]\s*([^\]】\n]{1,20})\s*[\]】]""")

    /** 一行超过这个长度，就认为她在写散文，不能按行拆成多条。 */
    private const val PROSE_LINE_CHARS = 45

    /** 一次最多连发几条 —— 再多就像刷屏了。 */
    const val MAX_SEGMENTS = 5

    private val TAG_WORDS_REGEX = Regex("""(?:$ALL_WORDS)""")

    // ================================================================ 归一化

    /**
     * 把各种"写歪的"标记整理成规范的 `[心想]` / `[嘴上]` / `[不回]`。
     *
     * 顺序很重要：先处理带内容的（`[嘴上：内容]`），再处理只有标记的。
     */
    internal fun normalize(raw: String): String {
        var t = raw.replace("\r\n", "\n")

        // 【嘴上：内容】 / [嘴上：内容] → [嘴上] 内容（冒号写在括号里）
        t = Regex("""[\[【〔]\s*($ALL_WORDS)\s*[:：]\s*([\s\S]*?)\s*[\]】〕]""").replace(t) { m ->
            "[${m.groupValues[1]}] ${m.groupValues[2]}"
        }
        // 【嘴上】 / 〔嘴上〕 → [嘴上]
        t = Regex("""[\[【〔]\s*($ALL_WORDS)\s*[\]】〕]""").replace(t) { m ->
            "[${m.groupValues[1]}]"
        }
        // [嘴上：  /  [嘴上:  （只有开括号，冒号在后）
        t = Regex("""[\[【〔]\s*($ALL_WORDS)\s*[:：]\s*""").replace(t) { m ->
            "[${m.groupValues[1]}] "
        }
        // **嘴上** / **嘴上**： / *嘴上* → [嘴上]
        t = Regex("""\*{1,2}\s*($ALL_WORDS)\s*\*{0,2}\s*[:：]?""").replace(t) { m ->
            "[${m.groupValues[1]}]"
        }
        // （嘴上） / (嘴上) → [嘴上]
        t = Regex("""[（(]\s*($ALL_WORDS)\s*[)）]\s*[:：]?""").replace(t) { m ->
            "[${m.groupValues[1]}]"
        }
        // 行首裸写：嘴上：内容 → [嘴上] 内容
        t = Regex("""(?m)^[ \t]*($ALL_WORDS)\s*[:：]\s*""").replace(t) { m ->
            "[${m.groupValues[1]}] "
        }
        // 行首裸写但没冒号：`嘴上` 单独一行
        t = Regex("""(?m)^[ \t]*($ALL_WORDS)[ \t]*$""").replace(t) { m ->
            "[${m.groupValues[1]}]"
        }
        return t
    }

    /** 规范化之后的标记本身（`[心想]` 这种）。 */
    private val CANON_TAG = Pattern.compile("""\[\s*(?:$ALL_WORDS)\s*]\s*[:：]?""")

    /** 行首剩下的零星碎片（`*：`、`】` 之类）。 */
    private val LEADING_JUNK = Regex("""(?m)^[ 	]*[*\[\]【】:：][ 	*\[\]【】:：]{0,5}[ 	]*""")

    /**
     * 把任何残留的标记形状擦掉。**最后一道防线** —— 用户永远不该看到标记。
     *
     * 分三步：先去旧标记 → 再把写歪的标记规范化 → 再清掉行首的碎片。
     * 实测过：只做前两步会剩下 `*：` 这种渣，照样难看。
     */
    internal fun scrub(s: String): String {
        var t = LEGACY_MOOD.matcher(s).replaceAll("")
        t = normalize(t)
        t = CANON_TAG.matcher(t).replaceAll(" ")
        t = LEAK.matcher(t).replaceAll(" ")
        t = LEADING_JUNK.replace(t, "")
        return t
    }

    /** 擦完之后还有没有残留（用来判定"这条不能发"）。 */
    internal fun hasLeak(s: String): Boolean = LEAK.matcher(s).find()

    // ================================================================ 解析

    fun parse(raw: String): Parsed {
        val text = normalize(raw)

        val innerM = CANON_INNER.matcher(text)
        val inner = if (innerM.find()) innerM.group(2)?.trim()?.ifBlank { null } else null

        val saidM = CANON_SAID.matcher(text)
        val saidRaw = if (saidM.find()) saidM.group(2)?.trim().orEmpty() else ""
        val hadSaidTag = saidM.reset().find()

        val silentM = CANON_SILENT.matcher(text)
        val silentBody = if (silentM.find()) silentM.group(2)?.trim()?.ifBlank { null } else null

        val hadAnyTag = hadSaidTag ||
            CANON_INNER.matcher(text).find() ||
            CANON_SILENT.matcher(text).find()

        if (!hadAnyTag) {
            // 她没按格式写：整段就当她说的。**不分段** —— 没有标记就没有"一行一条"的依据。
            val cleaned = tidy(scrub(text))
            return Parsed(
                said = cleaned,
                inner = null,
                silent = false,
                segments = if (cleaned.isBlank()) emptyList() else listOf(cleaned),
            )
        }

        val isSilent = saidRaw.isBlank()
        val cleanedSaid = tidy(scrub(saidRaw))

        return Parsed(
            said = cleanedSaid,
            inner = when {
                isSilent -> (silentBody ?: inner?.lines()?.firstOrNull()?.trim())?.let { tidy(scrub(it)) }
                else -> inner?.let { tidy(scrub(it)) }
            },
            silent = isSilent || cleanedSaid.isBlank() && hadSaidTag,
            segments = if (isSilent) emptyList() else splitSegments(cleanedSaid, allowSplit = hadSaidTag),
        )
    }

    /**
     * **一行 = 一条消息** —— 但只在她说清楚了 `[嘴上]` 的前提下。
     *
     * 没有标记时绝不拆：实测过一段连贯的三行散文被切成三个气泡，
     * 看起来就像她"崩了"。散文（某行特别长）也不拆。
     */
    internal fun splitSegments(said: String, allowSplit: Boolean): List<String> {
        val trimmed = said.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (!allowSplit) return listOf(trimmed)

        val lines = trimmed.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size <= 1) return lines.ifEmpty { listOf(trimmed) }

        // 某行太长 → 这是散文，不是连发的短消息 → 整段当一条
        if (lines.any { it.length > PROSE_LINE_CHARS }) return listOf(trimmed)

        // 平均长度也偏长 → 同样当散文
        if (lines.sumOf { it.length } / lines.size > PROSE_LINE_CHARS / 2) return listOf(trimmed)

        return lines.take(MAX_SEGMENTS)
    }

    // ================================================================ 流式

    /**
     * 流式展示：把标记和没收尾的半截都藏掉，只留下已经说出口的部分。
     */
    fun stripForDisplay(raw: String): String {
        val t = normalize(raw)

        val saidM = CANON_SAID.matcher(t)
        if (saidM.find()) {
            // 已经出现 [嘴上]：只显示最后正在敲的那一条
            val body = scrub(saidM.group(2) ?: "")
            return body.split('\n').map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty()
        }

        // 出现了别的标记但还没有 [嘴上] → 她可能不打算开口，先什么都不显示
        if (CANON_INNER.matcher(t).find() || CANON_SILENT.matcher(t).find()) return ""

        // 什么都没有 → 可能是正在写标记的半截，也可能是旧式单段输出
        val open = t.lastIndexOf('[')
        if (open >= 0) {
            val tail = t.substring(open)
            if (!tail.contains(']') && tail.length <= 10 && looksLikeTagPrefix(tail)) {
                return tidy(scrub(t.substring(0, open)))
            }
        }
        return tidy(scrub(t))
    }

    /** `[心`、`[嘴` 这种是不是标记的开头。 */
    private fun looksLikeTagPrefix(fragment: String): Boolean {
        val body = fragment.removePrefix("[").removePrefix("【").trimStart()
        if (body.isEmpty()) return true
        if (TAG_WORDS_REGEX.matchEntire(body) != null) return true
        return listOf(INNER_WORDS, SAID_WORDS, SILENT_WORDS, "心情")
            .flatMap { it.split("|") }
            .any { it.startsWith(body) }
    }

    /** 去掉标记后可能留下空行，收拾干净。 */
    private fun tidy(s: String): String {
        val lines = s.split('\n')
        val kept = ArrayList<String>(lines.size)
        var blankRun = 0
        for (line in lines) {
            if (line.isBlank()) {
                blankRun++
                if (blankRun > 1) continue
                kept.add("")
            } else {
                blankRun = 0
                kept.add(line.trimEnd())
            }
        }
        return kept.joinToString("\n").trim()
    }
}
