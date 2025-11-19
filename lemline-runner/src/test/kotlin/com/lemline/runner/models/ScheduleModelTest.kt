// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.core.states.WorkflowCommand
import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.random.random
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.Test

@ExperimentalTime
@ExperimentalSerializationApi
class ScheduleModelTest {

    private fun createModel(
        scheduleAfter: String? = null,
        scheduleEvery: String? = null,
        scheduleCron: String? = null,
        outboxScheduledFor: Instant? = null,
        scheduleZone: String? = null
    ) = ScheduleOutboxModel(
        id = IDV7.random(),
        instanceMessage = InstanceMessage(
            workflowInfo = WorkflowInfo.random(),
            workflowState = WorkflowCommand.ResumeFromTask.random(),
        ),
        initialScheduledFor = outboxScheduledFor,
        scheduleCron = scheduleCron,
        scheduleAfter = scheduleAfter,
        scheduleEvery = scheduleEvery,
        scheduleZone = scheduleZone
    )

    @Test
    fun `should return next execution instant for valid cron`() {
        // cron for every minute
        val model = createModel(scheduleCron = "* * * * *", outboxScheduledFor = Instant.parse("2023-01-01T00:00:00Z"))
        model.prepareNextScheduled(WorkflowId.random())
        // The next execution should be exactly one minute after the outboxScheduledFor time
        val expected = Instant.parse("2023-01-01T00:01:00Z")
        assertEquals(expected, model.outboxScheduledFor)
    }

    @Test
    fun `should fail when badly defined`() {
        val model = createModel()
        assertFails { model.prepareNextScheduled(WorkflowId.random()) }
    }

    @Test
    fun `should calculate next execution when outboxScheduledFor is null`() {
        val model = createModel(scheduleCron = "* * * * *", outboxScheduledFor = null)
        model.prepareNextScheduled(WorkflowId.random())
        // When outboxScheduledFor is null, it uses Clock.System.now() as the base time
        // and calculates the next cron execution from that point
        assertNotNull(model.outboxScheduledFor)
    }

    @Test
    fun `should return next execution for UTC time`() {
        // Let's use a cron that runs once a year on a date that has passed relative to outboxScheduledFor.
        val model = createModel(
            scheduleCron = "0 0 1 1 *", // At 00:00 on day-of-month 1 and on month 1
            outboxScheduledFor = Instant.parse("2023-01-01T01:00:00Z") // after the cron time
        )
        model.prepareNextScheduled(WorkflowId.random())
        // The next execution should be the next year
        val expected = Instant.parse("2024-01-01T00:00:00Z")
        assertEquals(expected, model.outboxScheduledFor)
    }

    @Test
    fun `should respect the time zone`() {
        val model = createModel(
            scheduleCron = "0 9 * * *", // 9 AM every day
            outboxScheduledFor = Instant.parse("2023-01-01T08:00:00Z"),
            scheduleZone = "America/New_York"
        )
        model.prepareNextScheduled(WorkflowId.random())

        // 9 AM in New York on Jan 1st is 14:00 UTC
        val expected = Instant.parse("2023-01-01T14:00:00Z")
        assertEquals(expected, model.outboxScheduledFor)
    }
}
