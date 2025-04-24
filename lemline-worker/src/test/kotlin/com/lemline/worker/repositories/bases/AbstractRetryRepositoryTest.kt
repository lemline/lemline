// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.repositories.bases

import com.lemline.worker.models.RetryModel
import com.lemline.worker.repositories.RetryRepository
import jakarta.inject.Inject


/**
 * Abstract base class for retry repository tests.
 */
internal abstract class AbstractRetryRepositoryTest : AbstractOutboxRepositoryTest<RetryModel>() {

    @Inject
    override lateinit var repository: RetryRepository

    override fun createModel() = RetryModel()
}
