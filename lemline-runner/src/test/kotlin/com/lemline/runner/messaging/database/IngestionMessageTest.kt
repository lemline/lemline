// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.database

import com.lemline.runner.messaging.database.envelopes.FailureEnvelope
import com.lemline.runner.messaging.database.envelopes.ModelEnvelope
import com.lemline.runner.messaging.database.envelopes.ParentOutboxEnvelope
import com.lemline.runner.messaging.database.envelopes.RetryOutboxEnvelope
import com.lemline.runner.messaging.database.envelopes.ScheduleOutboxEnvelope
import com.lemline.runner.messaging.database.envelopes.WaitOutboxEnvelope
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
        val original: ModelEnvelope = ParentOutboxEnvelope.random()
        // When
        val serialized = original.toJsonString()

        println(serialized)
        val deserialized = ModelEnvelope.fromJsonString(serialized)
        // Then
        assertEquals(original, deserialized)
    }

    @Test
    fun `should be JSON serializable and deserializable for Wait message`() {
        // Given
        val original: ModelEnvelope = WaitOutboxEnvelope.random()
        // When
        val serialized = original.toJsonString()
        val deserialized = ModelEnvelope.fromJsonString(serialized)
        // Then
        assertEquals(original, deserialized)
    }

    @Test
    fun `should be JSON serializable and deserializable for Schedule message`() {
        // Given
        val original: ModelEnvelope = ScheduleOutboxEnvelope.random()
        // When
        val serialized = original.toJsonString()
        val deserialized = ModelEnvelope.fromJsonString(serialized)
        // Then
        assertEquals(original, deserialized)
    }

    @Test
    fun `should be JSON serializable and deserializable for Retry message`() {
        // Given
        val original: ModelEnvelope = RetryOutboxEnvelope.random()
        // When
        val serialized = original.toJsonString()
        val deserialized = ModelEnvelope.fromJsonString(serialized)
        // Then
        assertEquals(original, deserialized)
    }

    @Test
    fun `should be JSON serializable and deserializable for Failure message`() {
        // Given
        val original: ModelEnvelope = FailureEnvelope.random()
        // When
        val serialized = original.toJsonString()
        val deserialized = ModelEnvelope.fromJsonString(serialized)
        // Then
        assertEquals(original, deserialized)
    }

    @Test
    fun `serialized keys maintain their values for messages backward compatibility`() {
        // Given

        // When
        val msgParent = ParentOutboxEnvelope.random()

        val msgWait = WaitOutboxEnvelope.random()

        val msgRetry = RetryOutboxEnvelope.random()

        val msgSchedule = ScheduleOutboxEnvelope.random()

        val msgFailure = FailureEnvelope.random()

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
