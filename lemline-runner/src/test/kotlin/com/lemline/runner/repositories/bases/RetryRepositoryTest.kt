// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.common.ids.IdGenerator
import com.lemline.runner.models.RetryModel
import com.lemline.runner.repositories.RetryRepository
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime


/**
 * Abstract base class for retry repository tests.
 */
@ExperimentalTime
internal abstract class RetryRepositoryTest : OutboxRepositoryTest<RetryModel>() {

    @Inject
    override lateinit var repository: RetryRepository

    override fun createRandomEntity() = RetryModel(
        workflowId = IdGenerator.generateTimeBasedId(),
        workflowName = randomString,
        workflowVersion = randomString,
        workflowPosition = randomString,
        workflowState = randomString,
        message = randomString,
    )

    override fun changeDelayedUntil(model: RetryModel) = model.copy(outboxDelayedUntil = randomInstant)
}
