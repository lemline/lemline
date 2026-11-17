// SPDX-License-Identifier: BUSL-1.1
package com.lemline.common.json

import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test

class JacksonNodesTest {
    @Serializable
    data class Payload(@Serializable(with = JsonNodeSerializer::class) val data: com.fasterxml.jackson.databind.JsonNode)

    @Test
    fun `strict runtime parsing distinguishes missing null and invalid documents`() {
        val data = parseJson("""{"present":null,"empty":[],"number":9007199254740993,"decimal":1.234567890123456789}""")
        assertEquals(true, data.has("present"))
        assertEquals(true, data["present"].isNull)
        assertEquals(false, data.has("absent"))
        assertEquals("1.234567890123456789", data["decimal"].asText())
        assertEquals("9007199254740993", data["number"].asText())
        for (invalid in listOf("", "  ", "{} {}", "{unquoted:1}", "1e1000000", "1e-1000000")) assertFails { parseJson(invalid) }
        assertEquals(data, parseJson(data.toString()))
    }

    @Test
    fun `typed Kotlin DTO codecs preserve nested native trees`() {
        val payload = Payload(parseJson("""{"null":null,"empty":[],"false":false,"text":"é","number":9007199254740993,"decimal":1.234567890123456789}"""))
        val text = LemlineJson.encodeToString(payload)
        assertEquals(payload, LemlineJson.decodeFromString<Payload>(text))
    }

    @Test
    fun `building and merging objects leaves source objects unchanged`() {
        val original = parseJson("""{"nested":{"value":1}}""").jsonObject
        val merged = ObjectNode(original + ("added" to jsonValue(2)))
        assertEquals(false, original.has("added"))
        assertEquals(true, merged.has("added"))
    }
}
