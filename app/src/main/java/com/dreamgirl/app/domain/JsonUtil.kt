package com.dreamgirl.app.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 模型有时会把 JSON 包在 ```json 里、或前后带一句废话。
 * 这里做的是「尽力而为地把它抠出来」，抠不出来就返回 null，
 * 由调用方决定降级策略（记忆抽取失败绝不影响主流程，方案 §6.4-5）。
 */
object JsonUtil {

    val lenient = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    fun extractJsonObject(raw: String): JsonObject? {
        val cleaned = stripFence(raw)
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val candidate = cleaned.substring(start, end + 1)
        return runCatching { lenient.parseToJsonElement(candidate).jsonObject }.getOrNull()
    }

    private fun stripFence(raw: String): String {
        val t = raw.trim()
        if (!t.startsWith("```")) return t
        val firstNewline = t.indexOf('\n')
        if (firstNewline < 0) return t
        val body = t.substring(firstNewline + 1)
        val fence = body.lastIndexOf("```")
        return if (fence >= 0) body.substring(0, fence) else body
    }

    fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    fun JsonObject.stringList(key: String): List<String> {
        val el: JsonElement = this[key] ?: return emptyList()
        return runCatching {
            el.jsonArray.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
        }.getOrElse {
            // 有些时候模型会给一个字符串而不是数组
            (el as? JsonPrimitive)?.contentOrNull
                ?.split('\n', '；', ';')
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?: emptyList()
        }
    }

    fun JsonObject.double(key: String, default: Double): Double =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: default

    fun JsonObject.obj(key: String): JsonObject? =
        runCatching { this[key]?.jsonObject }.getOrNull()

    fun JsonObject.array(key: String): List<JsonObject> =
        runCatching { this[key]?.jsonArray?.map { it.jsonObject } ?: emptyList() }
            .getOrElse { emptyList() }

    /** 把任意字符串安全地塞进 JSON 字符串字面量。 */
    fun quote(s: String): String = buildString {
        append('"')
        s.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    fun stringArray(items: List<String>): String =
        items.joinToString(prefix = "[", postfix = "]", separator = ",") { quote(it) }

    fun parseStringArray(json: String): List<String> {
        val t = json.trim()
        if (!t.startsWith("[")) return emptyList()
        return runCatching {
            lenient.parseToJsonElement(t).jsonArray
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        }.getOrElse { emptyList() }
    }
}
