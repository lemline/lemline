// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.runner.outbox.OutBoxStatus
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import org.junit.jupiter.api.Test

@ExperimentalTime
class ScheduleModelTest {

    private fun createModel(
        scheduleAfter: String? = null,
        scheduleEvery: String? = null,
        scheduleCron: String? = null,
        outboxScheduledFor: Instant? = null,
        scheduleZone: String? = null
    ) = ScheduleModel(
        workflowId = "wf-1",
        workflowVersion = "1.0",
        workflowName = "test-workflow",
        workflowPosition = "start",
        workflowState = "{}",
        outBoxStatus = OutBoxStatus.PENDING,
        outboxScheduledFor = outboxScheduledFor,
        scheduleCron = scheduleCron,
        scheduleAfter = scheduleAfter,
        scheduleEvery = scheduleEvery,
        scheduleZone = scheduleZone
    )

    @Test
    fun `should return next execution instant for valid cron`() {
        // cron for every minute
        val model = createModel(scheduleCron = "* * * * *", outboxScheduledFor = Instant.parse("2023-01-01T00:00:00Z"))
        model.updateBeforeExecution()
        // The next execution should be exactly one minute after the outboxScheduledFor time
        val expected = Instant.parse("2023-01-01T00:01:00Z")
        assertEquals(expected, model.outboxScheduledFor)
    }

    @Test
    fun `should fail when badly defined`() {
        val model = createModel()
        assertFails { model.updateBeforeExecution() }
    }

    @Test
    fun `should return null when outboxScheduledFor is null`() {
        val model = createModel(scheduleCron = "* * * * *", outboxScheduledFor = null)
        model.updateBeforeExecution()
        assertNull(model.outboxScheduledFor)
    }

    @Test
    fun `should return next execution for UTC time`() {
        // Let's use a cron that runs once a year on a date that has passed relative to outboxScheduledFor.
        val model = createModel(
            scheduleCron = "0 0 1 1 *", // At 00:00 on day-of-month 1 and on month 1
            outboxScheduledFor = Instant.parse("2023-01-01T01:00:00Z") // after the cron time
        )
        model.updateBeforeExecution()
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
        model.updateBeforeExecution()

        // 9 AM in New York on Jan 1st is 14:00 UTC
        val expected = Instant.parse("2023-01-01T14:00:00Z")
        assertEquals(expected, model.outboxScheduledFor)
    }
}
