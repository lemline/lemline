// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.common.ids.IdGenerator
import com.lemline.runner.models.ParentModel
import com.lemline.runner.repositories.ParentRepository
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime


/**
 * Abstract base class for retry repository tests.
 */
@ExperimentalTime
internal abstract class ParentRepositoryTest : OutboxRepositoryTest<ParentModel>() {

    @Inject
    override lateinit var repository: ParentRepository

    override fun createRandomEntity() = ParentModel(
        workflowId = IdGenerator.generateTimeBasedId(),
        workflowName = randomString,
        workflowVersion = randomString,
        workflowPosition = randomString,
        workflowState = randomString,
    )

    override fun changeDelayedUntil(model: ParentModel) = model.copy(outboxDelayedUntil = randomInstant)
}
