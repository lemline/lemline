// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.common.ids.IdGenerator
import com.lemline.runner.models.WaitModel
import com.lemline.runner.repositories.WaitRepository
import jakarta.inject.Inject
import kotlin.random.Random
import kotlin.time.ExperimentalTime

/**
 * Abstract base class for wait repository tests.
 */
@ExperimentalTime
internal abstract class WaitRepositoryTest : OutboxRepositoryTest<WaitModel>() {

    @Inject
    override lateinit var repository: WaitRepository

    override fun createWithState(state: String) = WaitModel(
        workflowId = IdGenerator.generateTimeBasedId(),
        workflowName = Random.nextBytes(10).toString(),
        workflowVersion = Random.nextBytes(10).toString(),
        workflowPosition = Random.nextBytes(10).toString(),
        workflowState = state
    )

    override fun copyModel(model: WaitModel, state: String) = model.copy(workflowState = state)
}
