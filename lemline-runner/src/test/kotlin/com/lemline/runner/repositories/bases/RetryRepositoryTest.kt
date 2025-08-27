// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.models.RetryModel
import com.lemline.runner.repositories.RetryRepository
import jakarta.inject.Inject
import java.util.*
import kotlin.time.ExperimentalTime


/**
 * Abstract base class for retry repository tests.
 */
@ExperimentalTime
internal abstract class RetryRepositoryTest : OutboxRepositoryTest<RetryModel>() {

    @Inject
    override lateinit var repository: RetryRepository

    override fun createRandomEntity() = RetryModel(
        instance = InstanceMessage.fromStrings(
            workflowId = UUID.randomUUID(),
            workflowName = randomString,
            workflowVersion = randomString,
            workflowPosition = randomString,
            workflowState = randomString,
            parentId = null,
        ),
        outboxScheduledFor = randomInstant,
    )

    override fun changeDelayedUntil(model: RetryModel) = model.copy(outboxDelayedUntil = randomInstant)
}
