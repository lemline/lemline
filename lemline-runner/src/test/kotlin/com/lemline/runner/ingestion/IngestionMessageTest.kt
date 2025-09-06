// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.ingestion

import com.lemline.runner.instances.InstanceMessageTest.Companion.sampleInstance
import com.lemline.runner.models.IDV7
import com.lemline.runner.outbox.OutBoxStatus
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
        val original: IngestionMessage = ParentIngestionMessage(
            id = IDV7.new(),
            instanceMessage = sampleInstance(),
            outBoxStatus = OutBoxStatus.PENDING,
            outboxScheduledFor = Clock.System.now(),
        )
        val json = original.toJsonString()
        val deserialized = IngestionMessage.fromJsonString(json)
        assertEquals(original, deserialized)
    }

    @Test
    fun `should be JSON serializable and deserializable for Wait message`() {
        val original: IngestionMessage = WaitIngestionMessage(
            id = IDV7.new(),
            instanceMessage = sampleInstance(),
            outBoxStatus = OutBoxStatus.PENDING,
            outboxScheduledFor = Clock.System.now(),
        )
        val json = original.toJsonString()
        val deserialized = IngestionMessage.fromJsonString(json)
        assertEquals(original, deserialized)
    }

    @Test
    fun `should be JSON serializable and deserializable for Schedule message`() {
        val original: IngestionMessage = ScheduleIngestionMessage(
            id = IDV7.new(),
            instanceMessage = sampleInstance(),
            outBoxStatus = OutBoxStatus.PENDING,
            outboxScheduledFor = null,
            scheduleAfter = "PT10S",
            scheduleEvery = "a",
            scheduleCron = "b",
            scheduleZone = "c",
        )
        val json = original.toJsonString()
        val deserialized = IngestionMessage.fromJsonString(json)
        assertEquals(original, deserialized)
    }

    @Test
    fun `should be JSON serializable and deserializable for Retry message`() {
        val original: IngestionMessage = RetryIngestionMessage(
            id = IDV7.new(),
            instanceMessage = sampleInstance(),
            outBoxStatus = OutBoxStatus.FAILED,
            outboxScheduledFor = null,
            errorReason = "r",
            errorClass = "a",
            errorMessage = "b",
            errorStackTrace = "c",
        )
        val json = original.toJsonString()
        val deserialized = IngestionMessage.fromJsonString(json)
        assertEquals(original, deserialized)
    }

    @Test
    fun `should be JSON serializable and deserializable for Failure message`() {
        val original: IngestionMessage = FailureIngestionMessage(
            id = IDV7.new(),
            instanceMessage = sampleInstance(),
            payload = "p",
            errorReason = "r",
            errorClass = "a",
            errorMessage = "b",
            errorStackTrace = "c",
        )
        val json = original.toJsonString()
        val deserialized = IngestionMessage.fromJsonString(json)
        assertEquals(original, deserialized)
    }

    @Test
    fun `serialized keys maintain their values for messages backward compatibility`() {
        val id = IDV7.new()

        val scheduledFor = Instant.parse("2023-01-01T00:00:00Z")

        val instance = sampleInstance()

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
            errorReason = "reason",
            errorClass = "errorClass",
            errorMessage = "errorMessage",
            errorStackTrace = "errorStackTrace",
        )

        // Expect compact keys with class discriminator t
        // ParentIngestionMessage has a serial name "p"
        assertEquals(
            """{"t":"p","id":"$id","i":${instance.toJsonString()},"s":"FAILED","f":"2023-01-01T00:00:00Z"}""",
            msgParent.toJsonString(),
        )
        // WaitIngestionMessage has a serial name "w"
        assertEquals(
            """{"t":"w","id":"$id","i":${instance.toJsonString()},"s":"FAILED","f":"2023-01-01T00:00:00Z"}""",
            msgWait.toJsonString(),
        )
        // RetryIngestionMessage has a serial name "r"
        assertEquals(
            """{"t":"r","id":"$id","i":${instance.toJsonString()},"s":"FAILED","f":"2023-01-01T00:00:00Z","ec":"errorClass","em":"errorMessage","es":"errorStackTrace"}""",
            msgRetry.toJsonString(),
        )
        // ScheduleIngestionMessage has a serial name "s"
        assertEquals(
            """{"t":"s","id":"$id","i":${instance.toJsonString()},"s":"FAILED","f":"2023-01-01T00:00:00Z","sa":"after","se":"every","sc":"cron","sz":"zone"}""",
            msgSchedule.toJsonString(),
        )
        // FailureIngestionMessage has a serial name "f"
        assertEquals(
            """{"t":"f","id":"$id","i":${instance.toJsonString()},"p":"payload","r":"reason","ec":"errorClass","em":"errorMessage","es":"errorStackTrace"}""",
            msgFailure.toJsonString(),
        )
    }
}
