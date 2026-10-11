package com.dreamgirl.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.dreamgirl.app.domain.JsonUtil.double
import com.dreamgirl.app.domain.JsonUtil.string
import com.dreamgirl.app.domain.JsonUtil.stringList

class JsonUtilTest {

    @Test
    fun `parses a plain json object`() {
        val obj = JsonUtil.extractJsonObject("""{"l1":"主线"}""")
        assertEquals("主线", obj?.string("l1"))
    }

    @Test
    fun `strips markdown code fences`() {
        val raw = "```json\n{\"l1\":\"主线\"}\n```"
        assertEquals("主线", JsonUtil.extractJsonObject(raw)?.string("l1"))
    }

    @Test
    fun `tolerates chatter around the json`() {
        val raw = "好的，这是结果：\n{\"l1\":\"主线\"}\n希望有用。"
        assertEquals("主线", JsonUtil.extractJsonObject(raw)?.string("l1"))
    }

    @Test
    fun `returns null when there is no json`() {
        assertNull(JsonUtil.extractJsonObject("今天什么都没发生"))
    }

    @Test
    fun `falls back to splitting a string when a list was expected`() {
        val obj = JsonUtil.extractJsonObject("""{"l2":"第一条；第二条"}""")
        assertEquals(listOf("第一条", "第二条"), obj?.stringList("l2"))
    }

    @Test
    fun `string array round trips with quotes and newlines`() {
        val items = listOf("他说\"我不要\"", "换行\n测试")
        val json = JsonUtil.stringArray(items)
        assertEquals(items, JsonUtil.parseStringArray(json))
    }

    @Test
    fun `missing key yields empty list`() {
        val obj = JsonUtil.extractJsonObject("""{"a":1}""")
        assertTrue(obj!!.stringList("l3").isEmpty())
    }

    @Test
    fun `double falls back to default`() {
        val obj = JsonUtil.extractJsonObject("""{"weight":"abc"}""")
        assertEquals(0.5, obj!!.double("weight", 0.5), 0.0001)
    }
}
