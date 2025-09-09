// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.common.random.random
import com.lemline.runner.models.WaitOutboxModel
import com.lemline.runner.random.random
import com.lemline.runner.repositories.WaitRepository
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Abstract base class for wait repository tests.
 */
@ExperimentalTime
@ExperimentalSerializationApi
internal abstract class WaitRepositoryTest : OutboxRepositoryTest<WaitOutboxModel>() {

    @Inject
    override lateinit var repository: WaitRepository

    override fun createRandomEntity() = WaitOutboxModel.random()

    override fun changeDelayedUntil(model: WaitOutboxModel) =
        model.copy().apply { outboxDelayedUntil = Instant.random() }
}
