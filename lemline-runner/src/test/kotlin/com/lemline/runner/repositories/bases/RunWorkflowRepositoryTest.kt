// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.runner.models.RunWorkflowModel
import com.lemline.runner.repositories.RunWorkflowRepository
import jakarta.inject.Inject


/**
 * Abstract base class for retry repository tests.
 */
internal abstract class RunWorkflowRepositoryTest : OutboxRepositoryTest<RunWorkflowModel>() {

    @Inject
    override lateinit var repository: RunWorkflowRepository

    override fun createWithMessage(message: String) = RunWorkflowModel(message = message)

    override fun copyModel(model: RunWorkflowModel, message: String) = model.copy(message = message)
}
