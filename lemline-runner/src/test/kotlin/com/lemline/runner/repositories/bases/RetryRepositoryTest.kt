// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.common.random.random
import com.lemline.runner.models.RetryOutboxModel
import com.lemline.runner.random.random
import com.lemline.runner.repositories.RetryRepository
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime
import kotlin.time.Instant


/**
 * Abstract base class for retry repository tests.
 */
@ExperimentalTime
internal abstract class RetryRepositoryTest : OutboxRepositoryTest<RetryOutboxModel>() {

    @Inject
    override lateinit var repository: RetryRepository

    override fun createRandomEntity() = RetryOutboxModel.random()

    override fun changeDelayedUntil(model: RetryOutboxModel) =
        model.copy().apply { outboxDelayedUntil = Instant.random() }
}
