package com.lemline.core.json

import io.serverlessworkflow.impl.expressions.DateTimeDescriptor
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertTrue

class DateTimeDescriptorTest {

    @Test
    fun `test DateTimeDescriptor serialization`() {
        val instant = Instant.parse("2023-11-15T08:30:05.987Z")
        val dateTimeDescriptor = DateTimeDescriptor.from(instant)

        // Serialize dateTimeDescriptor
        val jsonString = LemlineJson.encodeToElement(dateTimeDescriptor).toString()

        // Test the presence of required fields rather than exact string matching
        assertTrue(
            jsonString.contains("\"iso8601\":\"2023-11-15T08:30:05.987Z\""),
            "JSON should contain ISO8601 date"
        )
        assertTrue(
            jsonString.contains("\"epoch\":{"),
            "JSON should contain epoch information"
        )
        assertTrue(
            jsonString.contains("\"seconds\":1700037005"),
            "JSON should contain correct seconds value"
        )
        assertTrue(
            jsonString.contains("\"milliseconds\":1700037005987"),
            "JSON should contain correct milliseconds value"
        )
    }
} 