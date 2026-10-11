package com.dreamgirl.app.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SseStreamParserTest {

    private fun collect(vararg lines: String, endOfStream: Boolean = false): List<String> {
        val out = mutableListOf<String>()
        val parser = SseStreamParser { ev ->
            when (ev) {
                is SseStreamParser.SseEvent.Data -> out += "D:${ev.payload}"
                SseStreamParser.SseEvent.Done -> out += "DONE"
            }
        }
        lines.forEach { parser.feedLine(it) }
        if (endOfStream) parser.endOfStream()
        return out
    }

    @Test
    fun `parses a single data line`() {
        assertEquals(listOf("D:{\"a\":1}"), collect("data: {\"a\":1}", ""))
    }

    @Test
    fun `recognises the done sentinel`() {
        assertEquals(listOf("DONE"), collect("data: [DONE]", ""))
    }

    @Test
    fun `joins multiple data lines inside one event with newline`() {
        val out = collect("data: line1", "data: line2", "")
        assertEquals(listOf("D:line1\nline2"), out)
    }

    @Test
    fun `ignores comments and heartbeat`() {
        assertEquals(listOf("D:x"), collect(": keep-alive", "data: x", ""))
    }

    @Test
    fun `tolerates CRLF line endings`() {
        assertEquals(listOf("D:x"), collect("data: x\r", "\r"))
    }

    @Test
    fun `handles field with no space after colon`() {
        assertEquals(listOf("D:x"), collect("data:x", ""))
    }

    @Test
    fun `emits done when stream ends without an explicit sentinel`() {
        assertEquals(listOf("D:x", "DONE"), collect("data: x", endOfStream = true))
    }

    @Test
    fun `emits nothing for a stream with no data at all`() {
        assertEquals(listOf("DONE"), collect("event: ping", "", endOfStream = true))
    }

    @Test
    fun `stops dispatching after done`() {
        val out = collect("data: [DONE]", "", "data: after")
        assertEquals(listOf("DONE"), out)
    }

    @Test
    fun `realistic deepseek chunk decodes`() {
        val payload =
            """{"id":"1","choices":[{"index":0,"delta":{"content":"哼"},"finish_reason":null}]}"""
        val chunk = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(ChatCompletionChunk.serializer(), payload)
        assertEquals("哼", chunk.choices.first().delta?.content)
    }

    @Test
    fun `usage fields are parsed`() {
        val payload =
            """{"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12,"prompt_cache_hit_tokens":8,"prompt_cache_miss_tokens":2}}"""
        val chunk = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(ChatCompletionChunk.serializer(), payload)
        assertEquals(8, chunk.usage?.promptCacheHitTokens)
    }

    @Test
    fun `streaming parser keeps partial events separated`() {
        val out = mutableListOf<String>()
        val parser = SseStreamParser { ev ->
            if (ev is SseStreamParser.SseEvent.Data) out += ev.payload
        }
        parser.feedLine("data: a")
        parser.feedLine("")
        parser.feedLine("data: b")
        parser.feedLine("")
        assertTrue(out == listOf("a", "b"))
    }
}
