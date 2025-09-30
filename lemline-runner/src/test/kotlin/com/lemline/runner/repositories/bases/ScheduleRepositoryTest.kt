// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.runner.models.ScheduleModel
import com.lemline.runner.outbox.bases.RunStatus
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
internal abstract class ScheduleRepositoryTest : OutboxRepositoryTest<ScheduleModel>() {

    @Inject
    override lateinit var repository: ScheduleRepository

    override fun createRandomEntity(
        runStatus: RunStatus,
        runAt: Instant,
        runDelayedUntil: Instant,
        runAttemptCount: Int,
        runLastErrorClass: String?,
        runLastErrorMessage: String?,
        runLastErrorStackTrace: String?
    ) = ScheduleModel.random().copy(
        runStatus = runStatus,
        runAt = runAt
    ).apply {
        this.runDelayedUntil = runDelayedUntil
        this.runAttemptCount = runAttemptCount
        this.runLastErrorClass = runLastErrorClass
        this.runLastErrorMessage = runLastErrorMessage
        this.runLastErrorStackTrace = runLastErrorStackTrace
    }

    override fun modify(model: ScheduleModel) = ScheduleModel.random().copy(id = model.id)
}
