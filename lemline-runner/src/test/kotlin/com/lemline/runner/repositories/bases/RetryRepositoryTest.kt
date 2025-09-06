// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.runner.instances.InstanceMessageTest.Companion.sampleInstance
import com.lemline.runner.models.IDV7
import com.lemline.runner.models.RetryOutboxModel
import com.lemline.runner.repositories.RetryRepository
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime


/**
 * Abstract base class for retry repository tests.
 */
@ExperimentalTime
internal abstract class RetryRepositoryTest : OutboxRepositoryTest<RetryOutboxModel>() {

    @Inject
    override lateinit var repository: RetryRepository

    override fun createRandomEntity() = RetryOutboxModel(
        id = IDV7.new(),
        instanceMessage = sampleInstance(),
        outboxScheduledFor = randomInstant,
        errorReason = randomString,
        errorClass = randomString,
        errorMessage = randomString,
        errorStackTrace = randomString,
    )

    override fun changeDelayedUntil(model: RetryOutboxModel) = model.copy(outboxDelayedUntil = randomInstant)
}
