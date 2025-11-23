// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.common.random.random
import com.lemline.runner.models.RetryModel
import com.lemline.runner.random.random
import com.lemline.runner.repositories.RetryRepository
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi


/**
 * Abstract base class for retry repository tests.
 */
@ExperimentalTime
@ExperimentalSerializationApi
internal abstract class RetryRepositoryTest : OutboxRepositoryTest<RetryModel>() {

    @Inject
    override lateinit var repository: RetryRepository

    override fun createRandomEntity() = RetryModel.random()

    override fun changeDelayedUntil(model: RetryModel) =
        model.copy().apply { outboxDelayedUntil = Instant.random() }
}
