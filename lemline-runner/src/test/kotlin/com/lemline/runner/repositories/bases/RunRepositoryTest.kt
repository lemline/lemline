// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.runner.models.RunModel
import com.lemline.runner.repositories.RunRepository
import jakarta.inject.Inject


/**
 * Abstract base class for retry repository tests.
 */
internal abstract class RunRepositoryTest : OutboxRepositoryTest<RunModel>() {

    @Inject
    override lateinit var repository: RunRepository

    override fun createWithMessage(message: String) = RunModel(message = message)

    override fun copyModel(model: RunModel, message: String) = model.copy(message = message)
}
