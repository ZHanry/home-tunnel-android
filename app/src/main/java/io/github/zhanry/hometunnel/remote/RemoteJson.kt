package io.github.zhanry.hometunnel.remote

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Security messages must not silently accept duplicate keys or malformed UTF-8. */
object RemoteJson {
    const val MAX_BYTES = 64 * 1024
    private val json = Json

    fun parse(bytes: ByteArray, maximum: Int = MAX_BYTES): JsonObject {
        require(bytes.size <= maximum) { "RD_MESSAGE_TOO_LARGE" }
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        return Parser(text).parse() as? JsonObject ?: error("RD_JSON_OBJECT_REQUIRED")
    }

    fun canonical(value: JsonElement): String = when (value) {
        is JsonObject -> value.entries.sortedBy { it.key }.joinToString(",", "{", "}") {
            "${JsonPrimitive(it.key)}:${canonical(it.value)}"
        }
        is JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }
        else -> value.toString()
    }

    private class Parser(private val text: String) {
        private var index = 0
        private var nodes = 0
        fun parse(): JsonElement = value(0).also { space(); require(index == text.length) { "RD_JSON_TRAILING" } }
        private fun space() { while (index < text.length && text[index] in " \r\n\t") index++ }
        private fun take(c: Char): Boolean {
            space()
            return if (index < text.length && text[index] == c) { index++; true } else false
        }
        private fun value(depth: Int): JsonElement {
            require(depth <= 24 && ++nodes <= 4096) { "RD_JSON_LIMIT" }
            space()
            require(index < text.length) { "RD_JSON_TRUNCATED" }
            return when (text[index]) {
                '{' -> {
                    index++
                    val fields = linkedMapOf<String, JsonElement>()
                    if (!take('}')) {
                        do {
                            space()
                            val key = string()
                            require(!fields.containsKey(key)) { "RD_JSON_DUPLICATE_KEY" }
                            require(take(':')) { "RD_JSON_COLON" }
                            fields[key] = value(depth + 1)
                        } while (take(','))
                        require(take('}')) { "RD_JSON_OBJECT" }
                    }
                    JsonObject(fields)
                }
                '[' -> {
                    index++
                    val values = mutableListOf<JsonElement>()
                    if (!take(']')) {
                        do { values += value(depth + 1) } while (take(','))
                        require(take(']')) { "RD_JSON_ARRAY" }
                    }
                    JsonArray(values)
                }
                '"' -> JsonPrimitive(string())
                else -> {
                    val start = index
                    while (index < text.length && text[index] !in " \r\n\t,]}") index++
                    val token = text.substring(start, index)
                    require(token in setOf("true", "false", "null") || NUMBER.matches(token)) { "RD_JSON_NUMBER" }
                    if (NUMBER.matches(token)) {
                        require(token.toDoubleOrNull()?.isFinite() == true) { "RD_JSON_NUMBER" }
                        if (token.none { it in ".eE" }) require(token.toLongOrNull() != null) { "RD_JSON_INTEGER_RANGE" }
                    }
                    if (token == "null") JsonNull else json.parseToJsonElement(token)
                }
            }
        }
        private fun string(): String {
            require(index < text.length && text[index] == '"') { "RD_JSON_STRING" }
            val start = index++
            var escaped = false
            while (index < text.length) {
                val c = text[index++]
                if (c == '"' && !escaped) {
                    val result = (json.parseToJsonElement(text.substring(start, index)) as JsonPrimitive).content
                    // Java encoders replace lone surrogates; reject them before any signing or submission.
                    require(validUnicode(result)) { "RD_JSON_UNICODE" }
                    return result
                }
                require(c.code >= 32) { "RD_JSON_CONTROL" }
                escaped = c == '\\' && !escaped
            }
            error("RD_JSON_TRUNCATED")
        }
        companion object { private val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?") }
    }

    fun validUnicode(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text[i++]
            if (Character.isHighSurrogate(c)) {
                if (i == text.length || !Character.isLowSurrogate(text[i++])) return false
            } else if (Character.isLowSurrogate(c)) return false
        }
        return true
    }
}
