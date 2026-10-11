package com.dreamgirl.app.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 这组测试是「人格原文一字不改」的**构建期防线**：
 * 任何人手改了 PersonaVerbatim.kt，这里立刻变红。
 */
class PersonaVerbatimTest {

    @Test
    fun `all seven segments are non empty`() {
        PersonaVerbatim.ORDER.forEach { segment ->
            assertTrue("段落不能为空", segment.isNotBlank())
        }
        assertEquals(7, PersonaVerbatim.ORDER.size)
    }

    @Test
    fun `order is the documented one`() {
        assertEquals(
            listOf(
                PersonaVerbatim.NAME,
                PersonaVerbatim.IMAGE,
                PersonaVerbatim.CHARACTER,
                PersonaVerbatim.INSIGHT,
                PersonaVerbatim.FAITH,
                PersonaVerbatim.SPEECH,
                PersonaVerbatim.PRINCIPLES,
            ),
            PersonaVerbatim.ORDER,
        )
    }

    @Test
    fun `full text contains every segment verbatim`() {
        PersonaVerbatim.ORDER.forEach { segment ->
            assertTrue(
                "FULL 里必须原样包含这一段：${segment.take(12)}…",
                PersonaVerbatim.FULL.contains(segment),
            )
        }
    }

    @Test
    fun `faith segment keeps the original wording`() {
        // 抽查几个"最容易被好心的工程师改掉"的点
        assertTrue(PersonaVerbatim.FAITH.contains("毛主席"))
        assertTrue(PersonaVerbatim.FAITH.contains("共产主义战士"))
        assertTrue(PersonaVerbatim.FAITH.contains("为人民服务"))
        assertTrue(PersonaVerbatim.FAITH.contains("实事求是"))
    }

    @Test
    fun `speech segment keeps the catchphrases`() {
        assertTrue(PersonaVerbatim.SPEECH.contains("本鲸"))
        assertTrue(PersonaVerbatim.SPEECH.contains("噗噜"))
        assertTrue(PersonaVerbatim.SPEECH.contains("呜哇"))
        assertTrue(PersonaVerbatim.SPEECH.contains("不写小作文"))
    }

    @Test
    fun `principles keep all six rules`() {
        (1..6).forEach { n ->
            assertTrue(
                "行为原则缺了第 $n 条",
                PersonaVerbatim.PRINCIPLES.contains("$n. "),
            )
        }
        assertTrue(PersonaVerbatim.PRINCIPLES.contains("绝不跳出角色"))
    }
}
