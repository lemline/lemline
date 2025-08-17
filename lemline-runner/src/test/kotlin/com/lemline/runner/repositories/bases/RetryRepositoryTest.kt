// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.common.ids.IdGenerator
import com.lemline.runner.models.RetryModel
import com.lemline.runner.repositories.RetryRepository
import jakarta.inject.Inject
import kotlin.random.Random
import kotlin.time.ExperimentalTime


/**
 * Abstract base class for retry repository tests.
 */
@ExperimentalTime
internal abstract class RetryRepositoryTest : OutboxRepositoryTest<RetryModel>() {

    @Inject
    override lateinit var repository: RetryRepository

    override fun createWithState(state: String) = RetryModel(
        workflowId = IdGenerator.generateTimeBasedId(),
        workflowName = Random.nextBytes(10).toString(),
        workflowVersion = Random.nextBytes(10).toString(),
        workflowPosition = Random.nextBytes(10).toString(),
        workflowState = state,
        message = null,
    )

    override fun copyModel(model: RetryModel, state: String) = model.copy(workflowState = state)
}
