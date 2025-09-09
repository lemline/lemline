// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.common.random.random
import com.lemline.runner.models.ParentOutboxModel
import com.lemline.runner.random.random
import com.lemline.runner.repositories.ParentRepository
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi


/**
 * Abstract base class for retry repository tests.
 */
@ExperimentalTime
@ExperimentalSerializationApi
internal abstract class ParentRepositoryTest : OutboxRepositoryTest<ParentOutboxModel>() {

    @Inject
    override lateinit var repository: ParentRepository

    override fun createRandomEntity() = ParentOutboxModel.random()

    override fun changeDelayedUntil(model: ParentOutboxModel) =
        model.copy().apply { outboxDelayedUntil = Instant.random() }
}
