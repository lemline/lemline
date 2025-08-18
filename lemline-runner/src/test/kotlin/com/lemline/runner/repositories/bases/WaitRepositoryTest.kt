// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.common.ids.IdGenerator
import com.lemline.runner.models.WaitModel
import com.lemline.runner.repositories.WaitRepository
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime

/**
 * Abstract base class for wait repository tests.
 */
@ExperimentalTime
internal abstract class WaitRepositoryTest : OutboxRepositoryTest<WaitModel>() {

    @Inject
    override lateinit var repository: WaitRepository

    override fun createRandomEntity() = WaitModel(
        workflowId = IdGenerator.generateTimeBasedId(),
        workflowName = randomString,
        workflowVersion = randomString,
        workflowPosition = randomString,
        workflowState = randomString,
    )

    override fun changeDelayedUntil(model: WaitModel) = model.copy(outboxDelayedUntil = randomInstant)
}
