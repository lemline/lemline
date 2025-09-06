// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.runner.instances.InstanceMessage
import com.lemline.runner.models.IDV7
import com.lemline.runner.models.ScheduleOutboxModel
import com.lemline.runner.random.nullableRandom
import com.lemline.runner.random.random
import com.lemline.runner.repositories.ScheduleRepository
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Abstract base class for wait repository tests.
 */
@ExperimentalTime
internal abstract class ScheduleRepositoryTest : OutboxRepositoryTest<ScheduleOutboxModel>() {

    @Inject
    override lateinit var repository: ScheduleRepository

    override fun createRandomEntity() = ScheduleOutboxModel(
        id = IDV7.random(),
        instanceMessage = InstanceMessage.random(),
        scheduleAfter = String.nullableRandom(),
        scheduleCron = String.nullableRandom(),
        scheduleEvery = String.nullableRandom(),
        outboxScheduledFor = Instant.random(),
        scheduleZone = String.nullableRandom(),
    )

    override fun changeDelayedUntil(model: ScheduleOutboxModel) = model.copy(outboxDelayedUntil = Instant.random())
}
