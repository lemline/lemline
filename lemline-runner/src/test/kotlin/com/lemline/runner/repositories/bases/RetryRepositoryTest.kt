// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.common.ids.IdGenerator
import com.lemline.runner.models.RetryModel
import com.lemline.runner.repositories.RetryRepository
import jakarta.inject.Inject


/**
 * Abstract base class for retry repository tests.
 */
internal abstract class RetryRepositoryTest : OutboxRepositoryTest<RetryModel>() {

    @Inject
    override lateinit var repository: RetryRepository

    override fun createWithMessage(message: String) = RetryModel(
        workflowId = IdGenerator.generateTimeBasedId(),
        message = message
    )

    override fun copyModel(model: RetryModel, message: String) = model.copy(message = message)
}
