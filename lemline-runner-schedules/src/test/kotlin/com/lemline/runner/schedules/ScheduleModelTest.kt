// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.schedules

import com.lemline.common.values.IDV7
import com.lemline.core.random.randomResumeFromTaskCommand
import com.lemline.core.random.randomWorkflowInfo
import com.lemline.runner.common.messaging.InstanceMessage
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import org.junit.jupiter.api.Test

class ScheduleModelTest {

    private fun createModel(
        scheduleAfter: String? = null,
        scheduleEvery: String? = null,
        scheduleCron: String? = null,
        outboxScheduledFor: Instant? = null,
        scheduleZone: String? = null
    ) = ScheduleModel(
        id = IDV7.random(),
        instanceMessage = InstanceMessage(
            workflowInfo = randomWorkflowInfo(),
            workflowState = randomResumeFromTaskCommand(),
        ),
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
        val next = model.nextScheduled()
        // The next execution should be exactly one minute after the outboxScheduledFor time
        val expected = Instant.parse("2023-01-01T00:01:00Z")
        assertEquals(expected, next!!.outboxScheduledFor)
    }

    @Test
    fun `each execution gets a new workflow id and its own start time`() {
        val model = createModel(scheduleCron = "* * * * *", outboxScheduledFor = Instant.parse("2023-01-01T00:00:00Z"))
        val registeredStartedAt = model.instanceMessage.workflowState.nodeStack.rootState.startedAt
        val executionId = model.executionId
        val beforeTick = Clock.System.now()

        model.prepareExecution()

        val rootState = model.instanceMessage.workflowState.nodeStack.rootState
        assertEquals(executionId, rootState.workflowId)
        assertTrue(rootState.startedAt >= beforeTick, "start time ${rootState.startedAt} predates the tick")
        assertNotEquals(registeredStartedAt, rootState.startedAt)
    }

    @Test
    fun `should fail when badly defined`() {
        val model = createModel()
        assertFails { model.nextScheduled() }
    }

    @Test
    fun `successor has fresh retry state and preserves the schedule identity`() {
        val model = createModel(scheduleEvery = "PT1M", outboxScheduledFor = Instant.parse("2023-01-01T00:00:00Z"))
        model.outboxAttemptCount = 3
        model.outboxErrorMessage = "unavailable"
        val next = model.nextScheduled()!!
        assertEquals(model.scheduleId, next.scheduleId)
        assertNotEquals(model.id, next.id)
        assertNotEquals(model.executionId, next.executionId)
        assertEquals(0, next.outboxAttemptCount)
        assertEquals(null, next.outboxErrorMessage)
        assertEquals(model.nextOccurrenceId, next.id)
        assertEquals(Instant.parse("2023-01-01T00:00:00Z"), model.outboxScheduledFor)
    }

    @Test
    fun `should return next execution for UTC time`() {
        // Let's use a cron that runs once a year on a date that has passed relative to outboxScheduledFor.
        val model = createModel(
            scheduleCron = "0 0 1 1 *", // At 00:00 on day-of-month 1 and on month 1
            outboxScheduledFor = Instant.parse("2023-01-01T01:00:00Z") // after the cron time
        )
        val next = model.nextScheduled()
        // The next execution should be the next year
        val expected = Instant.parse("2024-01-01T00:00:00Z")
        assertEquals(expected, next!!.outboxScheduledFor)
    }

    @Test
    fun `should respect the time zone`() {
        val model = createModel(
            scheduleCron = "0 9 * * *", // 9 AM every day
            outboxScheduledFor = Instant.parse("2023-01-01T08:00:00Z"),
            scheduleZone = "America/New_York"
        )
        val next = model.nextScheduled()

        // 9 AM in New York on Jan 1st is 14:00 UTC
        val expected = Instant.parse("2023-01-01T14:00:00Z")
        assertEquals(expected, next!!.outboxScheduledFor)
    }
}
