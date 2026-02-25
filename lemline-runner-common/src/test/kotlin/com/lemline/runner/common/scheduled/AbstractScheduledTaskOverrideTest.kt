// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.scheduled

import io.mockk.mockk
import io.quarkus.runtime.StartupEvent
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AbstractScheduledTaskOverrideTest {

    @Test
    fun `scheduled task is skipped when global scheduling is disabled by default`() {
        val task = TestScheduledTask(
            taskEnabled = true,
            allowWhenDisabled = false,
        )
        task.scheduledEnabled = "false"

        task.onStart(mockk<StartupEvent>())

        assertFalse(task.scheduled)
    }

    @Test
    fun `scheduled task can opt in when global scheduling is disabled`() {
        val task = TestScheduledTask(
            taskEnabled = true,
            allowWhenDisabled = true,
        )
        task.scheduledEnabled = "false"

        task.onStart(mockk<StartupEvent>())

        assertTrue(task.scheduled)
    }

    private class TestScheduledTask(
        private val taskEnabled: Boolean,
        private val allowWhenDisabled: Boolean,
    ) : AbstractScheduledTask() {
        var scheduled: Boolean = false

        override val enabled: Boolean get() = taskEnabled
        override val interval: Duration get() = 1.seconds
        override val jobName: String get() = "test-scheduled-task"
        override val initialDelay: Duration get() = Duration.ZERO
        override val runWhenScheduledDisabled: Boolean get() = allowWhenDisabled

        override suspend fun doWork() {
            // No-op
        }

        override fun scheduleTask() {
            scheduled = true
        }
    }
}
