// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.ingestion

import com.lemline.common.random.nullableRandom
import com.lemline.common.random.random
import com.lemline.common.values.IDV7
import com.lemline.runner.instances.InstanceMessage
import com.lemline.runner.outbox.OutBoxStatus
import com.lemline.runner.random.random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@ExperimentalTime
@ExperimentalSerializationApi
internal class IngestionMessageTest {

    @Test
    fun `should be JSON serializable and deserializable for Parent message`() {
        // Given
        val original: IngestionMessage = ParentIngestionMessage(
            id = IDV7.random(),
            instanceMessage = InstanceMessage.random(),
            outBoxStatus = OutBoxStatus.PENDING,
            outboxScheduledFor = Clock.System.now(),
        )
        // When
        val serialized = original.toJsonString()
        val deserialized = IngestionMessage.fromJsonString(serialized)
        // Then
        assertEquals(original, deserialized)
    }

    @Test
    fun `should be JSON serializable and deserializable for Wait message`() {
        // Given
        val original: IngestionMessage = WaitIngestionMessage(
            id = IDV7.random(),
            instanceMessage = InstanceMessage.random(),
            outBoxStatus = OutBoxStatus.PENDING,
            outboxScheduledFor = Instant.nullableRandom(),
        )
        // When
        val serialized = original.toJsonString()
        val deserialized = IngestionMessage.fromJsonString(serialized)
        // Then
        assertEquals(original, deserialized)
    }

    @Test
    fun `should be JSON serializable and deserializable for Schedule message`() {
        // Given
        val original: IngestionMessage = ScheduleIngestionMessage(
            id = IDV7.random(),
            instanceMessage = InstanceMessage.random(),
            outBoxStatus = OutBoxStatus.PENDING,
            outboxScheduledFor = Instant.nullableRandom(),
            scheduleAfter = String.nullableRandom(),
            scheduleEvery = String.nullableRandom(),
            scheduleCron = String.nullableRandom(),
            scheduleZone = String.nullableRandom(),
        )
        // When
        val serialized = original.toJsonString()
        val deserialized = IngestionMessage.fromJsonString(serialized)
        // Then
        assertEquals(original, deserialized)
    }

    @Test
    fun `should be JSON serializable and deserializable for Retry message`() {
        // Given
        val original: IngestionMessage = RetryIngestionMessage(
            id = IDV7.random(),
            instanceMessage = InstanceMessage.random(),
            outBoxStatus = OutBoxStatus.FAILED,
            outboxScheduledFor = Instant.nullableRandom(),
            errorReason = String.random(),
            errorClass = String.random(),
            errorMessage = String.nullableRandom(),
            errorStackTrace = String.random(),
        )
        // When
        val serialized = original.toJsonString()
        val deserialized = IngestionMessage.fromJsonString(serialized)
        // Then
        assertEquals(original, deserialized)
    }

    @Test
    fun `should be JSON serializable and deserializable for Failure message`() {
        // Given
        val original: IngestionMessage = FailureIngestionMessage(
            id = IDV7.random(),
            instanceMessage = InstanceMessage.random(),
            payload = String.nullableRandom(),
            errorReason = String.random(),
            errorClass = String.random(),
            errorMessage = String.nullableRandom(),
            errorStackTrace = String.random(),
        )
        // When
        val serialized = original.toJsonString()
        val deserialized = IngestionMessage.fromJsonString(serialized)
        // Then
        assertEquals(original, deserialized)
    }

    @Test
    fun `serialized keys maintain their values for messages backward compatibility`() {
        // Given
        val id = IDV7.random()
        val scheduledFor = Instant.random()
        val instance = InstanceMessage.random()
        // When
        val msgParent = ParentIngestionMessage(
            id = id,
            instanceMessage = instance,
            outBoxStatus = OutBoxStatus.FAILED,
            outboxScheduledFor = scheduledFor,
        )
        val msgWait = WaitIngestionMessage(
            id = id,
            instanceMessage = instance,
            outBoxStatus = OutBoxStatus.FAILED,
            outboxScheduledFor = scheduledFor,
        )
        val msgRetry = RetryIngestionMessage(
            id = id,
            instanceMessage = instance,
            outBoxStatus = OutBoxStatus.FAILED,
            outboxScheduledFor = scheduledFor,
            errorReason = "errorReason",
            errorClass = "errorClass",
            errorMessage = "errorMessage",
            errorStackTrace = "errorStackTrace",
        )
        val msgSchedule = ScheduleIngestionMessage(
            id = id,
            instanceMessage = instance,
            outBoxStatus = OutBoxStatus.FAILED,
            outboxScheduledFor = scheduledFor,
            scheduleAfter = "after",
            scheduleEvery = "every",
            scheduleCron = "cron",
            scheduleZone = "zone",
        )
        val msgFailure = FailureIngestionMessage(
            id = id,
            instanceMessage = instance,
            payload = "payload",
            errorReason = "errorReason",
            errorClass = "errorClass",
            errorMessage = "errorMessage",
            errorStackTrace = "errorStackTrace",
        )

        // Then

        // ParentIngestionMessage has a serial name "p"
        assertEquals(
            """{"t":"p","id":"$id","i":${instance.toJsonString()},"s":"FAILED","f":"$scheduledFor"}""",
            msgParent.toJsonString(),
        )
        // WaitIngestionMessage has a serial name "w"
        assertEquals(
            """{"t":"w","id":"$id","i":${instance.toJsonString()},"s":"FAILED","f":"$scheduledFor"}""",
            msgWait.toJsonString(),
        )
        // RetryIngestionMessage has a serial name "r"
        assertEquals(
            """{"t":"r","id":"$id","i":${instance.toJsonString()},"s":"FAILED","f":"$scheduledFor","er":"errorReason","ec":"errorClass","em":"errorMessage","es":"errorStackTrace"}""",
            msgRetry.toJsonString(),
        )
        // ScheduleIngestionMessage has a serial name "s"
        assertEquals(
            """{"t":"s","id":"$id","i":${instance.toJsonString()},"s":"FAILED","f":"$scheduledFor","sa":"after","se":"every","sc":"cron","sz":"zone"}""",
            msgSchedule.toJsonString(),
        )
        // FailureIngestionMessage has a serial name "f"
        assertEquals(
            """{"t":"f","id":"$id","i":${instance.toJsonString()},"p":"payload","er":"errorReason","ec":"errorClass","em":"errorMessage","es":"errorStackTrace"}""",
            msgFailure.toJsonString(),
        )
    }
}
