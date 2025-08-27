// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.models.ScheduleModel
import com.lemline.runner.repositories.ScheduleRepository
import jakarta.inject.Inject
import java.util.*
import kotlin.time.ExperimentalTime

/**
 * Abstract base class for wait repository tests.
 */
@ExperimentalTime
internal abstract class ScheduleRepositoryTest : OutboxRepositoryTest<ScheduleModel>() {

    @Inject
    override lateinit var repository: ScheduleRepository

    override fun createRandomEntity() = ScheduleModel(
        instance = InstanceMessage.fromStrings(
            workflowId = UUID.randomUUID(),
            workflowName = randomString,
            workflowVersion = randomString,
            workflowPosition = randomString,
            workflowState = randomString,
            parentId = null,
        ),
        scheduleAfter = randomNullableString,
        scheduleCron = randomNullableString,
        scheduleEvery = randomNullableString,
        outboxScheduledFor = randomInstant,
        scheduleZone = randomNullableString,
    )

    override fun changeDelayedUntil(model: ScheduleModel) = model.copy(outboxDelayedUntil = randomInstant)
}
