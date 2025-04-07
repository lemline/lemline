package com.lemline.common.json

import io.serverlessworkflow.impl.expressions.DateTimeDescriptor
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

class DateTimeDescriptorTest {

    @Test
    fun `test DateTimeDescriptor serialization`() {
        val instant = Instant.parse("2023-11-15T08:30:05.987Z")
        val dateTimeDescriptor = DateTimeDescriptor.from(instant)

        // Serialize dateTimeDescriptor
        val jsonString = Json.encodeToElement(dateTimeDescriptor).toString()

        // Expected JSON format:
        val expectedJsonString =
            """{"iso8601":"2023-11-15T08:30:05.987Z","epoch":{"seconds":1700037005,"milliseconds":1700037005987}}"""

        assertEquals(expectedJsonString, jsonString)
    }
} 