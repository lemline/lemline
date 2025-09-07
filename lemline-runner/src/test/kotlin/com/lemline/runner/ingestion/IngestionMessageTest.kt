// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.ingestion

import com.lemline.runner.random.random
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@ExperimentalTime
@ExperimentalSerializationApi
internal class IngestionMessageTest {

    @Test
    fun `should be JSON serializable and deserializable for Parent message`() {
        // Given
        val original: IngestionMessage = ParentIngestionMessage.random()
        // When
        val serialized = original.toJsonString()

        println(serialized)
        val deserialized = IngestionMessage.fromJsonString(serialized)
        // Then
        assertEquals(original, deserialized)
    }

    @Test
    fun `should be JSON serializable and deserializable for Wait message`() {
        // Given
        val original: IngestionMessage = WaitIngestionMessage.random()
        // When
        val serialized = original.toJsonString()
        val deserialized = IngestionMessage.fromJsonString(serialized)
        // Then
        assertEquals(original, deserialized)
    }

    @Test
    fun `should be JSON serializable and deserializable for Schedule message`() {
        // Given
        val original: IngestionMessage = ScheduleIngestionMessage.random()
        // When
        val serialized = original.toJsonString()
        val deserialized = IngestionMessage.fromJsonString(serialized)
        // Then
        assertEquals(original, deserialized)
    }

    @Test
    fun `should be JSON serializable and deserializable for Retry message`() {
        // Given
        val original: IngestionMessage = RetryIngestionMessage.random()
        // When
        val serialized = original.toJsonString()
        val deserialized = IngestionMessage.fromJsonString(serialized)
        // Then
        assertEquals(original, deserialized)
    }

    @Test
    fun `should be JSON serializable and deserializable for Failure message`() {
        // Given
        val original: IngestionMessage = FailureIngestionMessage.random()
        // When
        val serialized = original.toJsonString()
        val deserialized = IngestionMessage.fromJsonString(serialized)
        // Then
        assertEquals(original, deserialized)
    }

    @Test
    fun `serialized keys maintain their values for messages backward compatibility`() {
        // Given

        // When
        val msgParent = ParentIngestionMessage.random()

        val msgWait = WaitIngestionMessage.random()

        val msgRetry = RetryIngestionMessage.random()

        val msgSchedule = ScheduleIngestionMessage.random()

        val msgFailure = FailureIngestionMessage.random()

        // Then

        // ParentIngestionMessage has a serial name "p"
        assertEquals(
            with(msgParent.model) { """{"t":"p","m":${toJsonString()}}""" },
            msgParent.toJsonString(),
        )
        // WaitIngestionMessage has a serial name "w"
        assertEquals(
            with(msgWait.model) { """{"t":"w","m":${toJsonString()}}""" },
            msgWait.toJsonString(),
        )
        // RetryIngestionMessage has a serial name "r"
        assertEquals(
            with(msgRetry.model) { """{"t":"r","m":${toJsonString()}}""" },
            msgRetry.toJsonString(),
        )
        // ScheduleIngestionMessage has a serial name "s"
        assertEquals(
            with(msgSchedule.model) { """{"t":"s","m":${toJsonString()}}""" },
            msgSchedule.toJsonString(),
        )
        // FailureIngestionMessage has a serial name "f"
        assertEquals(
            with(msgFailure.model) { """{"t":"f","m":${toJsonString()}}""" },
            msgFailure.toJsonString(),
        )
    }
}
