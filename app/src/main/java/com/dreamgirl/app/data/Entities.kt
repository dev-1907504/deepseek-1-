package com.dreamgirl.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 一条消息。sessionDate 是按 04:00 切出来的会话日，用于「按天回看」。 */
@Entity(
    tableName = "messages",
    indices = [Index("sessionDate"), Index("createdAt")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionDate: String,
    /** user / assistant / system */
    val role: String,
    val content: String,
    val createdAt: Long,
    /**
     * 消息类型。默认纯文字；她和我都能发表情，我也能给她发图片/视频/文件。
     * 用单独一列而不是在 content 里塞标记 —— 否则渲染和检索都要靠字符串猜。
     */
    val kind: String = KIND_TEXT,
    /** 媒体文件在 App 私有目录里的相对路径（kind != text 时有效）。 */
    val mediaPath: String? = null,
    /** 她是否主动开的口（主动消息）。 */
    val proactive: Boolean = false,
    /** 流式被中断时置位，UI 上给个标记。 */
    val interrupted: Boolean = false,
    /** 出错时的分类，正常消息为 null。 */
    val errorKind: String? = null,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    /**
     * 引用的那条消息 id。null = 没引用。
     *
     * 只存 id 会有一个问题：被引用的消息删了之后，引用就变成空白。
     * 所以同时存一份 [quoteText] 快照 —— 引用是"我当时回了哪句"的事实，
     * 就算原消息没了，这个事实也该留着。
     */
    val quoteId: Long? = null,
    /** 被引用那句的原文快照（截断到 120 字，只为显示）。 */
    val quoteText: String? = null,
) {
    val isText: Boolean get() = kind == KIND_TEXT
    val isMedia: Boolean get() = kind != KIND_TEXT

    companion object {
        const val KIND_TEXT = "text"
        const val KIND_IMAGE = "image"
        const val KIND_VIDEO = "video"
        const val KIND_FILE = "file"

        /** 她的内心独白 —— 她这回没开口，但你看得到她心里那句（居中灰字）。 */
        const val KIND_INNER = "inner"
        const val KIND_STICKER = "sticker"
    }
}

/** 一天的会话。 */
@Entity(tableName = "day_sessions")
data class DaySessionEntity(
    @PrimaryKey val date: String,
    val startedAt: Long,
    val lastMessageAt: Long,
    val messageCount: Int,
)

/**
 * 当日总结：三层结构（方案 §6.2）。
 * L1 一句话；L2 要点（JSON 数组）；L3 细节（JSON 数组）。
 */
@Entity(tableName = "day_summaries")
data class DaySummaryEntity(
    @PrimaryKey val date: String,
    val l1: String,
    val l2Json: String,
    val l3Json: String,
    /** 他这天的状态（给检索/看点用）。 */
    val mood: String? = null,
    /** **她**那天的心情，用她的口吻。资料头显示的就是这个 —— 由她自己写，不是用户设的。 */
    val herMood: String? = null,
    val generatedAt: Long,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    /** 用户手动改过就不再被自动覆盖。 */
    val userEdited: Boolean = false,
    /**
     * 这天识别出、因而**没有记下来**的敏感条目数（方案 §6.3「识别到就跳过并在 UI 上标注」）。
     * 只记数量，绝不记内容 —— 否则"跳过"就没意义了。
     */
    val skippedCount: Int = 0,
)

/** 长期记忆条目：从日总结里抽出来、可检索的细节。 */
@Entity(
    tableName = "memory_items",
    indices = [Index("sourceDate"), Index("weight")],
)
data class MemoryItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceDate: String,
    val content: String,
    /** 逗号分隔的标签：人名、事件、情绪词。 */
    val tags: String = "",
    /** 0..1，越高越重要。 */
    val weight: Float = 0.5f,
    /** L2 / L3，来源层级，方便 UI 分组。 */
    val layer: Int = 2,
    val createdAt: Long,
    val lastReferencedAt: Long = 0,
    /** 敏感信息被跳过的条目不会存进来，这里只留审计用。 */
    val skipped: Boolean = false,
)

/** 人设：默认值来自 PersonaVerbatim（只读原文），用户改了之后存这里。 */
@Entity(tableName = "persona")
data class PersonaEntity(
    @PrimaryKey val id: Int = 1,
    val name: String,
    val image: String,
    val character: String,
    val insight: String,
    val faith: String,
    val speech: String,
    val principles: String,
    /** 用户是否改过。false = 与方案原文逐字一致。 */
    val customized: Boolean = false,
    val updatedAt: Long,
)

/** 月记压缩：超过 90 天的日总结合并成一条，控制体积（方案 §6.5）。 */
@Entity(tableName = "month_digests")
data class MonthDigestEntity(
    @PrimaryKey val month: String,
    val l1: String,
    val l2Json: String,
    val generatedAt: Long,
    val coveredDays: Int,
)
