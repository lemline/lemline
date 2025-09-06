// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.runner.instances.InstanceMessageTest.Companion.sampleInstance
import com.lemline.runner.models.IDV7
import com.lemline.runner.models.WaitOutboxModel
import com.lemline.runner.repositories.WaitRepository
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime

/**
 * Abstract base class for wait repository tests.
 */
@ExperimentalTime
internal abstract class WaitRepositoryTest : OutboxRepositoryTest<WaitOutboxModel>() {

    @Inject
    override lateinit var repository: WaitRepository

    override fun createRandomEntity() = WaitOutboxModel(
        id = IDV7.new(),
        instanceMessage = sampleInstance(),
        outboxScheduledFor = randomInstant,
    )

    override fun changeDelayedUntil(model: WaitOutboxModel) = model.copy(outboxDelayedUntil = randomInstant)
}
