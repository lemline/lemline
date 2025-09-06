// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.runner.instances.InstanceMessage
import com.lemline.runner.models.IDV7
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

    override fun createRandomEntity() = RetryOutboxModel(
        id = IDV7.random(),
        instanceMessage = InstanceMessage.random(),
        outboxScheduledFor = Instant.random(),
        errorReason = String.random(),
        errorClass = String.random(),
        errorMessage = String.random(),
        errorStackTrace = String.random(),
    )

    override fun changeDelayedUntil(model: RetryOutboxModel) = model.copy(outboxDelayedUntil = Instant.random())
}
