// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.common.ids.IdGenerator
import com.lemline.runner.models.RunWorkflowModel
import com.lemline.runner.repositories.RunWorkflowRepository
import jakarta.inject.Inject
import kotlin.random.Random
import kotlin.time.ExperimentalTime


/**
 * Abstract base class for retry repository tests.
 */
@ExperimentalTime
internal abstract class RunWorkflowRepositoryTest : OutboxRepositoryTest<RunWorkflowModel>() {

    @Inject
    override lateinit var repository: RunWorkflowRepository

    override fun createWithState(state: String) = RunWorkflowModel(
        workflowId = IdGenerator.generateTimeBasedId(),
        workflowName = Random.nextBytes(10).toString(),
        workflowVersion = Random.nextBytes(10).toString(),
        workflowPosition = Random.nextBytes(10).toString(),
        workflowState = state,
    )

    override fun copyModel(model: RunWorkflowModel, state: String) = model.copy(workflowState = state)
}
