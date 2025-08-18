// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.common.ids.IdGenerator
import com.lemline.runner.models.RunWorkflowModel
import com.lemline.runner.repositories.RunWorkflowRepository
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime


/**
 * Abstract base class for retry repository tests.
 */
@ExperimentalTime
internal abstract class RunWorkflowRepositoryTest : OutboxRepositoryTest<RunWorkflowModel>() {

    @Inject
    override lateinit var repository: RunWorkflowRepository

    override fun createRandomEntity() = RunWorkflowModel(
        workflowId = IdGenerator.generateTimeBasedId(),
        workflowName = randomString,
        workflowVersion = randomString,
        workflowPosition = randomString,
        workflowState = randomString,
    )

    override fun changeDelayedUntil(model: RunWorkflowModel) = model.copy(outboxDelayedUntil = randomInstant)
}
