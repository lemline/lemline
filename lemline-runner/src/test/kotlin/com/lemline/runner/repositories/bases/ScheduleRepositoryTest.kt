// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.common.ids.IdGenerator
import com.lemline.runner.models.ScheduleModel
import com.lemline.runner.repositories.ScheduleRepository
import jakarta.inject.Inject
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.random.Random

/**
 * Abstract base class for wait repository tests.
 */
internal abstract class ScheduleRepositoryTest : OutboxRepositoryTest<ScheduleModel>() {

    @Inject
    override lateinit var repository: ScheduleRepository

    override fun createWithMessage(message: String) = ScheduleModel(
        workflowId = IdGenerator.generateTimeBasedId(),
        message = message,
        cron = null,
        after = null,
        every = Duration.of(Random.nextLong(), ChronoUnit.MILLIS),
        outboxScheduledFor = Instant.now()
    )

    override fun copyModel(model: ScheduleModel, message: String) = model.copy(message = message)
}
