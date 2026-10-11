package com.dreamgirl.app.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetrievalTest {

    @Test
    fun `keywords pick up names and events`() {
        val terms = Keywords.of("上次说的那颗智齿还疼吗")
        assertTrue(terms.contains("智齿"))
    }

    @Test
    fun `stop words are dropped`() {
        val terms = Keywords.of("这个是那个什么")
        assertFalse(terms.contains("这个"))
        assertFalse(terms.contains("什么"))
    }

    @Test
    fun `english words are kept`() {
        val terms = Keywords.of("我用 Kotlin 写了个 app")
        assertTrue(terms.any { it.equals("kotlin", ignoreCase = true) })
    }

    @Test
    fun `empty query yields no terms`() {
        assertTrue(Keywords.of("   ").isEmpty())
    }

    @Test
    fun `bank card numbers are treated as sensitive`() {
        assertTrue(SensitiveFilter.isSensitive("我的卡号是 6222021234567890123"))
    }

    @Test
    fun `id card numbers are treated as sensitive`() {
        assertTrue(SensitiveFilter.isSensitive("身份证 110101199003072316"))
    }

    @Test
    fun `api keys are treated as sensitive`() {
        assertTrue(SensitiveFilter.isSensitive("sk-abcdefghijklmnopqrstuvwxyz123456"))
    }

    @Test
    fun `ordinary sentence is not sensitive`() {
        assertFalse(SensitiveFilter.isSensitive("我明天想去跑步，顺便买点牛奶"))
    }
}
