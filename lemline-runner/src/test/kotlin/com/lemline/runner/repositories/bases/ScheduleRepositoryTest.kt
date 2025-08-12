// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.runner.models.ScheduleModel
import com.lemline.runner.repositories.ScheduleRepository
import jakarta.inject.Inject
import java.time.Instant
import kotlin.random.Random

/**
 * Abstract base class for wait repository tests.
 */
internal abstract class ScheduleRepositoryTest : OutboxRepositoryTest<ScheduleModel>() {

    @Inject
    override lateinit var repository: ScheduleRepository

    override fun createWithMessage(message: String) = ScheduleModel(
        message = message,
        cron = Random.nextBytes(16).toString(),
        after = Random.nextBytes(16).toString(),
        every = Random.nextBytes(16).toString(),
        delayedUntil = Instant.now()
    )

    override fun copyModel(model: ScheduleModel, message: String) = model.copy(message = message)
}
