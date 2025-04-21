// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.json

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.*
import kotlin.test.assertEquals

class UUIDSerializerTest {
    @Test
    fun `should serialize UUID to string`() {
        // Given
        val uuid = UUID.randomUUID()

        // When
        val serialized = LemlineJson.encodeToString(uuid)

        // Then
        assertEquals("\"${uuid}\"", serialized)
    }

    @Test
    fun `should deserialize string to UUID`() {
        // Given
        val uuid = UUID.randomUUID()
        val jsonString = "\"${uuid}\""

        // When
        val deserialized = LemlineJson.decodeFromString<UUID>(jsonString)

        // Then
        assertEquals(uuid, deserialized)
    }

    @Test
    fun `should throw exception for invalid UUID string`() {
        // Given
        val invalidUuidString = "\"not-a-valid-uuid\""

        // When & Then
        assertThrows<IllegalArgumentException> {
            LemlineJson.decodeFromString<UUID>(invalidUuidString)
        }
    }

    @Test
    fun `should handle empty string`() {
        // Given
        val emptyString = "\"\""

        // When & Then
        assertThrows<IllegalArgumentException> {
            LemlineJson.decodeFromString<UUID>(emptyString)
        }
    }

    @Test
    fun `should handle malformed JSON`() {
        // Given
        val malformedJson = "not-a-json"

        // When & Then
        assertThrows<IllegalArgumentException> {
            LemlineJson.decodeFromString<UUID>(malformedJson)
        }
    }
}
