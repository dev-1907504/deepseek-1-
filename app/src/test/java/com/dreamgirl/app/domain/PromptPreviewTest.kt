package com.dreamgirl.app.domain

import com.dreamgirl.app.data.MessageEntity
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 不是一个断言型测试，而是一个**人工审阅工具**：把模型真正会看到的 prompt
 * 原样导出到 /opt/dream/prompt-preview.txt，方便逐字检查人设有没有走样。
 */
class PromptPreviewTest {

    @Test
    fun `export assembled prompt for human review`() {
        val assembler = PromptAssembler()
        val history = listOf(
            MessageEntity(id = 1, sessionDate = "2026-10-11", role = "user", content = "在吗", createdAt = 1),
            MessageEntity(id = 2, sessionDate = "2026-10-11", role = "assistant", content = "（尾巴翘起来）在。", createdAt = 2),
        )
        val built = assembler.build(
            personaName = "",
            userAddress = "你",
            memoryLines = listOf("（昨天）他说这周要开始跑步"),
            memories = listOf(11L to "他说这周要开始跑步"),
            history = history,
            currentUserText = "今天跑了没",
            daySummaryLine = "昨天聊了跑步计划，他有点心虚",
        )

        val out = File("/opt/dream/prompt-preview.txt")
        out.writeText(
            buildString {
                appendLine("=========== 模型实际收到的 messages ===========")
                built.messages.forEachIndexed { i, m ->
                    appendLine("----- [$i] role=${m.role} (${m.content.length} 字) -----")
                    appendLine(m.content)
                    appendLine()
                }
            },
        )
        assertTrue("导出的 messages 至少要有 system + user", built.messages.size >= 2)
        assertTrue(out.exists())
    }
}
