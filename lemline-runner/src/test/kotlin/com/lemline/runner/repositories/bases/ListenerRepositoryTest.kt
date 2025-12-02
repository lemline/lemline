// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.common.random.random
import com.lemline.runner.models.ListenerModel
import com.lemline.runner.random.random
import com.lemline.runner.repositories.ListenerRepository
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Abstract base class for testing ListenerRepository implementations.
 *
 * Extends OutboxRepositoryTest to inherit all standard outbox tests,
 * and adds listener-specific tests.
 */
@ExperimentalTime
@ExperimentalSerializationApi
internal abstract class ListenerRepositoryTest : OutboxRepositoryTest<ListenerModel>() {

    @Inject
    override lateinit var repository: ListenerRepository

    override fun createRandomEntity() = ListenerModel.random()

    override fun changeDelayedUntil(model: ListenerModel) =
        model.copy().apply { outboxDelayedUntil = Instant.random() }
}
