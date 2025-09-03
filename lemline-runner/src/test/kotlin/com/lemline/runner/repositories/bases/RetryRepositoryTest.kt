// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.runner.instances.InstanceMessage
import com.lemline.runner.models.RetryOutboxModel
import com.lemline.runner.repositories.RetryRepository
import jakarta.inject.Inject
import java.util.*
import kotlin.time.ExperimentalTime


/**
 * Abstract base class for retry repository tests.
 */
@ExperimentalTime
internal abstract class RetryRepositoryTest : OutboxRepositoryTest<RetryOutboxModel>() {

    @Inject
    override lateinit var repository: RetryRepository

    override fun createRandomEntity() = RetryOutboxModel(
        id = UUID.randomUUID(),
        instance = InstanceMessage.fromStrings(
            workflowId = UUID.randomUUID(),
            workflowName = randomString,
            workflowVersion = randomString,
            workflowPosition = randomString,
            workflowState = randomString,
            parentId = null,
        ),
        outboxScheduledFor = randomInstant,
        errorClass = randomString,
        errorMessage = randomString,
        errorStackTrace = randomString,
    )

    override fun changeDelayedUntil(model: RetryOutboxModel) = model.copy(outboxDelayedUntil = randomInstant)
}
