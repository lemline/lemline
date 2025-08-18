// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.common.ids.IdGenerator
import com.lemline.runner.models.ScheduleModel
import com.lemline.runner.repositories.ScheduleRepository
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime

/**
 * Abstract base class for wait repository tests.
 */
@ExperimentalTime
internal abstract class ScheduleRepositoryTest : OutboxRepositoryTest<ScheduleModel>() {

    @Inject
    override lateinit var repository: ScheduleRepository

    override fun createRandomEntity() = ScheduleModel(
        workflowId = IdGenerator.generateTimeBasedId(),
        workflowName = randomString,
        workflowVersion = randomString,
        workflowPosition = randomString,
        workflowState = randomString,
        scheduleAfter = randomNullableString,
        scheduleCron = randomNullableString,
        scheduleEvery = randomNullableString,
        outboxScheduledFor = randomInstant
    )

    override fun changeDelayedUntil(model: ScheduleModel) = model.copy(outboxDelayedUntil = randomInstant)
}
