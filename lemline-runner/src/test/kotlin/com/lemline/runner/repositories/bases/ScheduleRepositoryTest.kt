// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.common.ids.IdGenerator
import com.lemline.runner.models.ScheduleModel
import com.lemline.runner.repositories.ScheduleRepository
import jakarta.inject.Inject
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime

/**
 * Abstract base class for wait repository tests.
 */
@ExperimentalTime
internal abstract class ScheduleRepositoryTest : OutboxRepositoryTest<ScheduleModel>() {

    @Inject
    override lateinit var repository: ScheduleRepository

    override fun createWithState(state: String) = ScheduleModel(
        workflowId = IdGenerator.generateTimeBasedId(),
        workflowName = Random.nextBytes(10).toString(),
        workflowVersion = Random.nextBytes(10).toString(),
        workflowPosition = Random.nextBytes(10).toString(),
        workflowState = state,
        scheduleAfter = null,
        scheduleCron = null,
        scheduleEvery = Random.nextLong().milliseconds.toString(),
        outboxScheduledFor = Clock.System.now()
    )

    override fun copyModel(model: ScheduleModel, state: String) = model.copy(workflowState = state)
}
