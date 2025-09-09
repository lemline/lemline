// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.common.random.random
import com.lemline.runner.models.ScheduleOutboxModel
import com.lemline.runner.random.random
import com.lemline.runner.repositories.ScheduleRepository
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Abstract base class for wait repository tests.
 */
@ExperimentalTime
@ExperimentalSerializationApi
internal abstract class ScheduleRepositoryTest : OutboxRepositoryTest<ScheduleOutboxModel>() {

    @Inject
    override lateinit var repository: ScheduleRepository

    override fun createRandomEntity() = ScheduleOutboxModel.random()

    override fun changeDelayedUntil(model: ScheduleOutboxModel) =
        model.copy().apply { outboxDelayedUntil = Instant.random() }
}
