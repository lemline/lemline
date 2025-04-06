package com.lemline.common.json

import io.serverlessworkflow.impl.expressions.DateTimeDescriptor
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

class DateTimeDescriptorSerializerTest {

    // Define a simple data class that uses DateTimeDescriptor
    // to leverage kotlinx.serialization's automatic serializer discovery.
    @Serializable
    data class TestWrapper(
        @Serializable(with = DateTimeDescriptorSerializer::class)
        val dateTime: DateTimeDescriptor
    )

    @Test
    fun `test DateTimeDescriptor serialization`() {
        val instant = Instant.now()
        val dateTimeDescriptor = DateTimeDescriptor.from(instant)
        val wrapper = TestWrapper(dateTimeDescriptor)

        // Serialize the wrapper class using type inference
        val jsonString = Json.encodeToString(wrapper)

        // Expected JSON format: {"dateTime":"ISO_8601_STRING"}
        // The serializer should produce the ISO 8601 string directly.
        val expectedJsonString = "{\"dateTime\":\"$instant\"}"

        assertEquals(expectedJsonString, jsonString)
    }

    @Test
    fun `test DateTimeDescriptor deserialization`() {
        val instant = Instant.now()
        val jsonString = "{\"dateTime\":\"$instant\"}"

        // Deserialize the JSON string back into the wrapper class using type inference
        val decodedWrapper: TestWrapper = Json.decodeFromString(jsonString)

        // Create the expected DateTimeDescriptor
        val expectedDateTimeDescriptor = DateTimeDescriptor.from(instant)

        // Assert that the deserialized DateTimeDescriptor matches the expected one.
        assertEquals(expectedDateTimeDescriptor.iso8601(), decodedWrapper.dateTime.iso8601())
    }

    @Test
    fun `test DateTimeDescriptor serialization with milliseconds`() {
        val instant = Instant.parse("2023-11-15T08:30:05.456Z")
        val dateTimeDescriptor = DateTimeDescriptor.from(instant)
        val wrapper = TestWrapper(dateTimeDescriptor)

        val jsonString = Json.encodeToString(wrapper)
        val expectedJsonString = "{\"dateTime\":\"2023-11-15T08:30:05.456Z\"}"

        assertEquals(expectedJsonString, jsonString)
    }

    @Test
    fun `test DateTimeDescriptor deserialization with milliseconds`() {
        val instant = Instant.parse("2023-11-15T08:30:05.987Z")
        val jsonString = "{\"dateTime\":\"$instant\"}"

        val decodedWrapper: TestWrapper = Json.decodeFromString(jsonString)
        val expectedDateTimeDescriptor = DateTimeDescriptor.from(instant)

        assertEquals(expectedDateTimeDescriptor.iso8601(), decodedWrapper.dateTime.iso8601())
    }
} 