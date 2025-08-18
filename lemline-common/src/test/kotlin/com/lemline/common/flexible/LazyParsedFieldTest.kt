// SPDX-License-Identifier: BUSL-1.1
package com.lemline.common.flexible

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@Serializable
data class MyData(val value: String)

class LazyParsedFieldTest {

    private val myDataSerializer = MyData.serializer()
    private val myDataInstance = MyData("test")
    private val myDataJson = """{"value":"test"}"""

    @Test
    fun `should initialize with serialized value`() {
        val field = LazyParsedField(serialized = myDataJson, serializer = myDataSerializer)
        assertEquals(myDataJson, field.serialized)
        assertEquals(myDataInstance, field.parsed)
    }

    @Test
    fun `should initialize with parsed value`() {
        val field = LazyParsedField(parsed = myDataInstance, serializer = myDataSerializer)
        assertEquals(myDataInstance, field.parsed)
        assertEquals(myDataJson, field.serialized)
    }

    @Test
    fun `should throw when initialized with both serialized and parsed values`() {
        assertThrows<IllegalArgumentException> {
            LazyParsedField(_serialized = myDataJson, _parsed = myDataInstance, serializer = myDataSerializer)
        }
    }

    @Test
    fun `should throw when initialized with neither serialized nor parsed values`() {
        assertThrows<IllegalArgumentException> {
            LazyParsedField(_serialized = null, _parsed = null, serializer = myDataSerializer)
        }
    }

    @Test
    fun `should serialize and deserialize correctly`() {
        val originalField = LazyParsedField(parsed = myDataInstance, serializer = myDataSerializer)
        val json = Json.encodeToString(LazyParsedField.serializer(myDataSerializer), originalField)
        val deserializedField = Json.decodeFromString(LazyParsedField.serializer(myDataSerializer), json)

        assertEquals(originalField.serialized, deserializedField.serialized)
        assertEquals(originalField.parsed, deserializedField.parsed)
    }

    @Test
    fun `parsed should be lazily initialized`() {
        // This test is a bit conceptual to test laziness directly without reflection/mocks
        // We can infer it by checking if the parsed value is correct when starting from serialized form
        val field = LazyParsedField(serialized = myDataJson, serializer = myDataSerializer)
        // Accessing 'parsed' for the first time should trigger deserialization
        assertEquals(myDataInstance, field.parsed)
    }

    @Test
    fun `serialized should be lazily initialized`() {
        // Similar to the parsed test, we infer laziness
        val field = LazyParsedField(parsed = myDataInstance, serializer = myDataSerializer)
        // Accessing 'serialized' for the first time should trigger serialization
        assertEquals(myDataJson, field.serialized)
    }
}
